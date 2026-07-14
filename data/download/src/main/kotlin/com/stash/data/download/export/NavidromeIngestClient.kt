package com.stash.data.download.export

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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
            val request = Request.Builder()
                .url("${endpoint.baseUrl}/v1/files/${encodePath(relativePath)}")
                .put(FileRequestBody(uploadFile))
                .authenticated(endpoint.token)
                .header("X-Stash-Sha256", sha256(uploadFile))
                .header("X-Stash-Size", uploadFile.length().toString())
                .apply { metadataHeader(metadata)?.let { header("X-Stash-Metadata", it) } }
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
            val uploadFile = materializeArtwork(artPathOrUrl).getOrElse {
                Log.w(TAG, "Could not materialize Navidrome cover source")
                return@withContext NavidromeUploadOutcome.RetryableFailure
            }
            val deleteWhenDone = uploadFile.parentFile == context.cacheDir &&
                uploadFile.name.startsWith("navidrome_art_")

            try {
                if (!uploadFile.exists() || uploadFile.length() <= 0) {
                    return@withContext NavidromeUploadOutcome.RetryableFailure
                }
                val request = Request.Builder()
                    .url("${endpoint.baseUrl}/v1/covers/${encodePath(relativePath)}")
                    .put(FileRequestBody(uploadFile))
                    .authenticated(endpoint.token)
                    .header("X-Stash-Sha256", sha256(uploadFile))
                    .header("X-Stash-Size", uploadFile.length().toString())
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

    private suspend fun endpointAndToken(): Endpoint? {
        val config = prefs.current()
        if (!config.configured) return null
        val baseUrl = NavidromeEndpoint.normalize(config.serverUrl) ?: return null
        return Endpoint(baseUrl, config.token)
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
        private val PERMANENT_STATUS_CODES = (400..499).filterNot { it == 408 || it == 429 }.toSet()
        private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val M3U_MEDIA_TYPE = "audio/x-mpegurl; charset=utf-8".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
