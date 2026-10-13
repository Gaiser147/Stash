package com.stash.core.media.service

import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.PlaylistType

/**
 * How the Android Auto browse tree sorts playlists into its tabs. Pure, so
 * the layout the car shows is pinned by JVM tests (there's no car here).
 *
 * - "For you" — what Stash makes for the listener: its own mixes, the
 *   imported daily mixes and the liked songs, behind "Mix for you".
 * - "Playlists" — everything the listener collected or imported.
 */
internal object AutoBrowseTree {

    private val FOR_YOU_ORDER = listOf(
        PlaylistType.STASH_MIX,
        PlaylistType.DAILY_MIX,
        PlaylistType.STASH_LIKED,
        PlaylistType.LIKED_SONGS,
    )

    /** Playlists for the "For you" tab: mixes first, then liked songs. */
    fun forYou(all: List<PlaylistEntity>): List<PlaylistEntity> =
        all.filter { it.type in FOR_YOU_ORDER }
            .sortedWith(compareBy<PlaylistEntity> { FOR_YOU_ORDER.indexOf(it.type) }.thenBy { it.mixNumber ?: 0 }.thenBy { it.name.lowercase() })

    /** Playlists for the "Playlists" tab, alphabetical. */
    fun library(all: List<PlaylistEntity>): List<PlaylistEntity> =
        all.filter { it.type !in FOR_YOU_ORDER }.sortedBy { it.name.lowercase() }

    /** True for the generated mixes (shown under the "Mixes" group title). */
    fun isMix(playlist: PlaylistEntity): Boolean =
        playlist.type == PlaylistType.STASH_MIX || playlist.type == PlaylistType.DAILY_MIX
}
