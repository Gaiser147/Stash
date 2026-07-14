package com.stash.data.download.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.crypto.TinkEncryptionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeExportPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val encryption = mockk<TinkEncryptionManager>().also { manager ->
        every { manager.encrypt(any()) } answers {
            firstArg<ByteArray>().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        every { manager.decrypt(any()) } answers {
            firstArg<ByteArray>().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }
    private val preferences = NavidromeExportPreferences(context, encryption)

    @Test
    fun `preferences stay secure and preserve export lifecycle state`() = runTest {
        preferences.clearConnection()
        assertThat(preferences.current().enabled).isFalse()

        preferences.saveConnection("https://music.example.test/stash-ingest/", "private-bearer-token")
        preferences.setEnabled(true)

        val config = preferences.current()
        assertThat(config.enabled).isTrue()
        assertThat(config.serverUrl).isEqualTo("https://music.example.test/stash-ingest")
        assertThat(config.token).isEqualTo("private-bearer-token")
        assertThat(config.wifiOnly).isTrue()
        assertThat(config.chargingOnly).isTrue()

        val store = context.filesDir.resolve("datastore/navidrome_export_preferences.preferences_pb")
        assertThat(store.readText()).doesNotContain("private-bearer-token")

        preferences.clearConnection()
        val enableFailure = runCatching { preferences.setEnabled(true) }.exceptionOrNull()
        assertThat(enableFailure).isInstanceOf(IllegalArgumentException::class.java)

        preferences.recordResult("track_uploaded", successful = true, now = 100L)

        preferences.recordAttempt(now = 200L)
        val attempting = preferences.current()
        assertThat(attempting.lastAttemptAt).isEqualTo(200L)
        assertThat(attempting.lastSuccessAt).isEqualTo(100L)
        assertThat(attempting.lastResult)
            .isEqualTo(NavidromeExportPreferences.RESULT_EXPORT_IN_PROGRESS)

        preferences.recordQueued(NavidromeExportPreferences.RESULT_FULL_EXPORT_QUEUED)
        val queued = preferences.current()
        assertThat(queued.lastAttemptAt).isEqualTo(200L)
        assertThat(queued.lastSuccessAt).isEqualTo(100L)
        assertThat(queued.lastResult)
            .isEqualTo(NavidromeExportPreferences.RESULT_FULL_EXPORT_QUEUED)

        preferences.saveConnection("https://music.example.test/stash-ingest", "private-token")
        every { encryption.decrypt(any()) } throws IllegalStateException("keystore unavailable")

        val unavailableToken = preferences.current()
        assertThat(unavailableToken.token).isEmpty()
        assertThat(unavailableToken.tokenConfigured).isFalse()
        assertThat(unavailableToken.tokenDecryptionFailed).isTrue()
    }
}
