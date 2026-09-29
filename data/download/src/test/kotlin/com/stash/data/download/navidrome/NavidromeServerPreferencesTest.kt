package com.stash.data.download.navidrome

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.crypto.TinkEncryptionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeServerPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val encryption = mockk<TinkEncryptionManager>().also { manager ->
        every { manager.encrypt(any()) } answers {
            firstArg<ByteArray>().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        every { manager.decrypt(any()) } answers {
            firstArg<ByteArray>().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }
    private val preferences = NavidromeServerPreferences(context, encryption)

    @Test fun `password is encrypted at rest and kept when left blank for the same server`() = runTest {
        preferences.clearConnection()
        preferences.saveConnection("https://nd.example.test/", "stash", "s3cret-pass")

        // Only the encrypted form is ever handed to storage.
        verify { encryption.encrypt("s3cret-pass".toByteArray(Charsets.UTF_8)) }

        preferences.saveConnection("https://nd.example.test", "stash2", "")
        val config = preferences.current()
        assertThat(config.password).isEqualTo("s3cret-pass")
        assertThat(config.username).isEqualTo("stash2")
        assertThat(config.configured).isTrue()
    }

    @Test fun `a new server never receives the saved password`() = runTest {
        preferences.clearConnection()
        preferences.saveConnection("https://nd.example.test", "stash", "s3cret-pass")

        val failure = runCatching {
            preferences.saveConnection("https://evil.example.test", "stash", "")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(preferences.current().serverUrl).isEqualTo("https://nd.example.test")

        preferences.saveConnection("https://other.example.test", "stash", "other-pass")
        assertThat(preferences.current().password).isEqualTo("other-pass")
    }

    @Test fun `plain http is rejected`() = runTest {
        val failure = runCatching {
            preferences.saveConnection("http://100.121.106.101:4533", "stash", "pw")
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}
