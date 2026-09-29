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
 * v32 -> v33 adds `listening_events.nd_scrobbled` (reporting plays to the
 * user's Navidrome server). Plays recorded before the upgrade must count as
 * already reported so enabling the feature doesn't replay the history;
 * new rows default to pending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV32V33Test {

    private val DB_NAME = "migration-v32v33-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun event(trackId: Long, startedAt: Long) = ContentValues().apply {
        put("track_id", trackId)
        put("started_at", startedAt)
        put("scrobbled", 0)
    }

    @Test
    fun `existing plays are marked reported and new plays start pending`() {
        helper.createDatabase(DB_NAME, 32).use { db ->
            db.insert("listening_events", SQLiteDatabase.CONFLICT_FAIL, event(1, 1_000L))
            db.insert("listening_events", SQLiteDatabase.CONFLICT_FAIL, event(2, 2_000L))
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 33, true, StashDatabase.MIGRATION_32_33)

        migrated.query("SELECT COUNT(*) FROM listening_events WHERE nd_scrobbled = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
        migrated.insert("listening_events", SQLiteDatabase.CONFLICT_FAIL, event(3, 3_000L))
        migrated.query("SELECT nd_scrobbled FROM listening_events WHERE track_id = 3").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }
}
