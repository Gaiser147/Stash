package com.stash.data.download.export

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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
    val tracksUploaded: Int,
    val tracksSkipped: Int,
    val trackFailures: Int,
    val coversUploaded: Int,
    val coversSkipped: Int,
    val coverFailures: Int,
    val playlistsUploaded: Int,
    val playlistFailures: Int,
)

@Singleton
class NavidromeIngestClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: NavidromeExportPreferences,
    private val httpClient: OkHttpClient,
) {
    suspend fun uploadFile(
        filePath: String,
        relativePath: String,
        metadata: NavidromeTrackMetadata? = null,
    ): NavidromeUploadOutcome = withContext(Dispatchers.IO) {
        val config = prefs.current()
        if (!config.configured) return@withContext NavidromeUploadOutcome.Success

        val uploadFile = materialize(filePath).getOrElse { e ->
            Log.w(TAG, "Could not open upload source: $filePath", e)
            return@withContext NavidromeUploadOutcome.RetryableFailure
        }
        val deleteWhenDone = uploadFile.absolutePath != filePath && filePath.startsWith("content://")

        try {
            if (!uploadFile.exists() || uploadFile.length() <= 0) {
                return@withContext NavidromeUploadOutcome.RetryableFailure
            }
            val request = Request.Builder()
                .url("${config.serverUrl.trimEnd('/')}/v1/files/${encodePath(relativePath)}")
                .put(FileRequestBody(uploadFile))
                .header("Authorization", "Bearer ${config.token}")
                .header("X-Stash-Sha256", sha256(uploadFile))
                .header("X-Stash-Size", uploadFile.length().toString())
                .apply {
                    metadataHeader(metadata)?.let { header("X-Stash-Metadata", it) }
                }
                .build()

            execute(request, "file $relativePath")
        } catch (e: Exception) {
            Log.w(TAG, "Navidrome file upload failed", e)
            NavidromeUploadOutcome.RetryableFailure
        } finally {
            if (deleteWhenDone) runCatching { uploadFile.delete() }
        }
    }

    suspend fun uploadCover(artPathOrUrl: String?, relativePath: String): NavidromeUploadOutcome = withContext(Dispatchers.IO) {
        val config = prefs.current()
        if (!config.configured) return@withContext NavidromeUploadOutcome.Success
        if (artPathOrUrl.isNullOrBlank()) return@withContext NavidromeUploadOutcome.SkippedNoSource

        val uploadFile = materializeArtwork(artPathOrUrl).getOrElse { e ->
            Log.w(TAG, "Could not open cover source: $artPathOrUrl", e)
            return@withContext NavidromeUploadOutcome.RetryableFailure
        }
        val deleteWhenDone = uploadFile.parentFile == context.cacheDir && uploadFile.name.startsWith("navidrome_art_")

        try {
            if (!uploadFile.exists() || uploadFile.length() <= 0) {
                return@withContext NavidromeUploadOutcome.RetryableFailure
            }
            val request = Request.Builder()
                .url("${config.serverUrl.trimEnd('/')}/v1/covers/${encodePath(relativePath)}")
                .put(FileRequestBody(uploadFile))
                .header("Authorization", "Bearer ${config.token}")
                .header("X-Stash-Sha256", sha256(uploadFile))
                .header("X-Stash-Size", uploadFile.length().toString())
                .build()

            execute(request, "cover $relativePath")
        } catch (e: Exception) {
            Log.w(TAG, "Navidrome cover upload failed", e)
            NavidromeUploadOutcome.RetryableFailure
        } finally {
            if (deleteWhenDone) runCatching { uploadFile.delete() }
        }
    }

    suspend fun uploadPlaylist(fileName: String, body: ByteArray): NavidromeUploadOutcome = withContext(Dispatchers.IO) {
        val config = prefs.current()
        if (!config.configured) return@withContext NavidromeUploadOutcome.Success

        try {
            val request = Request.Builder()
                .url("${config.serverUrl.trimEnd('/')}/v1/playlists/${Uri.encode(fileName)}")
                .put(body.toRequestBody("audio/x-mpegurl; charset=utf-8".toMediaType()))
                .header("Authorization", "Bearer ${config.token}")
                .build()

            execute(request, "playlist $fileName")
        } catch (e: Exception) {
            Log.w(TAG, "Navidrome playlist upload failed", e)
            NavidromeUploadOutcome.RetryableFailure
        }
    }

    suspend fun syncComplete(summary: NavidromeSyncSummary): NavidromeUploadOutcome = withContext(Dispatchers.IO) {
        val config = prefs.current()
        if (!config.configured) return@withContext NavidromeUploadOutcome.Success

        try {
            val body = JSONObject()
                .put("tracksUploaded", summary.tracksUploaded)
                .put("tracksSkipped", summary.tracksSkipped)
                .put("trackFailures", summary.trackFailures)
                .put("coversUploaded", summary.coversUploaded)
                .put("coversSkipped", summary.coversSkipped)
                .put("coverFailures", summary.coverFailures)
                .put("playlistsUploaded", summary.playlistsUploaded)
                .put("playlistFailures", summary.playlistFailures)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${config.serverUrl.trimEnd('/')}/v1/sync-complete")
                .post(body)
                .header("Authorization", "Bearer ${config.token}")
                .build()

            execute(request, "sync summary")
        } catch (e: Exception) {
            Log.w(TAG, "Navidrome sync summary failed", e)
            NavidromeUploadOutcome.RetryableFailure
        }
    }

    private fun execute(request: Request, label: String): NavidromeUploadOutcome {
        httpClient.newCall(request).execute().use { response ->
            return if (response.isSuccessful) {
                Log.i(TAG, "Uploaded Navidrome $label")
                NavidromeUploadOutcome.Success
            } else if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                Log.w(TAG, "Permanent Navidrome upload failure ${response.code}: ${response.body?.string()?.take(300)}")
                NavidromeUploadOutcome.PermanentFailure
            } else {
                Log.w(TAG, "Retryable Navidrome upload failure ${response.code}")
                NavidromeUploadOutcome.RetryableFailure
            }
        }
    }

    private fun materialize(filePath: String): Result<File> = runCatching {
        if (!filePath.startsWith("content://")) return@runCatching File(filePath)
        val uri = Uri.parse(filePath)
        val ext = uri.lastPathSegment?.substringAfterLast('.', "tmp") ?: "tmp"
        val temp = File(context.cacheDir, "navidrome_upload_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open $uri")
        temp
    }

    private fun materializeArtwork(source: String): Result<File> = runCatching {
        if (!source.startsWith("http://") && !source.startsWith("https://")) {
            return@runCatching materialize(source).getOrThrow()
        }
        val request = Request.Builder().url(source).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("cover fetch failed: ${response.code}")
            val body = response.body ?: error("cover response had no body")
            val ext = when (body.contentType()?.subtype?.lowercase()) {
                "jpeg", "jpg" -> "jpg"
                "png" -> "png"
                "webp" -> "webp"
                else -> extensionOf(source, fallback = "jpg")
            }
            val temp = File(context.cacheDir, "navidrome_art_${UUID.randomUUID()}.$ext")
            body.contentLength().takeIf { it > MAX_COVER_BYTES }?.let {
                error("cover is too large: $it bytes")
            }
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_COVER_BYTES) error("cover exceeded $MAX_COVER_BYTES bytes")
                        output.write(buffer, 0, read)
                    }
                }
            }
            temp
        }
    }

    private fun metadataHeader(metadata: NavidromeTrackMetadata?): String? {
        metadata ?: return null
        val json = JSONObject()
            .put("title", metadata.title)
            .put("artist", metadata.artist)
        metadata.album?.takeIf { it.isNotBlank() }?.let { json.put("album", it) }
        metadata.albumArtist?.takeIf { it.isNotBlank() }?.let { json.put("album_artist", it) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun extensionOf(pathOrUrl: String, fallback: String): String {
        val name = Uri.parse(pathOrUrl).lastPathSegment.orEmpty()
        val ext = name.substringAfterLast('.', fallback).lowercase()
        return ext.takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: fallback
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { Uri.encode(it) }

    private class FileRequestBody(private val file: File) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            file.source().use { source -> sink.writeAll(source) }
        }
    }

    companion object {
        private const val TAG = "NavidromeIngestClient"
        private const val MAX_COVER_BYTES = 10L * 1024L * 1024L
    }
}
