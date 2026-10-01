package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import org.junit.Test

class AutoBrowseTreeTest {

    private fun playlist(id: Long, name: String, type: PlaylistType, mix: Int? = null) =
        PlaylistEntity(id = id, name = name, source = MusicSource.SPOTIFY, type = type, mixNumber = mix)

    private val all = listOf(
        playlist(1, "Road Trip", PlaylistType.CUSTOM),
        playlist(2, "Liked Songs", PlaylistType.LIKED_SONGS),
        playlist(3, "Daily Mix 2", PlaylistType.DAILY_MIX, mix = 2),
        playlist(4, "Daily Mix 1", PlaylistType.DAILY_MIX, mix = 1),
        playlist(5, "Rediscovery", PlaylistType.STASH_MIX),
        playlist(6, "Akustik", PlaylistType.CUSTOM),
    )

    @Test fun `for you holds the mixes first, then liked songs`() {
        assertThat(AutoBrowseTree.forYou(all).map { it.id }).containsExactly(5L, 4L, 3L, 2L).inOrder()
    }

    @Test fun `playlists tab holds everything else, alphabetical`() {
        assertThat(AutoBrowseTree.library(all).map { it.name }).containsExactly("Akustik", "Road Trip").inOrder()
    }

    @Test fun `every playlist lands in exactly one tab`() {
        val placed = AutoBrowseTree.forYou(all) + AutoBrowseTree.library(all)
        assertThat(placed.map { it.id }).containsExactlyElementsIn(all.map { it.id })
    }

    @Test fun `mixes are recognised for the group title`() {
        assertThat(all.filter(AutoBrowseTree::isMix).map { it.id }).containsExactly(3L, 4L, 5L)
    }
}
