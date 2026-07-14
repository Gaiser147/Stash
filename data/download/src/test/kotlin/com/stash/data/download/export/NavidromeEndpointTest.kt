package com.stash.data.download.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavidromeEndpointTest {
    @Test
    fun `normalizes a dedicated HTTPS ingest path`() {
        assertThat(NavidromeEndpoint.normalize("  https://music.example.test/stash-ingest/  "))
            .isEqualTo("https://music.example.test/stash-ingest")
    }

    @Test
    fun `rejects plaintext credentials query and fragment`() {
        assertThat(NavidromeEndpoint.normalize("http://music.example.test/stash-ingest")).isNull()
        assertThat(NavidromeEndpoint.normalize("https://token@music.example.test/stash-ingest")).isNull()
        assertThat(NavidromeEndpoint.normalize("https://music.example.test/stash-ingest?token=x")).isNull()
        assertThat(NavidromeEndpoint.normalize("https://music.example.test/stash-ingest#token")).isNull()
    }
}
