package com.stash.data.download.export

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
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

sealed interface NavidromeUploadOutcome {
    data object Success : NavidromeUploadOutcome
    data object PermanentFailure : NavidromeUploadOutcome
    data object RetryableFailure : NavidromeUploadOutcome
}

@Singleton
class NavidromeIngestClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: NavidromeExportPreferences,
    private val httpClient: OkHttpClient,
) {
    suspend fun uploadFile(filePath: String, relativePath: String): NavidromeUploadOutcome = withContext(Dispatchers.IO) {
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
                .build()

            execute(request, "file $relativePath")
        } catch (e: Exception) {
            Log.w(TAG, "Navidrome file upload failed", e)
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
    }
}
