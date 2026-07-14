package com.stash.data.download.export

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.data.sync.NavidromeExportScheduler
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
        youtubeId: String?,
    ) {
        val config = prefs.current()
        if (!config.configured) return
        val relativePath = relativePathForTrack(artist, album, title, extensionOfPath(filePath))
        val work = OneTimeWorkRequestBuilder<NavidromeUploadWorker>()
            .setConstraints(uploadConstraints(config))
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
                    .putString(NavidromeUploadWorker.KEY_YOUTUBE_ID, youtubeId)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "navidrome-track-${stableId(filePath, relativePath)}",
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    override suspend fun enqueuePlaylistExport() {
        enqueueReconciliation(full = false, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    override suspend fun enqueueFullExport() {
        enqueueReconciliation(full = true, policy = ExistingWorkPolicy.KEEP)
    }

    fun navidromeLibraryEntry(artist: String, album: String?, title: String, ext: String): String =
        "Stash/${relativePathForTrack(artist, album, title, ext)}"

    fun relativePathForTrack(artist: String, album: String?, title: String, ext: String): String =
        listOf(
            legacySegment(artist, "unknown-artist"),
            legacySegment(album?.takeIf(String::isNotBlank) ?: "singles", "singles"),
            "${legacySegment(title, "untitled")}.$ext",
        ).joinToString("/")

    fun albumCoverRelativePath(artist: String, album: String?, artPathOrUrl: String?): String =
        listOf(
            legacySegment(artist, "unknown-artist"),
            legacySegment(album?.takeIf(String::isNotBlank) ?: "singles", "singles"),
            "cover.${coverExtensionOf(artPathOrUrl)}",
        ).joinToString("/")

    fun extensionOfPath(path: String): String {
        val source = if (path.startsWith("content://")) {
            Uri.parse(path).lastPathSegment.orEmpty()
        } else {
            File(path).name
        }
        return source.substringAfterLast('.', "flac")
            .lowercase(Locale.US)
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "flac"
    }

    private suspend fun enqueueReconciliation(full: Boolean, policy: ExistingWorkPolicy) {
        val config = prefs.current()
        if (!config.configured) return
        val work = OneTimeWorkRequestBuilder<NavidromePlaylistExportWorker>()
            .setConstraints(uploadConstraints(config))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putBoolean(NavidromePlaylistExportWorker.KEY_FULL_EXPORT, full).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (full) "navidrome-full-export" else "navidrome-playlist-export",
            policy,
            work,
        )
    }

    private fun uploadConstraints(config: NavidromeExportConfig): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(config.chargingOnly)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun coverExtensionOf(pathOrUrl: String?): String {
        val source = pathOrUrl?.let {
            if (it.startsWith("content://")) Uri.parse(it).lastPathSegment.orEmpty() else it
        }.orEmpty()
        val ext = source.substringBefore('?').substringAfterLast('.', "jpg").lowercase(Locale.US)
        return ext.takeIf { it in COVER_EXTENSIONS } ?: "jpg"
    }

    /**
     * Contract-v1 path encoding. Keep this byte-for-byte compatible with the
     * installed fork so a full reconciliation deduplicates against the
     * existing Navidrome library instead of creating a second path tree.
     * Collision-proof identity paths require a coordinated ingest migration.
     */
    private fun legacySegment(value: String, fallback: String): String =
        value.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(80)
            .ifBlank { fallback }

    private suspend fun stableId(filePath: String, relativePath: String): String =
        withContext(Dispatchers.Default) { shortHash("$filePath\n$relativePath", 24) }

    private fun shortHash(value: String, length: Int = 8): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(length)

    private companion object {
        val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
