package com.stash.data.download.export

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

class NavidromeUploadSchedulerTest {
    private val scheduler = NavidromeUploadScheduler(
        context = mockk<Context>(relaxed = true),
        prefs = mockk(relaxed = true),
    )

    @Test
    fun `paths stay compatible with the installed contract v1 layout`() {
        val first = scheduler.relativePathForTrack("Beyoncé", "Renaissance", "Alien Superstar", "flac")

        assertThat(first).isEqualTo("beyonc/renaissance/alien-superstar.flac")
        assertThat(first).isEqualTo(
            scheduler.relativePathForTrack("Beyoncé", "Renaissance", "Alien Superstar", "flac"),
        )
        assertThat(first).endsWith(".flac")
        assertThat(first.split('/')).hasSize(3)
    }

    @Test
    fun `cover and audio use the same collision safe album directory`() {
        val audio = scheduler.relativePathForTrack("Artist", "Album", "Song", "opus")
        val cover = scheduler.albumCoverRelativePath("Artist", "Album", "https://example.test/cover.jpg")

        assertThat(audio.substringBeforeLast('/')).isEqualTo(cover.substringBeforeLast('/'))
        assertThat(cover).endsWith("/cover.jpg")
    }
}
