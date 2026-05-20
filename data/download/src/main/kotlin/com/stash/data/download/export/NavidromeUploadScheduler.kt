package com.stash.data.download.export

import android.content.Context
import android.net.Uri
import com.stash.core.data.sync.NavidromeExportScheduler
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class NavidromeUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: NavidromeExportPreferences,
) : NavidromeExportScheduler {
    override suspend fun enqueueTrack(
        filePath: String,
        artist: String,
        album: String?,
        title: String,
        albumArtist: String?,
        albumArtUrl: String?,
        albumArtPath: String?,
    ) {
        if (!prefs.current().configured) return
        val relativePath = buildRelativePath(artist, album, title, extensionOf(filePath))
        val work = OneTimeWorkRequestBuilder<NavidromeUploadWorker>()
            .setConstraints(uploadConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(NavidromeUploadWorker.KEY_FILE_PATH, filePath)
                    .putString(NavidromeUploadWorker.KEY_RELATIVE_PATH, relativePath)
                    .putString(NavidromeUploadWorker.KEY_TITLE, title)
                    .putString(NavidromeUploadWorker.KEY_ARTIST, artist)
                    .putString(NavidromeUploadWorker.KEY_ALBUM, album)
                    .putString(NavidromeUploadWorker.KEY_ALBUM_ARTIST, albumArtist)
                    .putString(NavidromeUploadWorker.KEY_ALBUM_ART_URL, albumArtUrl)
                    .putString(NavidromeUploadWorker.KEY_ALBUM_ART_PATH, albumArtPath)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "navidrome-upload-${stableId(filePath, relativePath)}",
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    override suspend fun enqueuePlaylistExport() {
        if (!prefs.current().configured) return
        val work = OneTimeWorkRequestBuilder<NavidromePlaylistExportWorker>()
            .setConstraints(uploadConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "navidrome-playlist-export",
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    fun navidromeLibraryEntry(artist: String, album: String?, title: String, ext: String): String =
        "Stash/${buildRelativePath(artist, album, title, ext)}"

    fun relativePathForTrack(artist: String, album: String?, title: String, ext: String): String =
        buildRelativePath(artist, album, title, ext)

    fun albumCoverRelativePath(artist: String, album: String?, artPathOrUrl: String?): String {
        val ext = coverExtensionOf(artPathOrUrl)
        return listOf(
            slugify(artist).ifBlank { "unknown-artist" },
            slugify(album?.takeIf { it.isNotBlank() } ?: "singles").ifBlank { "singles" },
            "cover.$ext",
        ).joinToString("/")
    }

    fun extensionOfPath(path: String): String = extensionOf(path)

    private fun uploadConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun extensionOf(path: String): String {
        val source = if (path.startsWith("content://")) {
            Uri.parse(path).lastPathSegment.orEmpty()
        } else {
            File(path).name
        }
        return source.substringAfterLast('.', "flac").lowercase(Locale.US).ifBlank { "flac" }
    }

    private fun coverExtensionOf(pathOrUrl: String?): String {
        val source = pathOrUrl?.let {
            if (it.startsWith("content://")) Uri.parse(it).lastPathSegment.orEmpty() else it
        }.orEmpty()
        val ext = source.substringBefore('?').substringAfterLast('.', "jpg").lowercase(Locale.US)
        return ext.takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
    }

    private fun buildRelativePath(artist: String, album: String?, title: String, ext: String): String =
        listOf(
            slugify(artist).ifBlank { "unknown-artist" },
            slugify(album?.takeIf { it.isNotBlank() } ?: "singles").ifBlank { "singles" },
            "${slugify(title).ifBlank { "untitled" }}.$ext",
        ).joinToString("/")

    private fun slugify(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(80)

    private suspend fun stableId(filePath: String, relativePath: String): String = withContext(Dispatchers.Default) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$filePath\n$relativePath".toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}
