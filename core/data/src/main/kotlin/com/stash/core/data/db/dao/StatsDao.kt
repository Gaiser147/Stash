package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/** One counted play with how long it was heard (see [com.stash.core.data.stats.StatsAggregator]). */
data class PlayRow(
    val trackId: Long,
    val startedAt: Long,
    val listenedMs: Long?,
    val durationMs: Long?,
    val trackDurationMs: Long,
)

data class TagWeight(val tag: String, val weight: Float)

/** Read-only queries behind the statistics page. */
@Dao
interface StatsDao {

    @Query(
        """
        SELECT le.track_id AS trackId, le.started_at AS startedAt, le.listened_ms AS listenedMs,
               le.duration_ms AS durationMs, t.duration_ms AS trackDurationMs
        FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE le.started_at >= :sinceMs
        """
    )
    suspend fun playsSince(sinceMs: Long): List<PlayRow>

    @Query("SELECT COUNT(*) FROM track_skip_events WHERE skipped_at >= :sinceMs")
    suspend fun skipsSince(sinceMs: Long): Int

    @Query(
        """
        SELECT COUNT(DISTINCT LOWER(t.artist)) FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE le.started_at >= :sinceMs
        """
    )
    suspend fun distinctArtistsSince(sinceMs: Long): Int

    /** Tags of what was played, each counted once per play. */
    @Query(
        """
        SELECT tt.tag AS tag, SUM(tt.weight) AS weight
        FROM listening_events le
        JOIN track_tags tt ON tt.track_id = le.track_id
        WHERE le.started_at >= :sinceMs AND tt.tag != '__untaggable__'
        GROUP BY LOWER(tt.tag)
        ORDER BY weight DESC
        LIMIT :limit
        """
    )
    suspend fun topTagsSince(sinceMs: Long, limit: Int = 12): List<TagWeight>

    /** Suggested new songs heard to the end since [sinceMs]. */
    @Query("SELECT COUNT(DISTINCT canonical_key) FROM discovery_feedback WHERE outcome = 'COMPLETED' AND at >= :sinceMs")
    suspend fun discoveriesKeptSince(sinceMs: Long): Int
}
