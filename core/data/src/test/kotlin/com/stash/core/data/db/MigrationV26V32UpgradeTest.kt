package com.stash.core.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the real installed-device upgrade boundary: Stash v0.9.32 used Room
 * schema 26. A single migration run to the current schema must retain a track,
 * playlist, and their ordering link without any destructive fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV26V32UpgradeTest {
    private val databaseName = "migration-v26-v32-upgrade-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `v0_9_32 library rows survive the complete migration chain`() {
        helper.createDatabase(databaseName, 26).use { db ->
            db.insertLegacyTrack()
            db.insert(
                "playlists",
                SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("id", 7L)
                    put("name", "Preserve Me")
                    put("source", "SPOTIFY")
                    put("source_id", "legacy-playlist")
                    put("type", "USER")
                    put("track_count", 1)
                    put("is_active", 1)
                    put("sync_enabled", 1)
                    put("date_added", 1_700_000_000_000L)
                },
            )
            db.insert(
                "playlist_tracks",
                SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("playlist_id", 7L)
                    put("track_id", 42L)
                    put("position", 3)
                    put("added_at", 1_700_000_000_000L)
                    put("locally_added", 1)
                },
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            32,
            true,
            StashDatabase.MIGRATION_26_27,
            StashDatabase.MIGRATION_27_28,
            StashDatabase.MIGRATION_28_29,
            StashDatabase.MIGRATION_29_30,
            StashDatabase.MIGRATION_30_31,
            StashDatabase.MIGRATION_31_32,
        )

        migrated.query(
            """
                SELECT t.title, t.artist, t.file_path, p.name, pt.position,
                       t.metadata_embedded_at, t.album_artist
                FROM tracks t
                JOIN playlist_tracks pt ON pt.track_id = t.id
                JOIN playlists p ON p.id = pt.playlist_id
                WHERE t.id = 42 AND p.id = 7
            """.trimIndent(),
        ).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Legacy Track", cursor.getString(0))
            assertEquals("Legacy Artist", cursor.getString(1))
            assertEquals("/music/legacy.flac", cursor.getString(2))
            assertEquals("Preserve Me", cursor.getString(3))
            assertEquals(3, cursor.getInt(4))
            assertTrue(cursor.isNull(5))
            assertEquals("", cursor.getString(6))
        }
        migrated.close()
    }

    private fun SupportSQLiteDatabase.insertLegacyTrack() {
        insert(
            "tracks",
            SQLiteDatabase.CONFLICT_FAIL,
            ContentValues().apply {
                put("id", 42L)
                put("title", "Legacy Track")
                put("artist", "Legacy Artist")
                put("album", "Legacy Album")
                put("album_artist", "")
                put("duration_ms", 240_000L)
                put("file_path", "/music/legacy.flac")
                put("file_format", "flac")
                put("quality_kbps", 900)
                put("file_size_bytes", 27_000_000L)
                put("source", "SPOTIFY")
                put("date_added", 1_700_000_000_000L)
                put("play_count", 4)
                put("is_downloaded", 1)
                put("canonical_title", "legacy track")
                put("canonical_artist", "legacy artist")
                put("match_confidence", 0.98f)
                put("match_dismissed", 0)
                put("match_flagged", 0)
                put("lastfm_user_loved", 0)
                put("is_streamable", 0)
            },
        )
    }
}
