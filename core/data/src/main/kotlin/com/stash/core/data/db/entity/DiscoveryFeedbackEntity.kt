package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v35: how a suggested new song (an autoplay discovery) was received.
 *
 * The autoplay session used to forget this when it ended, so an artist the
 * user skipped twice came back in the next session. One row per heard
 * discovery; the engine reads the recent rows to block artists the user
 * keeps rejecting and to seed from the ones they liked.
 *
 * @property canonicalKey `canonicalArtist|canonicalTitle` of the song.
 * @property artistKey canonical artist name.
 * @property outcome [com.stash.core.data.autoplay.ListenOutcome] name.
 */
@Entity(
    tableName = "discovery_feedback",
    indices = [Index(value = ["artist_key"]), Index(value = ["at"])],
)
data class DiscoveryFeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "canonical_key")
    val canonicalKey: String,
    @ColumnInfo(name = "artist_key")
    val artistKey: String,
    @ColumnInfo(name = "outcome")
    val outcome: String,
    @ColumnInfo(name = "at")
    val at: Long,
)
