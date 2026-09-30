package com.stash.core.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v33 -> v34 adds the `audio_features` table (server-side audio analysis); nothing else changes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV33V34Test {

    private val DB_NAME = "migration-v33v34-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `adds an empty audio_features table that accepts unlinked rows`() {
        helper.createDatabase(DB_NAME, 33).close()

        val db = helper.runMigrationsAndValidate(DB_NAME, 34, true, StashDatabase.MIGRATION_33_34)

        val row = ContentValues().apply {
            put("path", "artist/album/song.opus")
            putNull("track_id")
            put("bpm", 124.0)
            put("beat_confidence", 0.8)
            put("beat_regularity", 0.9)
            put("loudness_db", -9.5)
            put("dynamics_db", 6.0)
            put("brightness_hz", 2500.0)
            put("onset_rate", 3.1)
            put("pitch_class", 7)
            put("minor", 0)
            put("key_strength", 0.7)
            put("analyzer_version", 1)
            put("seq", 42)
        }
        db.insert("audio_features", SQLiteDatabase.CONFLICT_FAIL, row)
        db.query("SELECT bpm, track_id FROM audio_features WHERE path = 'artist/album/song.opus'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(124.0, c.getDouble(0), 1e-6)
            assertTrue(c.isNull(1))
        }
    }
}
