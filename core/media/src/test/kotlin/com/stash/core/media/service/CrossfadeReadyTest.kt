package com.stash.core.media.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.truth.Truth.assertThat
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The crossfade may only prime the spare on an item that can start at once.
 * An unresolved `stash-resolve://` placeholder can't: fading into it when the
 * resolve failed left a silent player — "playback just stops" in the car.
 */
@RunWith(RobolectricTestRunner::class)
class CrossfadeReadyTest {

    private fun item(uri: String, origin: String? = null) = MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(Bundle().apply { origin?.let { putString(EXTRA_STREAM_ORIGIN, it) } }).build(),
        )
        .build()

    @Test fun `local files are ready`() {
        assertThat(isCrossfadeReady(item("file:///music/a.flac"))).isTrue()
    }

    @Test fun `an unresolved stash-resolve placeholder is not ready`() {
        assertThat(isCrossfadeReady(item("stash-resolve://track/42?yt=abc"))).isFalse()
    }

    @Test fun `a resolved stream is ready, a bare placeholder url is not`() {
        assertThat(isCrossfadeReady(item("https://cdn/x.m4a", origin = "youtube"))).isTrue()
        assertThat(isCrossfadeReady(item("https://cdn/x.m4a"))).isFalse()
    }
}
