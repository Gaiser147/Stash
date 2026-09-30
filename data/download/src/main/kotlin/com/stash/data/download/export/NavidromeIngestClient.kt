package com.stash.data.download.export

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject

sealed interface NavidromeUploadOutcome {
    data object Success : NavidromeUploadOutcome
    data object SkippedNoSource : NavidromeUploadOutcome
    data object PermanentFailure : NavidromeUploadOutcome
    data object RetryableFailure : NavidromeUploadOutcome
}

sealed interface NavidromeConnectionCheck {
    data object Verified : NavidromeConnectionCheck
    data object LegacyReachable : NavidromeConnectionCheck
    data object AuthenticationFailed : NavidromeConnectionCheck
    data object Incompatible : NavidromeConnectionCheck
    data object Unreachable : NavidromeConnectionCheck
    data object NotConfigured : NavidromeConnectionCheck
}

data class NavidromeTrackMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
)

data class NavidromeSyncSummary(
    val mode: String,
    val tracksUploaded: Int,
    val tracksSkipped: Int,
    val trackFailures: Int,
    val coversUploaded: Int,
    val coversSkipped: Int,
    val coverFailures: Int,
    val playlistsUploaded: Int,
    val playlistFailures: Int,
)

/** One analysed file from `GET /v1/features`; [path] is relative to the Stash prefix. */
data class RemoteAudioFeatures(
    val path: String,
    val seq: Long,
    val version: Int,
    val bpm: Float,
    val beatConfidence: Float,
    val beatRegularity: Float,
    val loudnessDb: Float,
    val dynamicsDb: Float,
    val brightnessHz: Float,
    val onsetRate: Float,
    val pitchClass: Int,
    val minor: Boolean,
    val keyStrength: Float,
)

/** A page of [RemoteAudioFeatures]; [next] is the cursor for the following page. */
data class AudioFeaturesPage(val items: List<RemoteAudioFeatures>, val next: Long)

/**
 * Authenticated client for the versioned stash-ingest contract.
 *
 * Requests contain a content hash and stable relative path so the server can
 * deduplicate retries. Tokens and response bodies are never written to logs.
 */
