package com.stash.data.download.acquisition

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject

data class MuseAcquisitionJob(
    val id: String,
    val query: String,
    val attempt: Int,
    val approvedAt: String,
    val expiresAt: String,
    val origin: MuseAcquisitionOrigin = MuseAcquisitionOrigin.MANUAL_MISSING_SEARCH,
    val quality: MuseAcquisitionQuality = MuseAcquisitionQuality.LOSSLESS,
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val isrc: String? = null,
    val durationMs: Long? = null,
)

data class MuseAcquisitionClaim(
    val job: MuseAcquisitionJob,
    val leaseToken: String,
    val leaseExpiresAt: String,
)

enum class MuseAcquisitionContract(val wireName: String) {
    V1("muse-acquisition/v1"),
    V2("muse-acquisition/v2"),
}

enum class MuseAcquisitionOrigin(val wireName: String) {
    MANUAL_MISSING_SEARCH("manual_missing_search"),
    MANUAL_QUALITY_UPGRADE("manual_quality_upgrade"),
    ;

    companion object {
        fun parse(value: String): MuseAcquisitionOrigin? = when (value) {
            "manual_discord_search", MANUAL_MISSING_SEARCH.wireName -> MANUAL_MISSING_SEARCH
            MANUAL_QUALITY_UPGRADE.wireName -> MANUAL_QUALITY_UPGRADE
            else -> null
        }
    }
}

enum class MuseAcquisitionQuality(val wireName: String) {
    LOSSLESS("lossless"),
    HI_RES("hi_res"),
    MAX("max"),
    ;

