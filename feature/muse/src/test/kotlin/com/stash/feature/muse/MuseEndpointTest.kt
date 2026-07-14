package com.stash.feature.muse

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MuseEndpointTest {
    @Test
    fun `normalizes a root https endpoint`() {
        assertThat(MuseEndpoint.normalize("  https://music.example.test:8443/  "))
            .isEqualTo("https://music.example.test:8443")
    }

    @Test
    fun `rejects cleartext credentials and path rewriting`() {
        assertThrows(IllegalArgumentException::class.java) {
            MuseEndpoint.normalize("http://music.example.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MuseEndpoint.normalize("https://user:secret@music.example.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MuseEndpoint.normalize("https://music.example.test/proxy")
        }
    }

    @Test
    fun `builds stable contract paths without leaking ids into query`() {
        val route = MuseEndpoint.route(
            "https://music.example.test",
            "guilds",
            "1234567890123456",
            "player",
        )

        assertThat(route.toString())
            .isEqualTo("https://music.example.test/companion/v1/guilds/1234567890123456/player")
    }
}
