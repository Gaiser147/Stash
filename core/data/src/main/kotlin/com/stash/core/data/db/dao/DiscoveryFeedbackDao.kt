package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.stash.core.data.db.entity.DiscoveryFeedbackEntity

/** Per-artist tally of how discoveries were received. */
data class ArtistDiscoveryStats(
    val artistKey: String,
    val earlySkips: Int,
    val completed: Int,
    val total: Int,
    val lastAt: Long,
)

@Dao
interface DiscoveryFeedbackDao {

    @Insert
    suspend fun insert(row: DiscoveryFeedbackEntity): Long

    @Query(
        """
        SELECT artist_key AS artistKey,
               SUM(CASE WHEN outcome = 'EARLY_SKIP' THEN 1 ELSE 0 END) AS earlySkips,
               SUM(CASE WHEN outcome = 'COMPLETED' THEN 1 ELSE 0 END) AS completed,
               COUNT(*) AS total,
               MAX(at) AS lastAt
        FROM discovery_feedback
        WHERE at >= :sinceMs
        GROUP BY artist_key
        """
    )
    suspend fun artistStatsSince(sinceMs: Long): List<ArtistDiscoveryStats>

    /** Forgets an artist's history ("allow again" on the stats page). */
    @Query("DELETE FROM discovery_feedback WHERE artist_key = :artistKey")
    suspend fun clearArtist(artistKey: String)

    @Query("DELETE FROM discovery_feedback WHERE at < :beforeMs")
    suspend fun pruneBefore(beforeMs: Long)
}