    companion object {
        fun parse(value: String): MuseAcquisitionQuality? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class MuseDeviceCapabilities(
    val maxConcurrentJobs: Int = 1,
    val supportedQualities: Set<MuseAcquisitionQuality> = MuseAcquisitionQuality.entries.toSet(),
    val supportsDirectUpload: Boolean = true,
    val supportsResumableUpload: Boolean = true,
    val maxFileBytes: Long = TrackAcquisitionPipeline.MAX_ARTIFACT_BYTES,
)

data class MuseActualMedia(
    val codec: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val durationMs: Long,
)

data class MuseArtifactDescriptor(
    val localTrackId: String,
    val sha256: String,
    val sizeBytes: Long,
    val media: MuseActualMedia,
)

sealed interface MuseUploadTarget {
    data class Direct(
        val uploadUrl: String,
        val method: String,
        val headers: Map<String, String>,
    ) : MuseUploadTarget

    data class Resumable(
        val initUrl: String,
        val headers: Map<String, String>,
        val chunkSizeBytes: Long,
    ) : MuseUploadTarget
}

data class MuseUploadTicket(
    val id: String,
    val expiresAt: String,
    val target: MuseUploadTarget,
)

data class MuseIngestReceipt(
    val id: String,
    val sha256: String?,
)

sealed interface MuseAcquisitionConnectionCheck {
    data object Verified : MuseAcquisitionConnectionCheck
    data object AuthenticationFailed : MuseAcquisitionConnectionCheck
    data object Incompatible : MuseAcquisitionConnectionCheck
    data object Unreachable : MuseAcquisitionConnectionCheck
    data object NotConfigured : MuseAcquisitionConnectionCheck
}

class MuseAcquisitionRemoteException(
    val code: String,
    val retryable: Boolean,
) : Exception("Muse acquisition request failed ($code)")

@Singleton
class MuseAcquisitionClient @Inject constructor(
    private val prefs: MuseAcquisitionPreferences,
    httpClient: OkHttpClient,
) {
    private val client = httpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val uploadClient = httpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()

    /** Prefer the assignment-based v2 contract; downgrade only when v2 is absent. */
    suspend fun negotiateContract(): MuseAcquisitionContract = withContext(Dispatchers.IO) {
        val endpoint = endpoint() ?: throw MuseAcquisitionRemoteException("not_configured", false)
        negotiate(endpoint)
    }

    suspend fun checkConnection(): MuseAcquisitionConnectionCheck = withContext(Dispatchers.IO) {
        val endpoint = endpoint(requireEnabled = false)
            ?: return@withContext MuseAcquisitionConnectionCheck.NotConfigured
        try {
            negotiate(endpoint)
            MuseAcquisitionConnectionCheck.Verified
        } catch (error: MuseAcquisitionRemoteException) {
            when {
                error.code == "authentication_failed" -> MuseAcquisitionConnectionCheck.AuthenticationFailed
                !error.retryable -> MuseAcquisitionConnectionCheck.Incompatible
                else -> MuseAcquisitionConnectionCheck.Unreachable
            }
        }
    }

    /**
     * v2 has no global inbox. The broker returns either one assigned job or
     * HTTP 204. Device capabilities are scheduling hints, never authority.
     */
    suspend fun claimAssigned(
        capabilities: MuseDeviceCapabilities = MuseDeviceCapabilities(),
    ): MuseAcquisitionClaim? = withContext(Dispatchers.IO) {
        val endpoint = endpoint() ?: throw MuseAcquisitionRemoteException("not_configured", false)
        val body = JSONObject()
            .put("consumerId", endpoint.consumerId)
            .put(
                "deviceCapabilities",
                JSONObject()
                    .put("maxConcurrentJobs", capabilities.maxConcurrentJobs.coerceIn(1, 1))
                    .put(
                        "supportedQualities",
                        JSONArray(capabilities.supportedQualities.map(MuseAcquisitionQuality::wireName)),
                    )
                    .put("supportsDirectUpload", capabilities.supportsDirectUpload)
                    .put("supportsResumableUpload", capabilities.supportsResumableUpload),
            )
            .put(
                "constraints",
                JSONObject().put("maxFileBytes", capabilities.maxFileBytes),
            )
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = request(endpoint, "/v2/work/claim").post(body).build()
        executeJsonOrNoContent(request) { json -> parseV2Claim(json) }
    }

    suspend fun reportPhase(
        jobId: String,
        leaseToken: String,
        progress: TrackAcquisitionProgress,
    ) {
        val body = JSONObject()
            .put("consumerId", requireEndpoint().consumerId)
            .put("leaseToken", leaseToken)
            .put("phase", progress.phase.wireName)
        progress.bytesProcessed?.let { body.put("bytesProcessed", it) }
        progress.totalBytes?.let { body.put("totalBytes", it) }
        progress.percent?.let { body.put("percent", it) }
        postV2(jobId, "phase", body)
    }

    suspend fun heartbeat(jobId: String, leaseToken: String, phase: TrackAcquisitionPhase) {
        postV2(
            jobId,
            "heartbeat",
            JSONObject()
                .put("consumerId", requireEndpoint().consumerId)
                .put("leaseToken", leaseToken)
                .put("phase", phase.wireName),
        )
    }

    suspend fun requestUploadTicket(
        jobId: String,
        leaseToken: String,
        artifact: MuseArtifactDescriptor,
    ): MuseUploadTicket {
        val body = JSONObject()
            .put("consumerId", requireEndpoint().consumerId)
            .put("leaseToken", leaseToken)
            .put("artifact", artifact.toJson())
        return postV2(jobId, "upload-ticket", body) { parseUploadTicket(it) }
    }

    /** Uploads directly to stash-ingest; the Muse bearer token is never forwarded. */
    suspend fun uploadArtifact(
        ticket: MuseUploadTicket,
        file: File,
        expectedSha256: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): MuseIngestReceipt = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "artifact is missing" }
        val receipt = when (val target = ticket.target) {
            is MuseUploadTarget.Direct -> uploadDirect(target, file, onProgress)
            is MuseUploadTarget.Resumable -> uploadResumable(target, file, onProgress)
        }
        if (receipt.sha256 != null && !receipt.sha256.equals(expectedSha256, ignoreCase = true)) {
            throw MuseAcquisitionRemoteException("receipt_hash_mismatch", false)
        }
        receipt
    }

    suspend fun completeV2(
        jobId: String,
        leaseToken: String,
        receipt: MuseIngestReceipt,
        artifact: MuseArtifactDescriptor,
    ) {
        postV2(
            jobId,
            "complete",
            JSONObject()
                .put("consumerId", requireEndpoint().consumerId)
                .put("leaseToken", leaseToken)
                .put("ingestReceiptId", receipt.id)
                .put("artifact", artifact.toJson()),
        )
    }

