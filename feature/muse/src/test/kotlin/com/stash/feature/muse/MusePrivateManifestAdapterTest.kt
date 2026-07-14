package com.stash.feature.muse

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MusePrivateManifestAdapterTest {
    private val adapter = MusePrivateManifestAdapter()
    private val account = "a".repeat(64)

    @Test
    fun `transforms a complete file and zeroes raw bytes`() {
        val bytes = validJson().encodeToByteArray()

        val prepared = adapter.prepare(MuseSelectedDocument(bytes, "playlists.json"), account, 7)

        assertThat(bytes.all { it == 0.toByte() }).isTrue()
        assertThat(prepared.uploadTemplate.source).isEqualTo("file_import")
        assertThat(prepared.uploadTemplate.generation).isEqualTo(7)
        assertThat(prepared.uploadTemplate.importReport.skippedEntries).isEqualTo(0)
        assertThat(prepared.uploadTemplate.playlists.single().items.single().artists)
            .containsExactly("Fleetwood Mac")
        assertThat(prepared.uploadTemplate.manifestFingerprint).matches("^[0-9a-f]{64}$")
        assertThat(prepared.byteSize).isLessThan(MusePrivateManifestAdapter.MAX_REMOTE_BODY_BYTES)
    }

    @Test
    fun `manifest fingerprint is deterministic for response-loss retry`() {
        val first = adapter.prepare(
            MuseSelectedDocument(validJson().encodeToByteArray(), "one.json"),
            account,
            3,
        )
        val second = adapter.prepare(
            MuseSelectedDocument(validJson().encodeToByteArray(), "two.json"),
            account,
            3,
        )

        assertThat(first.uploadTemplate.manifestFingerprint)
            .isEqualTo(second.uploadTemplate.manifestFingerprint)
    }

    @Test
    fun `skipped entry rejects the complete generation before serialization`() {
        val csv = """
            playlist,track_uri,title,artist,duration_ms
            Mix,spotify:track:0ofHAoxe9vBkTCp2UQIavz,Dreams,Fleetwood Mac,257000
            Mix,,,,
        """.trimIndent().encodeToByteArray()

        val error = assertThrows(MuseManifestValidationException::class.java) {
            adapter.prepare(MuseSelectedDocument(csv, "mix.csv"), account, 1)
        }

        assertThat(error.code).isEqualTo("partial_import_not_allowed")
        assertThat(csv.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `missing duration is reported instead of guessed`() {
        val bytes = validJson().replace(",\"durationMs\":257000", "").encodeToByteArray()

        val error = assertThrows(MuseManifestValidationException::class.java) {
            adapter.prepare(MuseSelectedDocument(bytes, "missing.json"), account, 1)
        }

        assertThat(error.code).isEqualTo("missing_metadata")
        assertThat(error.message).contains("Dauer")
    }

    @Test
    fun `more than one hundred playlists is never truncated`() {
        val playlists = (1..101).joinToString(",") { index ->
            "{\"playlistId\":\"list-$index\",\"name\":\"List $index\",\"tracks\":[${validTrack()}]}"
        }
        val bytes = "{\"contract\":\"muse-private-manifest/v1\",\"playlists\":[$playlists]}"
            .encodeToByteArray()

        val error = assertThrows(MuseManifestValidationException::class.java) {
            adapter.prepare(MuseSelectedDocument(bytes, "many.json"), account, 1)
        }

        assertThat(error.code).isEqualTo("too_many_playlists")
    }

    @Test
    fun `more than five thousand items across valid sized playlists is never truncated`() {
        fun playlist(number: Int, count: Int): String {
            val tracks = (1..count).joinToString(",") { validTrack("Song $number-$it") }
            return "{\"playlistId\":\"list-$number\",\"name\":\"List $number\",\"tracks\":[$tracks]}"
        }
        val bytes = ("{\"contract\":\"muse-private-manifest/v1\",\"playlists\":[" +
            listOf(playlist(1, 2_000), playlist(2, 2_000), playlist(3, 1_001)).joinToString(",") +
            "]}").encodeToByteArray()

        val error = assertThrows(MuseManifestValidationException::class.java) {
            adapter.prepare(MuseSelectedDocument(bytes, "too-many.json"), account, 1)
        }

        assertThat(error.code).isEqualTo("too_many_items")
    }

    @Test
    fun `exact upload encoding enforces two MiB preflight`() {
        val large = "x".repeat(512)
        val tracks = List(5_000) {
            MuseManifestTrack(title = large, artists = listOf(large), album = large, durationMs = 1)
        }
        val report = MuseManifestImportReport(acceptedPlaylists = 3, acceptedItems = 5_000)
        val playlists = listOf(
            MuseManifestPlaylist("one", "One", "1".repeat(64), tracks.take(2_000)),
            MuseManifestPlaylist("two", "Two", "2".repeat(64), tracks.subList(2_000, 4_000)),
            MuseManifestPlaylist("three", "Three", "3".repeat(64), tracks.drop(4_000)),
        )
        val prepared = MusePreparedManifest(
            uploadTemplate = MuseManifestUpload(
                idempotencyKey = "00000000-0000-0000-0000-000000000000",
                accountFingerprint = account,
                generation = 1,
                manifestFingerprint = "f".repeat(64),
                importReport = report,
                playlists = playlists,
            ),
            byteSize = 0,
            formatLabel = "test",
        )

        val error = assertThrows(MuseManifestValidationException::class.java) {
            adapter.encodeForUpload(prepared, "12345678-1234-1234-1234-123456789abc")
        }

        assertThat(error.code).isEqualTo("manifest_too_large")
    }

    private fun validJson() = """
        {"contract":"muse-private-manifest/v1","playlists":[{
          "playlistId":"owned-list-1","name":"Favorites","tracks":[${validTrack()}]
        }]}
    """.trimIndent()

    private fun validTrack(title: String = "Dreams") =
        "{\"trackUri\":\"spotify:track:0ofHAoxe9vBkTCp2UQIavz\",\"isrc\":\"USWB17700363\"," +
            "\"title\":\"$title\",\"artist\":\"Fleetwood Mac\",\"album\":\"Rumours\"," +
            "\"durationMs\":257000}"
}
