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
}