    suspend fun failV2(
        jobId: String,
        leaseToken: String,
        errorCode: String,
        retryable: Boolean,
    ) {
        postV2(
            jobId,
            "fail",
            JSONObject()
                .put("consumerId", requireEndpoint().consumerId)
                .put("leaseToken", leaseToken)
                .put("errorCode", errorCode.take(64))
                .put("retryable", retryable),
        )
    }

    suspend fun listApproved(limit: Int = 5): List<MuseAcquisitionJob> = withContext(Dispatchers.IO) {
        val endpoint = endpoint() ?: return@withContext emptyList()
        val request = request(endpoint, "/v1/jobs?limit=${limit.coerceIn(1, 20)}").get().build()
        executeJson(request) { json ->
            requireContract(json)
            val jobs = json.getJSONArray("jobs")
            buildList(jobs.length()) {
                for (index in 0 until jobs.length()) add(parseJob(jobs.getJSONObject(index)))
            }
        }
    }

    suspend fun claim(jobId: String): MuseAcquisitionClaim = withContext(Dispatchers.IO) {
        val endpoint = endpoint() ?: throw MuseAcquisitionRemoteException("not_configured", false)
        val body = JSONObject()
            .put("consumerId", endpoint.consumerId)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = request(endpoint, "/v1/jobs/$jobId/claim").post(body).build()
        executeJson(request) { json ->
            requireContract(json)
            MuseAcquisitionClaim(
                job = parseJob(json.getJSONObject("job")),
                leaseToken = json.getString("leaseToken"),
                leaseExpiresAt = json.getString("leaseExpiresAt"),
            )
        }
    }

    suspend fun reportDownloading(jobId: String, leaseToken: String) {
        report(jobId, leaseToken, JSONObject().put("state", "downloading"))
    }

    suspend fun reportCompleted(jobId: String, leaseToken: String, localTrackId: String) {
        report(
            jobId,
            leaseToken,
            JSONObject()
                .put("state", "completed")
                .put("result", JSONObject().put("localTrackId", localTrackId.take(128))),
        )
    }

    suspend fun reportFailed(
        jobId: String,
        leaseToken: String,
        errorCode: String,
        retryable: Boolean,
    ) {
        report(
            jobId,
            leaseToken,
            JSONObject()
                .put("state", "failed")
                .put("retryable", retryable)
                .put("errorCode", errorCode.take(64)),
        )
    }

    private suspend fun report(jobId: String, leaseToken: String, report: JSONObject) = withContext(Dispatchers.IO) {
        val endpoint = endpoint() ?: throw MuseAcquisitionRemoteException("not_configured", false)
        val body = report
            .put("consumerId", endpoint.consumerId)
            .put("leaseToken", leaseToken)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = request(endpoint, "/v1/jobs/$jobId/report").post(body).build()
        executeJson(request) { json -> requireContract(json) }
    }

    private suspend fun negotiate(endpoint: Endpoint): MuseAcquisitionContract {
        val v2Request = request(endpoint, "/v2/capabilities").get().build()
        try {
            client.newCall(v2Request).execute().use { response ->
                val body = readSmallBody(response.body)
                when {
                    response.code in AUTH_STATUS_CODES -> {
                        throw MuseAcquisitionRemoteException("authentication_failed", false)
                    }
                    response.isSuccessful && validCapabilities(body, MuseAcquisitionContract.V2) -> {
                        return MuseAcquisitionContract.V2
                    }
                    response.code !in V2_ABSENT_STATUS_CODES -> {
                        throw MuseAcquisitionRemoteException(
                            if (response.isSuccessful) "incompatible_contract" else "http_${response.code}",
                            response.code == 408 || response.code == 429 || response.code >= 500,
                        )
                    }
                }
            }
        } catch (error: MuseAcquisitionRemoteException) {
            throw error
        } catch (_: Exception) {
            throw MuseAcquisitionRemoteException("network_error", true)
        }

        // Explicit compatibility path: only a definitively absent v2 endpoint
        // permits downgrade. A transient v2 failure never silently changes the
        // completion semantics to v1.
        val v1Request = request(endpoint, "/v1/capabilities").get().build()
        try {
            client.newCall(v1Request).execute().use { response ->
                val body = readSmallBody(response.body)
                when {
                    response.code in AUTH_STATUS_CODES -> {
                        throw MuseAcquisitionRemoteException("authentication_failed", false)
                    }
                    response.isSuccessful && validCapabilities(body, MuseAcquisitionContract.V1) -> {
                        return MuseAcquisitionContract.V1
                    }
                    response.isSuccessful || response.code in PERMANENT_STATUS_CODES -> {
                        throw MuseAcquisitionRemoteException("incompatible_contract", false)
                    }
                    else -> throw MuseAcquisitionRemoteException("http_${response.code}", true)
                }
            }
        } catch (error: MuseAcquisitionRemoteException) {
            throw error
        } catch (_: Exception) {
            throw MuseAcquisitionRemoteException("network_error", true)
        }
    }