@Singleton
class NavidromeIngestClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: NavidromeExportPreferences,
    private val httpClient: OkHttpClient,
) {
    /** baseUrl → whether that server offers the upload pre-check. */
    private val precheckSupport = ConcurrentHashMap<String, Boolean>()

    private val uploadHttpClient = httpClient.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()

    suspend fun uploadFile(
        filePath: String,
        relativePath: String,
        metadata: NavidromeTrackMetadata? = null,
    ): NavidromeUploadOutcome = withContext(Dispatchers.IO) {
        val endpoint = endpointAndToken() ?: return@withContext NavidromeUploadOutcome.Success
        val uploadFile = materialize(filePath).getOrElse {
            Log.w(TAG, "Could not materialize Navidrome audio source")
            return@withContext NavidromeUploadOutcome.RetryableFailure
        }
        val deleteWhenDone = uploadFile.absolutePath != filePath && filePath.startsWith("content://")

        try {
            if (!uploadFile.exists() || uploadFile.length() <= 0) {
                return@withContext NavidromeUploadOutcome.RetryableFailure
            }
            val sha = sha256(uploadFile)
            val size = uploadFile.length()
            val metadataValue = metadataHeader(metadata)
            if (alreadyOnServer(endpoint, "files", relativePath, sha, size, metadataValue)) {
                return@withContext NavidromeUploadOutcome.Success
            }
            val request = Request.Builder()
                .url("${endpoint.baseUrl}/v1/files/${encodePath(relativePath)}")
                .put(FileRequestBody(uploadFile))
                .authenticated(endpoint.token)
                .header("X-Stash-Sha256", sha)
                .header("X-Stash-Size", size.toString())
                .apply { metadataValue?.let { header("X-Stash-Metadata", it) } }
                .build()
            execute(request, "audio")
        } catch (_: Exception) {
            Log.w(TAG, "Navidrome audio upload failed code=network_error")
            NavidromeUploadOutcome.RetryableFailure
        } finally {
            if (deleteWhenDone) runCatching { uploadFile.delete() }
        }
    }

    suspend fun uploadCover(artPathOrUrl: String?, relativePath: String): NavidromeUploadOutcome =
        withContext(Dispatchers.IO) {
            val endpoint = endpointAndToken() ?: return@withContext NavidromeUploadOutcome.Success
            if (artPathOrUrl.isNullOrBlank()) return@withContext NavidromeUploadOutcome.SkippedNoSource
            // An unreadable or dead cover link (expired CDN URL, 404) won't heal
            // by retrying, and the audio is what matters: skip, don't retry.
            val uploadFile = materializeArtwork(artPathOrUrl).getOrElse {
                Log.w(TAG, "Could not materialize Navidrome cover source")
                return@withContext NavidromeUploadOutcome.SkippedNoSource
            }
            val deleteWhenDone = uploadFile.parentFile == context.cacheDir &&
                uploadFile.name.startsWith("navidrome_art_")

            try {
                if (!uploadFile.exists() || uploadFile.length() <= 0) {
                    return@withContext NavidromeUploadOutcome.SkippedNoSource
                }
                val sha = sha256(uploadFile)
                val size = uploadFile.length()
                if (alreadyOnServer(endpoint, "covers", relativePath, sha, size, metadata = null)) {
                    return@withContext NavidromeUploadOutcome.Success
                }
                val request = Request.Builder()
                    .url("${endpoint.baseUrl}/v1/covers/${encodePath(relativePath)}")
                    .put(FileRequestBody(uploadFile))
                    .authenticated(endpoint.token)
                    .header("X-Stash-Sha256", sha)
                    .header("X-Stash-Size", size.toString())
                    .build()
                execute(request, "cover")
            } catch (_: Exception) {
                Log.w(TAG, "Navidrome cover upload failed code=network_error")
                NavidromeUploadOutcome.RetryableFailure
            } finally {
                if (deleteWhenDone) runCatching { uploadFile.delete() }
            }
        }

    suspend fun uploadPlaylist(fileName: String, body: ByteArray): NavidromeUploadOutcome =
        withContext(Dispatchers.IO) {
            val endpoint = endpointAndToken() ?: return@withContext NavidromeUploadOutcome.Success
            try {
                val request = Request.Builder()
                    .url("${endpoint.baseUrl}/v1/playlists/${Uri.encode(fileName)}")
                    .put(body.toRequestBody(M3U_MEDIA_TYPE))
                    .authenticated(endpoint.token)
                    .header("X-Stash-Sha256", sha256(body))
                    .header("X-Stash-Size", body.size.toString())
                    .build()
                execute(request, "playlist")
            } catch (_: Exception) {
                Log.w(TAG, "Navidrome playlist upload failed code=network_error")
                NavidromeUploadOutcome.RetryableFailure
            }
        }

    suspend fun syncComplete(summary: NavidromeSyncSummary): NavidromeUploadOutcome =
        withContext(Dispatchers.IO) {
            val endpoint = endpointAndToken() ?: return@withContext NavidromeUploadOutcome.Success
            try {
                val body = JSONObject()
                    .put("mode", summary.mode)
                    .put("tracksUploaded", summary.tracksUploaded)
                    .put("tracksSkipped", summary.tracksSkipped)
                    .put("trackFailures", summary.trackFailures)
                    .put("coversUploaded", summary.coversUploaded)
                    .put("coversSkipped", summary.coversSkipped)
                    .put("coverFailures", summary.coverFailures)
                    .put("playlistsUploaded", summary.playlistsUploaded)
                    .put("playlistFailures", summary.playlistFailures)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url("${endpoint.baseUrl}/v1/sync-complete")
                    .post(body)
                    .authenticated(endpoint.token)
                    .build()
                execute(request, "summary")
            } catch (_: Exception) {
                Log.w(TAG, "Navidrome summary upload failed code=network_error")
                NavidromeUploadOutcome.RetryableFailure
            }
        }

    /**
     * The next page of server-side audio features after [after], or null when
     * the server is unreachable, not configured, or doesn't analyse audio
     * (older stash-ingest). Rows the app can't parse are skipped.
     */
    suspend fun fetchAudioFeatures(after: Long, limit: Int = FEATURES_PAGE): AudioFeaturesPage? =
        withContext(Dispatchers.IO) {
            val endpoint = endpointAndToken() ?: return@withContext null
            runCatching {
                val request = Request.Builder()
                    .url("${endpoint.baseUrl}/v1/features?after=$after&limit=$limit")
                    .get()
                    .authenticated(endpoint.token)
                    .build()
                uploadHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    val array = json.optJSONArray("features") ?: return@use null
                    val items = (0 until array.length()).mapNotNull { i ->
                        val o = array.optJSONObject(i) ?: return@mapNotNull null
                        runCatching {
                            RemoteAudioFeatures(
                                path = o.getString("path"),
                                seq = o.getLong("seq"),
                                version = o.optInt("version", 1),
                                bpm = o.getDouble("bpm").toFloat(),
                                beatConfidence = o.optDouble("beat_confidence", 0.0).toFloat(),
                                beatRegularity = o.optDouble("beat_regularity", 0.0).toFloat(),
                                loudnessDb = o.getDouble("loudness_db").toFloat(),
                                dynamicsDb = o.optDouble("dynamics_db", 0.0).toFloat(),
                                brightnessHz = o.optDouble("brightness_hz", 0.0).toFloat(),
                                onsetRate = o.optDouble("onset_rate", 0.0).toFloat(),
                                pitchClass = o.getInt("key"),
                                minor = o.optString("mode") == "minor",
                                keyStrength = o.optDouble("key_strength", 0.0).toFloat(),
                            )
                        }.getOrNull()
                    }
                    AudioFeaturesPage(items, json.optLong("next", after))
                }
            }.getOrNull()
        }

    suspend fun checkConnection(): NavidromeConnectionCheck = withContext(Dispatchers.IO) {
        val endpoint = endpointAndToken(requireEnabled = false)
            ?: return@withContext NavidromeConnectionCheck.NotConfigured
        try {
            val capabilities = Request.Builder()
                .url("${endpoint.baseUrl}/v1/capabilities")
                .get()
                .authenticated(endpoint.token)
                .build()
            uploadHttpClient.newCall(capabilities).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> {
                        return@withContext NavidromeConnectionCheck.AuthenticationFailed
                    }
                    response.code == 404 -> Unit
                    response.isSuccessful && validCapabilities(readSmallResponse(response.body)) -> {
                        return@withContext NavidromeConnectionCheck.Verified
                    }
                    response.isSuccessful || response.code in PERMANENT_STATUS_CODES -> {
                        return@withContext NavidromeConnectionCheck.Incompatible
                    }
                    else -> return@withContext NavidromeConnectionCheck.Unreachable
                }
            }

            val legacyHealth = Request.Builder()
                .url("${endpoint.baseUrl}/v1/health")
                .get()
                .authenticated(endpoint.token)
                .build()
            uploadHttpClient.newCall(legacyHealth).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> {
                        NavidromeConnectionCheck.AuthenticationFailed
                    }
                    response.isSuccessful && validLegacyHealth(readSmallResponse(response.body)) -> {
                        NavidromeConnectionCheck.LegacyReachable
                    }
                    response.code in PERMANENT_STATUS_CODES -> NavidromeConnectionCheck.Incompatible
                    else -> NavidromeConnectionCheck.Unreachable
                }
            }
        } catch (_: Exception) {
            NavidromeConnectionCheck.Unreachable
        }
    }

    /**
     * Asks the server whether it already holds exactly this file (same path,
     * hash and size, or matching tags) before streaming it. A reinstalled app
     * or a full export re-offers the whole library, and without this every
     * file was sent even when the server then answered already-present. Only
     * a definite `200` skips the upload; anything else (older server, error,
     * no network) falls back to the normal PUT, which stays the authority.
     */
    private fun alreadyOnServer(
        endpoint: Endpoint,
        route: String,
        relativePath: String,
        sha256: String,
        size: Long,
        metadata: String?,
    ): Boolean {
        if (!supportsPrecheck(endpoint)) return false
        return runCatching {
            val request = Request.Builder()
                .url("${endpoint.baseUrl}/v1/$route/${encodePath(relativePath)}")
                .head()
                .authenticated(endpoint.token)
                .header("X-Stash-Sha256", sha256)
                .header("X-Stash-Size", size.toString())
                .apply { metadata?.let { header("X-Stash-Metadata", it) } }
                .build()
            uploadHttpClient.newCall(request).execute().use { it.code == 200 }
        }.getOrDefault(false)
    }

    /** Whether [endpoint] advertises `features.uploadPrecheck`; asked once per server and process. */
    private fun supportsPrecheck(endpoint: Endpoint): Boolean {
        precheckSupport[endpoint.baseUrl]?.let { return it }
        val answer: Boolean? = runCatching {
            val request = Request.Builder()
                .url("${endpoint.baseUrl}/v1/capabilities")
                .get()
                .authenticated(endpoint.token)
                .build()
            uploadHttpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> JSONObject(readSmallResponse(response.body).orEmpty())
                        .optJSONObject("features")
                        ?.optBoolean("uploadPrecheck", false) == true
                    // A definite "no such endpoint / not for you" from an older server.
                    response.code in PERMANENT_STATUS_CODES -> false
                    else -> null
                }
            }
        }.getOrNull()
        // Transient failures aren't remembered, so the next file asks again.
        if (answer != null) precheckSupport[endpoint.baseUrl] = answer
        return answer == true
    }

    private suspend fun endpointAndToken(requireEnabled: Boolean = true): Endpoint? {
        val config = prefs.current()
        val baseUrl = NavidromeEndpoint.normalize(config.serverUrl) ?: return null
        if (config.token.isBlank() || (requireEnabled && !config.enabled)) return null
        return Endpoint(baseUrl, config.token)
    }

    private fun validCapabilities(body: String?): Boolean = runCatching {
        val json = JSONObject(body.orEmpty())
        json.optBoolean("ok") && json.optString("contract") == CONTRACT_VERSION
    }.getOrDefault(false)

    private fun validLegacyHealth(body: String?): Boolean = runCatching {
        JSONObject(body.orEmpty()).optBoolean("ok")
    }.getOrDefault(false)

    private fun readSmallResponse(body: okhttp3.ResponseBody?): String? {
        body ?: return null
        if (body.contentLength() > MAX_CONTROL_RESPONSE_BYTES) return null
        return runCatching {
            ByteArrayOutputStream().use { output ->
                body.byteStream().use { input ->
                    copyBounded(input, output, MAX_CONTROL_RESPONSE_BYTES)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }

    private fun execute(request: Request, operation: String): NavidromeUploadOutcome {
        uploadHttpClient.newCall(request).execute().use { response ->
            return when {
                response.isSuccessful -> NavidromeUploadOutcome.Success
                response.code in PERMANENT_STATUS_CODES -> {
                    Log.w(TAG, "Permanent Navidrome $operation failure status=${response.code}")
                    NavidromeUploadOutcome.PermanentFailure
                }
                else -> {
                    Log.w(TAG, "Retryable Navidrome $operation failure status=${response.code}")
                    NavidromeUploadOutcome.RetryableFailure
                }
            }
        }
    }

    private fun materialize(filePath: String): Result<File> = runCatching {
        if (!filePath.startsWith("content://")) return@runCatching File(filePath)
        val uri = Uri.parse(filePath)
        val ext = uri.lastPathSegment?.substringAfterLast('.', "tmp") ?: "tmp"
        val temp = File(context.cacheDir, "navidrome_upload_${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open content URI")
        temp
    }

    private fun materializeArtwork(source: String): Result<File> = runCatching {
        if (!source.startsWith("http://") && !source.startsWith("https://")) {
            return@runCatching materialize(source).getOrThrow()
        }
        val request = Request.Builder().url(source).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Cover fetch failed")
            val body = response.body ?: error("Cover response had no body")
            val contentLength = body.contentLength()
            if (contentLength > MAX_COVER_BYTES) error("Cover exceeds size limit")
            val ext = when (body.contentType()?.subtype?.lowercase()) {
                "jpeg", "jpg" -> "jpg"
                "png" -> "png"
                "webp" -> "webp"
                else -> extensionOf(source, "jpg")
            }
            val temp = File(context.cacheDir, "navidrome_art_${UUID.randomUUID()}.$ext")
            body.byteStream().use { input ->
                temp.outputStream().use { output -> copyBounded(input, output, MAX_COVER_BYTES) }
            }
            temp
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) error("Body exceeds size limit")
            output.write(buffer, 0, read)
        }
    }

    private fun metadataHeader(metadata: NavidromeTrackMetadata?): String? {
        metadata ?: return null
        val json = JSONObject().put("title", metadata.title).put("artist", metadata.artist)
        metadata.album?.takeIf(String::isNotBlank)?.let { json.put("album", it) }
        metadata.albumArtist?.takeIf(String::isNotBlank)?.let { json.put("album_artist", it) }
        return Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun extensionOf(pathOrUrl: String, fallback: String): String {
        val ext = Uri.parse(pathOrUrl).lastPathSegment.orEmpty()
            .substringAfterLast('.', fallback).lowercase()
        return ext.takeIf { it in COVER_EXTENSIONS } ?: fallback
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { Uri.encode(it) }

    private fun Request.Builder.authenticated(token: String): Request.Builder =
        header("Authorization", "Bearer $token")
            .header("X-Stash-Contract", CONTRACT_VERSION)
            .header("X-Stash-Request-Id", UUID.randomUUID().toString())

    private class FileRequestBody(private val file: File) : RequestBody() {
        override fun contentType() = OCTET_STREAM_MEDIA_TYPE
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            file.source().use(sink::writeAll)
        }
    }

    private data class Endpoint(val baseUrl: String, val token: String)

    companion object {
        private const val TAG = "NavidromeIngestClient"
        private const val CONTRACT_VERSION = "1"
        private const val MAX_COVER_BYTES = 10L * 1024L * 1024L
        private const val FEATURES_PAGE = 500
        private const val MAX_CONTROL_RESPONSE_BYTES = 64L * 1024L
        private val PERMANENT_STATUS_CODES = (400..499).filterNot { it == 408 || it == 429 }.toSet()
        private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val M3U_MEDIA_TYPE = "audio/x-mpegurl; charset=utf-8".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
