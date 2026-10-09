package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_IS_STREAMABLE
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android Auto browse tree can't be manually tested here (no AA
 * hardware, dated DHU), so these tests pin the exact logic the car sees:
 * which tracks appear/play, and what URI they carry.
 *
 * The original bug: children were gated on the BARE `is_streamable` flag,
 * which defaults to 0 meaning "not checked yet" — so every synced,
 * not-yet-downloaded row vanished ("playlist opens empty in the car") and
 * the survivors carried `filePath ?: ""` empty URIs that errored at play.
 */
@RunWith(RobolectricTestRunner::class)
class AutoBrowseTest {

    private fun track(
        id: Long = 1L,
        downloaded: Boolean = false,
        streamable: Boolean = false,
        checkedAt: Long? = null,
        filePath: String? = null,
        albumArtUrl: String? = null,
        albumArtPath: String? = null,
        album: String = "",
    ) = TrackEntity(
        id = id,
        title = "Song",
        artist = "Artist",
        isDownloaded = downloaded,
        isStreamable = streamable,
        isStreamableCheckedAt = checkedAt,
        filePath = filePath,
        youtubeId = "vid$id",
        durationMs = 200_000L,
        albumArtUrl = albumArtUrl,
        albumArtPath = albumArtPath,
        album = album,
    )

    // ---- isPlayableInAuto: the truth table from Track.isUnavailableForDisplay ----

    @Test
    fun `downloaded track is playable`() {
        assertThat(track(downloaded = true, filePath = "/m/a.flac").isPlayableInAuto(canStream = true)).isTrue()
    }

    @Test
    fun `confirmed-streamable track is playable`() {
        assertThat(track(streamable = true, checkedAt = 123L).isPlayableInAuto(canStream = true)).isTrue()
    }

    @Test
    fun `never-checked synced track is playable - the empty-playlist bug`() {
        // is_streamable=0 + checked_at=null means "unknown", NOT "unplayable".
        // The bare-flag filter dropped exactly these rows.
        assertThat(track(streamable = false, checkedAt = null).isPlayableInAuto(canStream = true)).isTrue()
    }

    @Test
    fun `confirmed-unstreamable undownloaded track is excluded`() {
        assertThat(track(streamable = false, checkedAt = 123L).isPlayableInAuto(canStream = true)).isFalse()
    }

    // ---- autoPlaybackUri: never an empty URI ----

    @Test
    fun `downloaded track gets its file uri`() {
        val uri = track(downloaded = true, filePath = "/music/a.flac").autoPlaybackUri()
        assertThat(uri.toString()).isEqualTo("file:///music/a.flac")
    }

    @Test
    fun `stream track gets a stash-resolve placeholder carrying resolver inputs`() {
        val uri = track(id = 42L).autoPlaybackUri()
        assertThat(uri.scheme).isEqualTo("stash-resolve")
        assertThat(uri.lastPathSegment).isEqualTo("42")
        assertThat(uri.getQueryParameter("yt")).isEqualTo("vid42")
        assertThat(uri.getQueryParameter("t")).isEqualTo("Song")
        assertThat(uri.getQueryParameter("a")).isEqualTo("Artist")
    }

    @Test
    fun `downloaded row with a missing path falls back to the placeholder not an empty uri`() {
        val uri = track(id = 7L, downloaded = true, filePath = null).autoPlaybackUri()
        assertThat(uri.scheme).isEqualTo("stash-resolve")
    }

    // ---- toAutoMediaItem: identity extras + playable flags ----

    @Test
    fun `auto item carries track id, playable flag, and streaming marker`() {
        val item = track(id = 9L).toAutoMediaItem(mediaId = "AUTOQ_p1_9")

        assertThat(item.mediaId).isEqualTo("AUTOQ_p1_9")
        assertThat(item.mediaMetadata.isPlayable).isTrue()
        assertThat(item.mediaMetadata.isBrowsable).isFalse()
        val extras = item.mediaMetadata.extras!!
        assertThat(extras.getLong(EXTRA_TRACK_ID)).isEqualTo(9L)
        assertThat(extras.getBoolean(EXTRA_TRACK_IS_STREAMABLE)).isTrue()
        assertThat(item.localConfiguration?.uri?.scheme).isEqualTo("stash-resolve")
    }

    @Test
    fun `downloaded auto item is marked non-streaming`() {
        val item = track(downloaded = true, filePath = "/m/b.flac").toAutoMediaItem()
        assertThat(item.mediaMetadata.extras!!.getBoolean(EXTRA_TRACK_IS_STREAMABLE)).isFalse()
        assertThat(item.localConfiguration?.uri?.toString()).isEqualTo("file:///m/b.flac")
    }

    // ---- isPlayableInAuto without streaming: downloads only ----

    @Test
    fun `without streaming only downloads play - no stream-only songs queued to fail`() {
        assertThat(track(downloaded = true, filePath = "/m/a.flac").isPlayableInAuto(canStream = false)).isTrue()
        assertThat(track(streamable = true, checkedAt = 123L).isPlayableInAuto(canStream = false)).isFalse()
        assertThat(track(streamable = false, checkedAt = null).isPlayableInAuto(canStream = false)).isFalse()
    }

    // ---- design: covers the car can load, subtitle, offline badge ----

    @Test
    fun `local cover goes through the artwork provider, not a file path`() {
        val item = track(id = 5L, albumArtPath = "/data/cache/albumart/x.jpg")
            .toAutoMediaItem(artAuthority = "com.stash.app.autoart")
        assertThat(item.mediaMetadata.artworkUri.toString()).isEqualTo("content://com.stash.app.autoart/track/5")
    }

    @Test
    fun `remote cover wins over the local one`() {
        val item = track(id = 5L, albumArtUrl = "https://img/x.jpg", albumArtPath = "/data/x.jpg")
            .toAutoMediaItem(artAuthority = "com.stash.app.autoart")
        assertThat(item.mediaMetadata.artworkUri.toString()).isEqualTo("https://img/x.jpg")
    }

    @Test
    fun `subtitle is artist and album, without an empty album`() {
        assertThat(track(album = "Blue").autoSubtitle()).isEqualTo("Artist · Blue")
        assertThat(track(album = "").autoSubtitle()).isEqualTo("Artist")
    }

    @Test
    fun `downloaded songs carry the car's downloaded badge, streams don't`() {
        val key = androidx.media3.session.MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS
        val local = track(downloaded = true, filePath = "/m/b.flac").toAutoMediaItem().mediaMetadata.extras!!
        val stream = track().toAutoMediaItem().mediaMetadata.extras!!
        assertThat(local.getLong(key)).isEqualTo(androidx.media3.session.MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
        assertThat(stream.containsKey(key)).isFalse()
    }

    // ---- search + paging helpers ----

    @Test
    fun `fts query strips punctuation a spoken name can contain`() {
        assertThat(ftsQuery("AC/DC  rock'n'roll")).isEqualTo("AC* DC* rock* n* roll*")
        assertThat(ftsQuery("  ")).isEmpty()
    }

    @Test
    fun `pageOf slices pages and returns everything for an unpaged request`() {
        val items = (1..5).toList()
        assertThat(pageOf(items, 0, 2)).containsExactly(1, 2).inOrder()
        assertThat(pageOf(items, 2, 2)).containsExactly(5)
        assertThat(pageOf(items, 3, 2)).isEmpty()
        assertThat(pageOf(items, 0, Int.MAX_VALUE)).isEqualTo(items)
    }
}