    private suspend fun requireEndpoint(): Endpoint = endpoint()
        ?: throw MuseAcquisitionRemoteException("not_configured", false)

    private suspend fun postV2(jobId: String, action: String, body: JSONObject) = withContext(Dispatchers.IO) {
        require(jobId.matches(UUID_PATTERN))
        val endpoint = endpoint() ?: throw MuseAcquisitionRemoteException("not_configured", false)
        val request = request(endpoint, "/v2/work/$jobId/$action")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeJsonOrNoContent(request) { json -> requireContract(json, MuseAcquisitionContract.V2) }
    }

    private suspend fun <T> postV2(
        jobId: String,
        action: String,
        body: JSONObject,
        parse: (JSONObject) -> T,
    ): T = withContext(Dispatchers.IO) {
        require(jobId.matches(UUID_PATTERN))
        val endpoint = endpoint() ?: throw MuseAcquisitionRemoteException("not_configured", false)
        val request = request(endpoint, "/v2/work/$jobId/$action")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeJson(request, parse)
    }

    private fun parseV2Claim(json: JSONObject): MuseAcquisitionClaim {
        requireContract(json, MuseAcquisitionContract.V2)
        val lease = json.optJSONObject("lease")
        return MuseAcquisitionClaim(
            job = parseJob(json.getJSONObject("job"), MuseAcquisitionContract.V2),
            leaseToken = lease?.getString("token") ?: json.getString("leaseToken"),
            leaseExpiresAt = lease?.getString("expiresAt") ?: json.getString("leaseExpiresAt"),
        )
    }

    private fun parseUploadTicket(json: JSONObject): MuseUploadTicket {
        requireContract(json, MuseAcquisitionContract.V2)
        val ticket = json.optJSONObject("ticket") ?: json
        val id = ticket.optString("id").ifBlank { ticket.getString("ticketId") }
        require(id.length in 8..256)
        val target = ticket.optJSONObject("resumable")?.let { resumable ->
            MuseUploadTarget.Resumable(
                initUrl = requireUploadUrl(resumable.getString("initUrl")),
                headers = parseHeaders(resumable.optJSONObject("headers")),
                chunkSizeBytes = resumable.optLong("chunkSizeBytes", DEFAULT_UPLOAD_CHUNK_BYTES)
                    .coerceIn(MIN_UPLOAD_CHUNK_BYTES, MAX_UPLOAD_CHUNK_BYTES),
            )
        } ?: MuseUploadTarget.Direct(
            uploadUrl = requireUploadUrl(ticket.getString("uploadUrl")),
            method = requireUploadMethod(ticket.optString("method", "PUT")),
            headers = parseHeaders(ticket.optJSONObject("headers")),
        )
        return MuseUploadTicket(
            id = id,
            expiresAt = ticket.optString("expiresAt"),
            target = target,
        )
    }

    private suspend fun uploadDirect(
        target: MuseUploadTarget.Direct,
        file: File,
        onProgress: (Long, Long) -> Unit,
    ): MuseIngestReceipt {
        val body = ProgressFileRequestBody(file, onProgress)
        val builder = Request.Builder().url(target.uploadUrl).applyTicketHeaders(target.headers)
        val request = builder.method(target.method, body).build()
        return executeReceiptRequest(request)
    }

