package com.stash.feature.muse

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class MuseCompanionContractTest {
    private val json = MuseCompanionJson

    @Test
    fun `player snapshot preserves immutable ids permissions and revisions`() {
        val response = json.decodeFromString<MusePlayerResponse>(
            """
                {
                  "contract":"muse-companion/v1",
                  "target":"discord",
                  "guild":{"id":"1234567890123456","name":"Test","voiceChannel":{"id":"9","name":"Music"}},
                  "permissions":{"canRead":true,"canControl":false,"canWriteQueue":true,"manageGuild":false},
                  "djMode":{"active":true,"djUserId":"8","futureField":"ignored"},
                  "player":{
                    "phase":"paused","status":"paused","positionSeconds":42,"volume":70,
                    "repeatSong":false,"repeatQueue":true,"playerRevision":12,"queueRevision":34,
                    "current":{"entryId":"entry-current","title":"A","artist":"B","durationSeconds":200,"source":"navidrome","quality":null},
                    "queue":[{"entryId":"entry-next","title":"C","artist":"D","durationSeconds":180,"requestedBy":"7","source":"youtube","quality":"Opus"}],
                    "queueLength":1,"queueRemainingSeconds":180
                  }
                }
            """.trimIndent(),
        )

        assertThat(response.permissions.canControl).isFalse()
        assertThat(response.permissions.canWriteQueue).isTrue()
        assertThat(response.player.playerRevision).isEqualTo(12)
        assertThat(response.player.queueRevision).isEqualTo(34)
        assertThat(response.player.current?.entryId).isEqualTo("entry-current")
        assertThat(response.player.queue.single().qualityLabel).isEqualTo("Opus")
    }

    @Test
    fun `actions carry only relevant expected revisions`() {
        val snapshot = sampleSnapshot()

        val pause = envelope(MuseRemoteAction.pause(), snapshot)
        val move = envelope(MuseRemoteAction.move("entry-next", 1), snapshot)
        val next = envelope(MuseRemoteAction.next(), snapshot)

        assertThat(pause.expectedPlayerRevision).isEqualTo(12)
        assertThat(pause.expectedQueueRevision).isNull()
        assertThat(move.expectedPlayerRevision).isNull()
        assertThat(move.expectedQueueRevision).isEqualTo(34)
        assertThat(next.expectedPlayerRevision).isEqualTo(12)
        assertThat(next.expectedQueueRevision).isEqualTo(34)
    }

    @Test
    fun `structured measured quality is tolerant and never invents lossless`() {
        val measured = json.decodeFromString<MuseSong>(
            """{"entryId":"one","title":"A","artist":"B","durationSeconds":1,"source":"navidrome","quality":{"codec":"flac","sampleRateHz":96000,"bitsPerSample":24,"channels":2,"bitrateKbps":1648,"future":"ignored"}}""",
        )
        val codecUnknown = json.decodeFromString<MuseSong>(
            """{"entryId":"two","title":"A","artist":"B","durationSeconds":1,"source":"navidrome","quality":{"sampleRateHz":44100,"unknownCodecHint":true}}""",
        )
        val entirelyUnknown = json.decodeFromString<MuseSong>(
            """{"entryId":"three","title":"A","artist":"B","durationSeconds":1,"source":"navidrome","quality":{"future":"value"}}""",
        )

        assertThat(measured.qualityLabel).isEqualTo("FLAC · 24 Bit/96 kHz · 2 Kanäle · 1648 kbit/s")
        assertThat(codecUnknown.qualityLabel).isEqualTo("44,1 kHz")
        assertThat(codecUnknown.qualityLabel).doesNotContain("FLAC")
        assertThat(entirelyUnknown.qualityLabel).isNull()
    }

    @Test
    fun `pairing challenge request omits client asserted fingerprint and bearer credentials`() {
        val encoded = json.encodeToString(
            MusePairingChallengeRequest.serializer(),
            MusePairingChallengeRequest(
                publicKeySpki = "spki",
                deviceName = "Phone",
                appVersion = "0.9.75",
            ),
        )

        assertThat(encoded).contains("\"contract\":\"muse-companion/v1\"")
        assertThat(encoded).doesNotContain("accessToken")
        assertThat(encoded).doesNotContain("refreshToken")
        assertThat(encoded).doesNotContain("publicKeyFingerprint")
    }

    @Test
    fun `refresh body binds one idempotency key into strict four field contract`() {
        val encoded = json.encodeToString(
            MuseRefreshRequest.serializer(),
            MuseRefreshRequest(
                deviceId = "11111111-1111-1111-1111-111111111111",
                refreshToken = "refresh-token-with-at-least-thirty-two-characters",
                idempotencyKey = "22222222-2222-2222-2222-222222222222",
            ),
        )

        assertThat(encoded).isEqualTo(
            "{\"contract\":\"muse-companion/v1\",\"deviceId\":\"11111111-1111-1111-1111-111111111111\"," +
                "\"refreshToken\":\"refresh-token-with-at-least-thirty-two-characters\"," +
                "\"idempotencyKey\":\"22222222-2222-2222-2222-222222222222\"}",
        )
    }

    private fun envelope(action: MuseRemoteAction, snapshot: MusePlayerResponse) = MuseActionEnvelope(
        action = action.wireName,
        idempotencyKey = "12345678-1234-1234-1234-123456789abc",
        expectedPlayerRevision = snapshot.player.playerRevision.takeIf { action.needsPlayerRevision },
        expectedQueueRevision = snapshot.player.queueRevision.takeIf { action.needsQueueRevision },
        arguments = action.arguments,
    )

    private fun sampleSnapshot() = MusePlayerResponse(
        contract = MUSE_COMPANION_CONTRACT,
        target = "discord",
        guild = MuseGuild("1234567890123456", "Test"),
        permissions = MusePermissions(true, true, true, false),
        player = MusePlayerState(
            phase = "playing",
            status = "playing",
            positionSeconds = 1,
            volume = 50,
            repeatSong = false,
            repeatQueue = false,
            playerRevision = 12,
            queueRevision = 34,
            queueLength = 0,
            queueRemainingSeconds = 0,
        ),
    )
}
