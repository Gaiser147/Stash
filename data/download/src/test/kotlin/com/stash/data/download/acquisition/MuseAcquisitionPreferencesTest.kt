package com.stash.data.download.acquisition

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
class MuseAcquisitionPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val encryption = mockk<TinkEncryptionManager>().also { manager ->
        every { manager.encrypt(any()) } answers {
            firstArg<ByteArray>().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        every { manager.decrypt(any()) } answers {
            firstArg<ByteArray>().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }
    private val preferences = MuseAcquisitionPreferences(context, encryption)

    @Test
    fun `connection is default off encrypted and assigned a stable consumer id`() = runTest {
        preferences.clearConnection()
        assertThat(preferences.current().enabled).isFalse()

        val token = "a-secure-token-with-at-least-32-characters"
        preferences.saveConnection("https://muse.example.test/acquisition/", token)
        preferences.setEnabled(true)

        val first = preferences.current()
        val second = preferences.current()
        assertThat(first.configured).isTrue()
        assertThat(first.serverUrl).isEqualTo("https://muse.example.test/acquisition")
        assertThat(first.token).isEqualTo(token)
        assertThat(first.consumerId).startsWith("stash-")
        assertThat(second.consumerId).isEqualTo(first.consumerId)
        assertThat(first.wifiOnly).isTrue()
        assertThat(first.chargingOnly).isTrue()

        val store = context.filesDir.resolve("datastore/muse_acquisition_preferences.preferences_pb")
        assertThat(store.readText()).doesNotContain(token)
    }

    @Test
    fun `endpoint rejects insecure and credential-bearing urls`() {
        assertThat(MuseAcquisitionEndpoint.normalize("http://muse.example.test")).isNull()
        assertThat(MuseAcquisitionEndpoint.normalize("https://user@muse.example.test")).isNull()
        assertThat(MuseAcquisitionEndpoint.normalize("https://muse.example.test/path?token=x")).isNull()
        assertThat(MuseAcquisitionEndpoint.normalize("https://muse.example.test/path#fragment")).isNull()
        assertThat(MuseAcquisitionEndpoint.normalize("https://muse.example.test/path/"))
            .isEqualTo("https://muse.example.test/path")
    }
}