    private suspend fun uploadResumable(
        target: MuseUploadTarget.Resumable,
        file: File,
        onProgress: (Long, Long) -> Unit,
    ): MuseIngestReceipt {
        val initialized = initializeResumable(target)
        if (initialized is ResumableInitialization.AlreadyComplete) return initialized.receipt
        initialized as ResumableInitialization.Session
        if (initialized.length != file.length()) {
            throw MuseAcquisitionRemoteException("upload_length_mismatch", false)
        }

        var offset = reconcileResumableOffset(target, initialized)
        onProgress(offset, file.length())
        RandomAccessFile(file, "r").use { input ->
            while (offset < file.length()) {
                currentCoroutineContext().ensureActive()
                val count = minOf(target.chunkSizeBytes, file.length() - offset).toInt()
                val chunk = ByteArray(count)
                input.seek(offset)
                input.readFully(chunk)
                val expectedNextOffset = offset + count
                val request = Request.Builder()
                    .url(initialized.sessionUrl)
                    .applyTicketHeaders(target.headers)
                    .header("Content-Type", OFFSET_UPLOAD_MEDIA_TYPE.toString())
                    .header("Upload-Offset", offset.toString())
                    .header("X-Chunk-Sha256", sha256(chunk))
                    .patch(chunk.toRequestBody(OFFSET_UPLOAD_MEDIA_TYPE))
                    .build()
                val serverOffset = appendResumableChunk(request, expectedNextOffset, file.length())
                if (serverOffset <= offset) {
                    throw MuseAcquisitionRemoteException("invalid_upload_offset", false)
                }
                offset = serverOffset
                onProgress(offset, file.length())
            }
        }

        val completeRequest = Request.Builder()
            .url(appendCompletePath(initialized.sessionUrl))
            .applyTicketHeaders(target.headers)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeReceiptRequest(completeRequest)
    }

    private suspend fun initializeResumable(
        target: MuseUploadTarget.Resumable,
    ): ResumableInitialization {
        val request = Request.Builder()
            .url(target.initUrl)
            .applyTicketHeaders(target.headers)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeUploadRequest(request) { response, body ->
            requireSuccessfulUpload(response.code)
            val json = parseUploadJson(body)
            json.optJSONObject("receipt")?.let {
                return@executeUploadRequest ResumableInitialization.AlreadyComplete(parseIngestReceipt(json))
            }
            val length = json.getLong("uploadLength")
            val offset = json.getLong("uploadOffset")
            require(length > 0L && offset in 0..length)
            ResumableInitialization.Session(
                sessionUrl = requireSameOriginUploadUrl(target.initUrl, json.getString("uploadUrl")),
                offset = offset,
                length = length,
            )
        }
    }

    private suspend fun reconcileResumableOffset(
        target: MuseUploadTarget.Resumable,
        session: ResumableInitialization.Session,
    ): Long {
        val request = Request.Builder()
            .url(session.sessionUrl)
            .applyTicketHeaders(target.headers)
            .head()
            .build()
        return executeUploadRequest(request) { response, _ ->
            requireSuccessfulUpload(response.code)
            val length = response.header("Upload-Length")?.toLongOrNull()
                ?: throw MuseAcquisitionRemoteException("invalid_upload_session", false)
            val offset = response.header("Upload-Offset")?.toLongOrNull()
                ?: throw MuseAcquisitionRemoteException("invalid_upload_session", false)
            if (length != session.length || offset !in 0..length) {
                throw MuseAcquisitionRemoteException("invalid_upload_session", false)
            }
            offset
        }
    }

    private suspend fun appendResumableChunk(
        request: Request,
        expectedNextOffset: Long,
        uploadLength: Long,
    ): Long = executeUploadRequest(request) { response, _ ->
        if (response.code != 204 && response.code != 409) requireSuccessfulUpload(response.code)
        val offset = response.header("Upload-Offset")?.toLongOrNull()
            ?: throw MuseAcquisitionRemoteException("invalid_upload_offset", false)
        if (offset !in 0..uploadLength) {
            throw MuseAcquisitionRemoteException("invalid_upload_offset", false)
        }
        if (response.code == 204 && offset != expectedNextOffset) {
            throw MuseAcquisitionRemoteException("invalid_upload_offset", false)
        }
        offset
    }

    private suspend fun executeReceiptRequest(request: Request): MuseIngestReceipt =
        executeUploadRequest(request) { response, body ->
            requireSuccessfulUpload(response.code)
            parseIngestReceipt(parseUploadJson(body))
        }

