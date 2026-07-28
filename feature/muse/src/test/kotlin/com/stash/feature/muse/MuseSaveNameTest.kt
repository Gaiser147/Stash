package com.stash.feature.muse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The collection name for `saveCurrent` is validated by the gateway
 * (NFC normalization, non-empty, at most 80 characters, no control
 * characters). The app mirrors that check so a bad name is caught in the
 * dialog instead of coming back as an opaque `invalid_action_arguments`.
 */
class MuseSaveNameTest {

    @Test
    fun `accepts an ordinary name and trims surrounding space`() {
        assertThat(MuseRemoteAction.normalizeSaveName("  Abendmusik  ")).isEqualTo("Abendmusik")
    }

    @Test
    fun `rejects empty and blank names`() {
        assertThat(MuseRemoteAction.normalizeSaveName("")).isNull()
        assertThat(MuseRemoteAction.normalizeSaveName("   ")).isNull()
    }

    @Test
    fun `enforces the server length limit`() {
        val atLimit = "x".repeat(MuseRemoteAction.SAVE_NAME_MAX_LENGTH)
        assertThat(MuseRemoteAction.normalizeSaveName(atLimit)).isEqualTo(atLimit)
        assertThat(MuseRemoteAction.normalizeSaveName("x".repeat(MuseRemoteAction.SAVE_NAME_MAX_LENGTH + 1)))
            .isNull()
    }

    @Test
    fun `rejects control characters the gateway would refuse`() {
        assertThat(MuseRemoteAction.normalizeSaveName("Abendmusik")).isNull()
        assertThat(MuseRemoteAction.normalizeSaveName("Abend​musik")).isNull()
    }

    @Test
    fun `normalizes to NFC so the length check matches the server`() {
        // "é" as e + combining acute composes to a single code point.
        val decomposed = "Café"
        val normalized = MuseRemoteAction.normalizeSaveName(decomposed)
        assertThat(normalized).isEqualTo("Café")
        assertThat(normalized!!.length).isEqualTo(4)
    }

    @Test
    fun `builds the wire action with the expected name argument`() {
        val action = MuseRemoteAction.saveCurrent("Abendmusik")
        assertThat(action.wireName).isEqualTo("saveCurrent")
        assertThat(action.arguments.toString()).contains("Abendmusik")
        assertThat(action.needsPlayerRevision).isTrue()
    }

    @Test
    fun `builds the like action with a boolean argument`() {
        assertThat(MuseRemoteAction.setLiked(true).wireName).isEqualTo("setLiked")
        assertThat(MuseRemoteAction.setLiked(false).arguments.toString()).contains("false")
    }
}
