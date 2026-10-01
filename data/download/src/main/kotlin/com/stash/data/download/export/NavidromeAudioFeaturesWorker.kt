package com.stash.data.download.export

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.AudioFeaturesDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.AudioFeaturesEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Pulls the audio features the user's stash-ingest server measured (tempo,
 * loudness, brightness, key) into `audio_features`, then links them to local
 * tracks by recomputing each downloaded track's upload path. Incremental:
 * pages from the saved cursor, so a run after the first only fetches what the
 * server analysed since. Unmatched rows are kept; a later download links them
 * on the next run.
 */
@HiltWorker
class NavidromeAudioFeaturesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val prefs: NavidromeExportPreferences,
    private val ingestClient: NavidromeIngestClient,
    private val audioFeaturesDao: AudioFeaturesDao,
    private val trackDao: TrackDao,
    private val scheduler: NavidromeUploadScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val config = prefs.current()
        if (!config.configured) return Result.success()
        var after = prefs.audioFeaturesCursor(config.serverUrl)
        var fetched = 0
        while (fetched < MAX_ROWS_PER_RUN) {
            val page = ingestClient.fetchAudioFeatures(after) ?: break
            if (page.items.isEmpty()) break
            audioFeaturesDao.upsertAll(page.items.map { it.toEntity() })
            fetched += page.items.size
            after = page.next
            prefs.saveAudioFeaturesCursor(config.serverUrl, after)
        }
        val linked = link()
        Log.i(TAG, "audio features: fetched=$fetched linked=$linked total=${audioFeaturesDao.resolvedCount()}")
        return Result.success()
    }

    /** Links unmatched feature rows to downloaded tracks by upload path. */
    internal suspend fun link(): Int {
        audioFeaturesDao.unlinkMissingTracks()
        val open = audioFeaturesDao.unresolvedPaths().toHashSet()
        if (open.isEmpty()) return 0
        var linked = 0
        for (track in trackDao.getAllDownloaded()) {
            val filePath = track.filePath ?: continue
            val path = scheduler.relativePathForTrack(
                track.artist,
                track.album.takeIf(String::isNotBlank),
                track.title,
                scheduler.extensionOfPath(filePath),
            )
            if (open.remove(path)) {
                audioFeaturesDao.resolve(path, track.id)
                linked++
            }
        }
        return linked
    }

    private fun RemoteAudioFeatures.toEntity() = AudioFeaturesEntity(
        path = path,
        trackId = null,
        bpm = bpm,
        beatConfidence = beatConfidence,
        beatRegularity = beatRegularity,
        loudnessDb = loudnessDb,
        dynamicsDb = dynamicsDb,
        brightnessHz = brightnessHz,
        onsetRate = onsetRate,
        pitchClass = pitchClass,
        minor = minor,
        keyStrength = keyStrength,
        analyzerVersion = version,
        seq = seq,
    )

    companion object {
        private const val TAG = "NavidromeAudioFeatures"
        private const val UNIQUE_WORK_NAME = "navidrome-audio-features"
        private const val MAX_ROWS_PER_RUN = 20_000

        private const val STARTUP_WORK_NAME = "navidrome-audio-features-startup"

        /**
         * Every 6 hours on any network, plus one run as soon as there is a
         * network after app start: Android may hold a periodic job back for
         * hours, and the fetch is incremental, so the extra run is cheap.
         * Idempotent.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NavidromeAudioFeaturesWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
            workManager.enqueueUniqueWork(
                STARTUP_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<NavidromeAudioFeaturesWorker>()
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
