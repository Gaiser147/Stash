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

/**
 * v34 -> v35 adds `listening_events.listened_ms` / `duration_ms` and the
 * `discovery_feedback` table. Existing plays keep their rows with NULL
 * depth (unknown), and the new table accepts feedback rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV34V35Test {

    private val DB_NAME = "migration-v34v35-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `old plays keep unknown depth and feedback rows can be stored`() {
        helper.createDatabase(DB_NAME, 34).use { db ->
            db.insert(
                "listening_events",
                SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("track_id", 1)
                    put("started_at", 1_000L)
                    put("scrobbled", 0)
                    put("completed_at", 1_000L)
                },
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 35, true, StashDatabase.MIGRATION_34_35)

        migrated.query("SELECT listened_ms, duration_ms FROM listening_events").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }
        migrated.insert(
            "discovery_feedback",
            SQLiteDatabase.CONFLICT_FAIL,
            ContentValues().apply {
                put("canonical_key", "artist|song")
                put("artist_key", "artist")
                put("outcome", "EARLY_SKIP")
                put("at", 5_000L)
            },
        )
        migrated.query("SELECT COUNT(*) FROM discovery_feedback").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }
}
