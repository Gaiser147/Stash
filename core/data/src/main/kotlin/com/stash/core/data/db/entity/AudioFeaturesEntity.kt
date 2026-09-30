package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Audio features measured on the user's server (stash-ingest's analyzer) for
 * one uploaded file, keyed by its upload path relative to the Stash prefix
 * (`artist/album/title.ext`, the same path the Navidrome export writes).
 * [trackId] is resolved on the phone by recomputing that path for every
 * downloaded track; it stays null for files this library doesn't hold (yet),
 * so a later download picks its features up without another fetch.
 *
 * Units: [bpm] beats per minute folded into 70-180; [loudnessDb] mean
 * short-term RMS in dBFS; [dynamicsDb] loud-vs-quiet spread in dB;
 * [brightnessHz] mean spectral centroid; [onsetRate] onsets per second;
 * [pitchClass] 0 = C … 11 = B.
 */
@Entity(
    tableName = "audio_features",
    indices = [Index("track_id")],
)
data class AudioFeaturesEntity(
    @PrimaryKey
    val path: String,
    @ColumnInfo(name = "track_id")
    val trackId: Long? = null,
    val bpm: Float,
    @ColumnInfo(name = "beat_confidence")
    val beatConfidence: Float,
    @ColumnInfo(name = "beat_regularity")
    val beatRegularity: Float,
    @ColumnInfo(name = "loudness_db")
    val loudnessDb: Float,
    @ColumnInfo(name = "dynamics_db")
    val dynamicsDb: Float,
    @ColumnInfo(name = "brightness_hz")
    val brightnessHz: Float,
    @ColumnInfo(name = "onset_rate")
    val onsetRate: Float,
    @ColumnInfo(name = "pitch_class")
    val pitchClass: Int,
    val minor: Boolean,
    @ColumnInfo(name = "key_strength")
    val keyStrength: Float,
    @ColumnInfo(name = "analyzer_version")
    val analyzerVersion: Int,
    /** stash-ingest's change sequence number (the paging cursor). */
    val seq: Long,
)
