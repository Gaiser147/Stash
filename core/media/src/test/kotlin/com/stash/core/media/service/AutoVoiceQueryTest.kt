package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.service.AutoVoiceQuery.Target
import org.junit.Test

class AutoVoiceQueryTest {

    private val playlists = listOf(1L to "Road Trip", 2L to "Daily Mix 1", 3L to "Chill")

    @Test fun `a bare play request starts the mix`() {
        assertThat(AutoVoiceQuery.resolve("", playlists)).isEqualTo(Target.MixForYou)
        assertThat(AutoVoiceQuery.resolve(null, playlists)).isEqualTo(Target.MixForYou)
    }

    @Test fun `mix phrases in English and German start the mix`() {
        assertThat(AutoVoiceQuery.resolve("Mix für mich", playlists)).isEqualTo(Target.MixForYou)
        assertThat(AutoVoiceQuery.resolve("mix for me", playlists)).isEqualTo(Target.MixForYou)
        assertThat(AutoVoiceQuery.resolve("Musik", playlists)).isEqualTo(Target.MixForYou)
    }

    @Test fun `an exact playlist name wins, even one containing mix`() {
        assertThat(AutoVoiceQuery.resolve("daily mix 1", playlists)).isEqualTo(Target.Playlist(2L))
        assertThat(AutoVoiceQuery.resolve("Road-Trip!", playlists)).isEqualTo(Target.Playlist(1L))
    }

    @Test fun `a playlist named inside the request is found`() {
        assertThat(AutoVoiceQuery.resolve("my road trip playlist", playlists)).isEqualTo(Target.Playlist(1L))
    }

    @Test fun `anything else searches songs`() {
        assertThat(AutoVoiceQuery.resolve("Bohemian Rhapsody", playlists)).isEqualTo(Target.Songs("Bohemian Rhapsody"))
    }
}
