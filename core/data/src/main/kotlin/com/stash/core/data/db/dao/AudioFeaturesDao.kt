package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.stash.core.data.db.entity.AudioFeaturesEntity

@Dao
interface AudioFeaturesDao {

    @Upsert
    suspend fun upsertAll(rows: List<AudioFeaturesEntity>)

    @Query("SELECT * FROM audio_features WHERE track_id IN (:trackIds)")
    suspend fun getByTrackIds(trackIds: Collection<Long>): List<AudioFeaturesEntity>

    @Query("SELECT path FROM audio_features WHERE track_id IS NULL")
    suspend fun unresolvedPaths(): List<String>

    @Query("UPDATE audio_features SET track_id = :trackId WHERE path = :path")
    suspend fun resolve(path: String, trackId: Long)

    /** Unlinks rows whose track is gone (deleted, or re-downloaded under a new path). */
    @Query("UPDATE audio_features SET track_id = NULL WHERE track_id IS NOT NULL AND track_id NOT IN (SELECT id FROM tracks)")
    suspend fun unlinkMissingTracks(): Int

    @Query("SELECT COUNT(*) FROM audio_features WHERE track_id IS NOT NULL")
    suspend fun resolvedCount(): Int

    /**
     * v35: the sound of every analysed play since [sinceMs] that was heard
     * (mostly) to the end, with when it started. Feeds the time-of-day energy
     * profile and the "your sound" statistics.
     */
    @Query(
        """
        SELECT le.started_at AS startedAt, af.bpm AS bpm, af.beat_confidence AS beatConfidence,
               af.loudness_db AS loudnessDb, af.brightness_hz AS brightnessHz,
               af.onset_rate AS onsetRate, af.minor AS minor, af.key_strength AS keyStrength
        FROM listening_events le
        JOIN audio_features af ON af.track_id = le.track_id
        WHERE le.started_at >= :sinceMs
          AND (le.listened_ms IS NULL OR le.duration_ms IS NULL OR le.duration_ms <= 0
               OR le.listened_ms * 2 >= le.duration_ms)
        """
    )
    suspend fun heardSoundSince(sinceMs: Long): List<HeardSound>
}

/** One analysed play: when it started and what it sounded like. */
data class HeardSound(
    val startedAt: Long,
    val bpm: Float,
    val beatConfidence: Float,
    val loudnessDb: Float,
    val brightnessHz: Float,
    val onsetRate: Float,
    val minor: Boolean,
    val keyStrength: Float,
)