    private suspend fun <T> executeUploadRequest(
        request: Request,
        parse: (okhttp3.Response, String?) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        val call = uploadClient.newCall(request)
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                val responseBody = readSmallBody(response.body)
                return try {
                    parse(response, responseBody)
                } catch (error: MuseAcquisitionRemoteException) {
                    throw error
                } catch (_: Exception) {
                    throw MuseAcquisitionRemoteException("invalid_upload_response", false)
                }
            }
        } catch (error: MuseAcquisitionRemoteException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw MuseAcquisitionRemoteException("upload_network_error", true)
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun parseUploadJson(body: String?): JSONObject =
        runCatching { JSONObject(body.orEmpty()) }.getOrElse {
            throw MuseAcquisitionRemoteException("invalid_upload_response", false)
        }

    private fun parseIngestReceipt(json: JSONObject): MuseIngestReceipt {
        val receipt = json.optJSONObject("receipt") ?: json
        val id = receipt.optString("receiptId")
            .ifBlank { json.optString("ingestReceiptId") }
            .ifBlank { json.optString("receiptId") }
        if (id.isBlank() || id.length > 256) {
            throw MuseAcquisitionRemoteException("missing_upload_receipt", false)
        }
        val sha256 = receipt.optString("sourceSha256")
            .ifBlank { receipt.optString("sha256") }
            .ifBlank { json.optString("sha256") }
            .takeIf(String::isNotBlank)
        return MuseIngestReceipt(id, sha256)
    }

    private fun requireSuccessfulUpload(statusCode: Int) {
        if (statusCode in 200..299) return
        throw MuseAcquisitionRemoteException(
            code = "upload_http_$statusCode",
            retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500,
        )
    }

    private fun requireSameOriginUploadUrl(initUrl: String, value: String): String {
        val origin = requireNotNull(initUrl.toHttpUrlOrNull())
        val resolved = origin.resolve(value) ?: throw IllegalArgumentException("invalid upload session URL")
        require(resolved.isHttps && resolved.username.isBlank() && resolved.password.isBlank())
        require(resolved.fragment == null)
        require(resolved.host == origin.host && resolved.port == origin.port && resolved.scheme == origin.scheme)
        return resolved.toString().trimEnd('/')
    }

    private fun appendCompletePath(sessionUrl: String): String =
        requireNotNull(sessionUrl.toHttpUrlOrNull())
            .newBuilder()
            .addPathSegment("complete")
            .build()
            .toString()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun MuseArtifactDescriptor.toJson(): JSONObject = JSONObject()
        .put("localTrackId", localTrackId.take(128))
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .put(
            "media",
            JSONObject()
                .put("codec", media.codec)
                .put("bitrateKbps", media.bitrateKbps)
                .put("durationMs", media.durationMs)
                .apply {
                    media.sampleRateHz?.let { put("sampleRateHz", it) }
                    media.bitsPerSample?.let { put("bitsPerSample", it) }
                },
        )

    private fun requireUploadUrl(value: String): String {
        val url = value.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isBlank() && url.password.isBlank())
        require(url.fragment == null)
        return url.toString()
    }

    private fun requireUploadMethod(value: String): String = value.uppercase().also {
        require(it in ALLOWED_UPLOAD_METHODS)
    }

    private fun parseHeaders(json: JSONObject?): Map<String, String> {
        json ?: return emptyMap()
        require(json.length() <= MAX_UPLOAD_HEADERS)
        return buildMap {
            for (name in json.keys()) {
                val value = json.getString(name)
                require(name.matches(HEADER_NAME_PATTERN) && value.length <= MAX_HEADER_VALUE_LENGTH)
                require('\n' !in value && '\r' !in value)
                put(name, value)
            }
        }
    }

    private fun Request.Builder.applyTicketHeaders(headers: Map<String, String>) = apply {
        headers.forEach { (name, value) -> header(name, value) }
        header("Cache-Control", "no-store")
    }

    private suspend fun endpoint(requireEnabled: Boolean = true): Endpoint? {
        val config = prefs.current()
        val baseUrl = MuseAcquisitionEndpoint.normalize(config.serverUrl) ?: return null
        if (config.token.isBlank() || config.consumerId.isBlank() || (requireEnabled && !config.enabled)) return null
        return Endpoint(baseUrl, config.token, config.consumerId)
    }

    private fun request(endpoint: Endpoint, path: String): Request.Builder = Request.Builder()
        .url("${endpoint.baseUrl}$path")
        .header("Authorization", "Bearer ${endpoint.token}")
        .header("Accept", "application/json")
        .header("Cache-Control", "no-store")

    private fun <T> executeJson(request: Request, parse: (JSONObject) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
                val body = readSmallBody(response.body)
                if (!response.isSuccessful) {
                    val serverCode = runCatching { JSONObject(body.orEmpty()).optString("error") }
                        .getOrNull()
                        ?.takeIf { it.matches(ERROR_CODE_PATTERN) }
                        ?: "http_${response.code}"
                    throw MuseAcquisitionRemoteException(
                        code = serverCode,
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    )
                }
                val json = runCatching { JSONObject(body.orEmpty()) }.getOrElse {
                    throw MuseAcquisitionRemoteException("invalid_response", false)
                }
                return runCatching { parse(json) }.getOrElse { error ->
                    if (error is MuseAcquisitionRemoteException) throw error
                    throw MuseAcquisitionRemoteException("invalid_response", false)
                }
            }
        } catch (error: MuseAcquisitionRemoteException) {
            throw error
        } catch (_: Exception) {
            throw MuseAcquisitionRemoteException("network_error", true)
        }
    }

    private fun <T> executeJsonOrNoContent(request: Request, parse: (JSONObject) -> T): T? {
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 204) return null
                val body = readSmallBody(response.body)
                if (!response.isSuccessful) {
                    val serverCode = runCatching { JSONObject(body.orEmpty()).optString("error") }
                        .getOrNull()
                        ?.takeIf { it.matches(ERROR_CODE_PATTERN) }
                        ?: "http_${response.code}"
                    throw MuseAcquisitionRemoteException(
                        code = serverCode,
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    )
                }
                val json = runCatching { JSONObject(body.orEmpty()) }.getOrElse {
                    throw MuseAcquisitionRemoteException("invalid_response", false)
                }
                return runCatching { parse(json) }.getOrElse { error ->
                    if (error is MuseAcquisitionRemoteException) throw error
                    throw MuseAcquisitionRemoteException("invalid_response", false)
                }
            }
        } catch (error: MuseAcquisitionRemoteException) {
            throw error
        } catch (_: Exception) {
            throw MuseAcquisitionRemoteException("network_error", true)
        }
    }

    private fun parseJob(
        json: JSONObject,
        contract: MuseAcquisitionContract = MuseAcquisitionContract.V1,
    ): MuseAcquisitionJob {
        if (json.has("contract")) requireContract(json, contract)
        val origin = MuseAcquisitionOrigin.parse(json.getString("origin")) ?: error("forbidden origin")
        require(json.getString("status") in ALLOWED_JOB_STATUSES)
        require(FORBIDDEN_JOB_FIELDS.none(json::has))
        val id = json.getString("id")
        require(id.matches(UUID_PATTERN))
        val track = json.optJSONObject("track")
        val artist = track?.optString("artist")?.takeIf(String::isNotBlank)
            ?: json.optString("artist").takeIf(String::isNotBlank)
        val title = track?.optString("title")?.takeIf(String::isNotBlank)
            ?: json.optString("title").takeIf(String::isNotBlank)
        val query = json.optString("query").trim().ifBlank {
            listOfNotNull(artist, title).joinToString(" ").trim()
        }
        require(query.isNotEmpty() && query.length <= MAX_QUERY_LENGTH)
        return MuseAcquisitionJob(
            id = id,
            query = query,
            attempt = json.getInt("attempt").coerceAtLeast(0),
            approvedAt = json.getString("approvedAt"),
            expiresAt = json.getString("expiresAt"),
            origin = origin,
            quality = requireNotNull(
                MuseAcquisitionQuality.parse(
                    json.optString("quality", track?.optString("quality", "lossless") ?: "lossless"),
                ),
            ),
            artist = artist,
            title = title,
            album = track?.optString("album")?.takeIf(String::isNotBlank)
                ?: json.optString("album").takeIf(String::isNotBlank),
            isrc = track?.optString("isrc")?.takeIf(String::isNotBlank)
                ?: json.optString("isrc").takeIf(String::isNotBlank),
            durationMs = (track?.optLong("durationMs", -1L) ?: json.optLong("durationMs", -1L))
                .takeIf { it > 0L },
        )
    }

    private fun requireContract(
        json: JSONObject,
        contract: MuseAcquisitionContract = MuseAcquisitionContract.V1,
    ) {
        require(json.getString("contract") == contract.wireName)
    }

    private fun validCapabilities(body: String?, contract: MuseAcquisitionContract): Boolean = runCatching {
        val json = JSONObject(body.orEmpty())
        requireContract(json, contract)
        val capabilities = json.getJSONArray("capabilities")
        val advertised = buildSet(capabilities.length()) {
            for (index in 0 until capabilities.length()) add(capabilities.getString(index))
        }
        when (contract) {
            MuseAcquisitionContract.V1 ->
                json.getInt("maxBatchSize") in 1..50 && advertised.containsAll(REQUIRED_V1_CAPABILITIES)
            MuseAcquisitionContract.V2 -> advertised.containsAll(REQUIRED_V2_CAPABILITIES)
        }
    }.getOrDefault(false)

    private fun readSmallBody(body: okhttp3.ResponseBody?): String? {
        body ?: return null
        if (body.contentLength() > MAX_RESPONSE_BYTES) return null
        return runCatching {
            ByteArrayOutputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_RESPONSE_BYTES) error("response too large")
                        output.write(buffer, 0, count)
                    }
                }
                output.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }

    private data class Endpoint(val baseUrl: String, val token: String, val consumerId: String)

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_QUERY_LENGTH = 500
        private const val DEFAULT_UPLOAD_CHUNK_BYTES = 4L * 1024L * 1024L
        private const val MIN_UPLOAD_CHUNK_BYTES = 256L * 1024L
        private const val MAX_UPLOAD_CHUNK_BYTES = 16L * 1024L * 1024L
        private const val MAX_UPLOAD_HEADERS = 24
        private const val MAX_HEADER_VALUE_LENGTH = 4_096
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OFFSET_UPLOAD_MEDIA_TYPE = "application/offset+octet-stream".toMediaType()
        private val AUTH_STATUS_CODES = setOf(401, 403)
        private val PERMANENT_STATUS_CODES = setOf(400, 404, 405, 409, 410, 413, 415, 422)
        private val V2_ABSENT_STATUS_CODES = setOf(404, 405, 410)
        private val REQUIRED_V1_CAPABILITIES = setOf("list", "claim", "lease", "report")
        private val REQUIRED_V2_CAPABILITIES = setOf(
            "assignment_claim",
            "phase",
            "heartbeat",
            "upload_ticket",
            "ingest_receipt",
        )
        private val ERROR_CODE_PATTERN = Regex("[a-z0-9_]{1,64}")
        private val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        private val HEADER_NAME_PATTERN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")
        private val ALLOWED_UPLOAD_METHODS = setOf("PUT", "POST", "PATCH")
        private val FORBIDDEN_JOB_FIELDS = setOf(
            "spotifyUri",
            "spotifyAccountId",
            "discordUserId",
            "guildId",
            "playlistId",
            "oauthToken",
        )
        private val ALLOWED_JOB_STATUSES = setOf(
            "approved",
            "offered",
            "claimed",
            "leased",
            "resolving",
            "downloading",
            "verifying",
            "uploading",
            "validating",
            "indexing",
            "available",
            "completed",
            "failed",
        )
    }
}

private sealed interface ResumableInitialization {
    data class AlreadyComplete(val receipt: MuseIngestReceipt) : ResumableInitialization

    data class Session(
        val sessionUrl: String,
        val offset: Long,
        val length: Long,
    ) : ResumableInitialization
}

private class ProgressFileRequestBody(
    private val file: File,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        file.source().use { source ->
            var written = 0L
            while (true) {
                val count = source.read(sink.buffer, DEFAULT_BUFFER_SIZE.toLong())
                if (count < 0L) break
                written += count
                sink.emitCompleteSegments()
                onProgress(written, file.length())
            }
        }
    }
}
