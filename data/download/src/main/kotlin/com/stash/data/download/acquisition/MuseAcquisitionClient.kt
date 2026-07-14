package com.stash.data.download.acquisition

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class MuseAcquisitionJob(
    val id: String,
    val query: String,
    val attempt: Int,
    val approvedAt: String,
    val expiresAt: String,
)

data class MuseAcquisitionClaim(
    val job: MuseAcquisitionJob,
    val leaseToken: String,
    val leaseExpiresAt: String,
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

    suspend fun checkConnection(): MuseAcquisitionConnectionCheck = withContext(Dispatchers.IO) {
        val endpoint = endpoint(requireEnabled = false)
            ?: return@withContext MuseAcquisitionConnectionCheck.NotConfigured
        try {
            val request = request(endpoint, "/v1/capabilities").get().build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code in AUTH_STATUS_CODES -> MuseAcquisitionConnectionCheck.AuthenticationFailed
                    response.isSuccessful && validCapabilities(readSmallBody(response.body)) -> {
                        MuseAcquisitionConnectionCheck.Verified
                    }
                    response.isSuccessful || response.code in PERMANENT_STATUS_CODES -> {
                        MuseAcquisitionConnectionCheck.Incompatible
                    }
                    else -> MuseAcquisitionConnectionCheck.Unreachable
                }
            }
        } catch (_: Exception) {
            MuseAcquisitionConnectionCheck.Unreachable
        }
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

    private fun parseJob(json: JSONObject): MuseAcquisitionJob {
        requireContract(json)
        require(json.getString("origin") == "manual_discord_search")
        require(json.getString("status") in ALLOWED_JOB_STATUSES)
        val id = json.getString("id")
        require(id.matches(UUID_PATTERN))
        val query = json.getString("query").trim()
        require(query.isNotEmpty() && query.length <= MAX_QUERY_LENGTH)
        return MuseAcquisitionJob(
            id = id,
            query = query,
            attempt = json.getInt("attempt").coerceAtLeast(0),
            approvedAt = json.getString("approvedAt"),
            expiresAt = json.getString("expiresAt"),
        )
    }

    private fun requireContract(json: JSONObject) {
        require(json.getString("contract") == CONTRACT)
    }

    private fun validCapabilities(body: String?): Boolean = runCatching {
        val json = JSONObject(body.orEmpty())
        requireContract(json)
        val capabilities = json.getJSONArray("capabilities")
        val advertised = buildSet(capabilities.length()) {
            for (index in 0 until capabilities.length()) add(capabilities.getString(index))
        }
        json.getInt("maxBatchSize") in 1..50 && advertised.containsAll(REQUIRED_CAPABILITIES)
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
        private const val CONTRACT = "muse-acquisition/v1"
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_QUERY_LENGTH = 500
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val AUTH_STATUS_CODES = setOf(401, 403)
        private val PERMANENT_STATUS_CODES = setOf(400, 404, 405, 409, 410, 413, 415, 422)
        private val REQUIRED_CAPABILITIES = setOf("list", "claim", "lease", "report")
        private val ERROR_CODE_PATTERN = Regex("[a-z0-9_]{1,64}")
        private val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        private val ALLOWED_JOB_STATUSES = setOf("approved", "claimed", "downloading", "completed", "failed")
    }
}
