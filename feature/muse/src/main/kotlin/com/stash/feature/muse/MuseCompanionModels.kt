package com.stash.feature.muse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal val MuseCompanionJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal const val MUSE_COMPANION_CONTRACT = "muse-companion/v1"

@Serializable
internal data class MusePairingChallengeRequest(
    val contract: String = MUSE_COMPANION_CONTRACT,
    val publicKeySpki: String,
    val deviceName: String,
    val appVersion: String,
    val platform: String = "android",
)

@Serializable
internal data class MusePairingChallengeResponse(
    val contract: String,
    val challengeId: String,
    val challengeNonce: String,
    val challengeExpiresAt: String,
    val requestDigest: String,
    val publicKeyFingerprint: String,
    val signatureAlgorithm: String,
    val signatureEncoding: String,
)

@Serializable
internal data class MusePairingSubmission(
    val contract: String = MUSE_COMPANION_CONTRACT,
    val publicKeySpki: String,
    val deviceName: String,
    val appVersion: String,
    val platform: String = "android",
    val challengeId: String,
    val challengeNonce: String,
    val challengeExpiresAt: String,
    val signature: String,
)

@Serializable
internal data class MusePairingStart(
    val contract: String,
    val pairingId: String,
    val pairingCode: String,
    val pairingSecret: String,
    val expiresAt: String,
)

@Serializable
internal data class MusePairingPollRequest(
    val contract: String = MUSE_COMPANION_CONTRACT,
    val pairingSecret: String,
)

@Serializable
internal data class MusePairingPendingResponse(
    val contract: String,
    val status: String,
)

@Serializable
internal data class MusePublicDevice(
    val id: String,
    val deviceName: String,
    val appVersion: String,
    val platform: String,
    val role: String,
    val status: String,
    val publicKeyFingerprint: String,
    val lastSeenAt: String? = null,
    val createdAt: String,
)

@Serializable
internal data class MusePublicGrant(
    val guildId: String,
    val scopes: List<String>,
    val status: String,
)

@Serializable
internal data class MusePairingPairedResponse(
    val contract: String,
    val status: String,
    val device: MusePublicDevice,
    val grant: MusePublicGrant,
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    // Optional per-device acquisition credential. Older Muse servers omit both
    // fields; ignoreUnknownKeys keeps this backward compatible.
    val acquisitionToken: String? = null,
    val acquisitionEndpoint: String? = null,
)

internal sealed interface MusePairingPollResult {
    data object Pending : MusePairingPollResult
    data object Expired : MusePairingPollResult
    data class Paired(val response: MusePairingPairedResponse) : MusePairingPollResult
}

@Serializable
internal data class MuseRefreshRequest(
    val contract: String = MUSE_COMPANION_CONTRACT,
    val deviceId: String,
    val refreshToken: String,
    val idempotencyKey: String,
)

@Serializable
internal data class MuseTokenPair(
    val contract: String,
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
)

@Serializable
internal data class MuseSelfRevokeRequest(
    val contract: String = MUSE_COMPANION_CONTRACT,
)

@Serializable
internal data class MuseSelfRevokeResponse(
    val contract: String,
    val status: String,
)

@Serializable
internal data class MusePendingPairing(
    val pairingId: String,
    val pairingCode: String,
    val pairingSecret: String,
    val expiresAt: String,
)

@Serializable
internal data class MusePairedCredential(
    val device: MusePublicDevice,
    val grant: MusePublicGrant,
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
)

@Serializable
internal data class MuseStoredState(
    val endpoint: String? = null,
    val pendingPairing: MusePendingPairing? = null,
    val credential: MusePairedCredential? = null,
    val playbackTarget: MusePlaybackTarget = MusePlaybackTarget.DEVICE,
    val spotifyImportIdentity: String? = null,
    val spotifyAcknowledgedGeneration: Int = 0,
    val spotifyLastManifestFingerprint: String? = null,
)

@Serializable
internal data class MuseVoiceChannel(
    val id: String,
    val name: String,
)

@Serializable
internal data class MuseGuild(
    val id: String,
    val name: String,
    val voiceChannel: MuseVoiceChannel? = null,
)

@Serializable
internal data class MusePermissions(
    val canRead: Boolean,
    val canControl: Boolean,
    val canWriteQueue: Boolean,
    val manageGuild: Boolean,
)

@Serializable
internal data class MuseDjMode(
    val active: Boolean = false,
    val djUserId: String? = null,
)

@Serializable
internal data class MuseSong(
    val entryId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val requestedBy: String? = null,
    val thumbnailUrl: String? = null,
    val source: String,
    val quality: JsonElement? = null,
) {
    val qualityLabel: String?
        get() = when (quality) {
            is JsonPrimitive -> quality.content.takeIf { it.isNotBlank() && it != "null" }
            is JsonObject -> quality.measuredQualityLabel()
            null -> null
            else -> null
        }

    private fun JsonObject.measuredQualityLabel(): String? {
        val codec = (get("codec") as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.matches(Regex("^[A-Za-z0-9._+-]{1,24}$")) }
            ?.uppercase()
        val bitrate = (get("bitrateKbps") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        val sampleRate = (get("sampleRateHz") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        val bits = (get("bitsPerSample") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        val channels = (get("channels") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        return buildList {
            codec?.let(::add)
            when {
                bits != null && sampleRate != null -> add("$bits Bit/${sampleRate.qualityKHz()} kHz")
                bits != null -> add("$bits Bit")
                sampleRate != null -> add("${sampleRate.qualityKHz()} kHz")
            }
            channels?.let { add("$it Kanäle") }
            bitrate?.let { add("$it kbit/s") }
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun Int.qualityKHz(): String = if (this % 1_000 == 0) {
        (this / 1_000).toString()
    } else {
        "%.1f".format(java.util.Locale.ROOT, this / 1_000.0).replace('.', ',')
    }
}

@Serializable
internal data class MusePlayerState(
    val phase: String,
    val status: String,
    val positionSeconds: Int,
    val volume: Int,
    val repeatSong: Boolean,
    val repeatQueue: Boolean,
    val playerRevision: Long,
    val queueRevision: Long,
    val current: MuseSong? = null,
    val queue: List<MuseSong> = emptyList(),
    val queueLength: Int,
    val queueRemainingSeconds: Int,
)

@Serializable
internal data class MusePlayerResponse(
    val contract: String,
    val target: String,
    val guild: MuseGuild,
    val permissions: MusePermissions,
    val djMode: MuseDjMode = MuseDjMode(),
    val player: MusePlayerState,
)

@Serializable
internal data class MuseActionEnvelope(
    val contract: String = MUSE_COMPANION_CONTRACT,
    val action: String,
    val idempotencyKey: String,
    val expectedPlayerRevision: Long? = null,
    val expectedQueueRevision: Long? = null,
    val arguments: JsonObject? = null,
)

@Serializable
internal data class MuseErrorResponse(
    val error: String,
)

internal data class MuseRemoteAction(
    val wireName: String,
    val arguments: JsonObject? = null,
    val needsPlayerRevision: Boolean,
    val needsQueueRevision: Boolean,
) {
    companion object {
        fun pause() = player("pause")
        fun resume() = player("resume")
        fun seek(positionSeconds: Int) = player(
            "seek",
            JsonObject(mapOf("positionSeconds" to JsonPrimitive(positionSeconds))),
        )

        fun seekRelative(deltaSeconds: Int) = player(
            "seekRelative",
            JsonObject(mapOf("deltaSeconds" to JsonPrimitive(deltaSeconds))),
        )

        fun previous() = both("previous")
        fun next() = both("next")
        fun stop() = player("stop")
        fun setVolume(volume: Int) = player(
            "setVolume",
            JsonObject(mapOf("volume" to JsonPrimitive(volume))),
        )

        fun repeatSong(enabled: Boolean) = player(if (enabled) "repeatSongEnable" else "repeatSongDisable")
        fun repeatQueue(enabled: Boolean) = player(if (enabled) "repeatQueueEnable" else "repeatQueueDisable")
        fun autoplay(enabled: Boolean) = player(if (enabled) "autoplayEnable" else "autoplayDisable")
        fun shuffle() = queue("shuffle")
        fun clearQueue() = queue("clearQueue")
        fun undoQueueChange() = queue("undoQueueChange")
        fun remove(entryIds: List<String>) = queue(
            "removeQueueEntries",
            JsonObject(mapOf("entryIds" to kotlinx.serialization.json.JsonArray(entryIds.map(::JsonPrimitive)))),
        )

        fun move(entryId: String, oneBasedPosition: Int) = queue(
            "moveQueueEntry",
            JsonObject(
                mapOf(
                    "entryId" to JsonPrimitive(entryId),
                    "to" to JsonPrimitive(oneBasedPosition),
                ),
            ),
        )

        private fun player(name: String, arguments: JsonObject? = null) =
            MuseRemoteAction(name, arguments, needsPlayerRevision = true, needsQueueRevision = false)

        private fun queue(name: String, arguments: JsonObject? = null) =
            MuseRemoteAction(name, arguments, needsPlayerRevision = false, needsQueueRevision = true)

        private fun both(name: String) =
            MuseRemoteAction(name, needsPlayerRevision = true, needsQueueRevision = true)
    }
}

internal enum class MusePlaybackTarget {
    @SerialName("device") DEVICE,
    @SerialName("discord") DISCORD,
}

internal enum class MuseSection {
    PLAYER,
    QUEUE,
    DEVICES,
}

@Serializable
internal data class MuseManifestImportReport(
    val acceptedPlaylists: Int,
    val acceptedItems: Int,
    val skippedEntries: Int = 0,
)

@Serializable
internal data class MuseManifestTrack(
    val trackUri: String? = null,
    val isrc: String? = null,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Int,
)

@Serializable
internal data class MuseManifestPlaylist(
    val playlistId: String,
    val name: String,
    val playlistFingerprint: String,
    val items: List<MuseManifestTrack>,
)

@Serializable
internal data class MuseManifestFingerprintContent(
    val source: String = "file_import",
    val accountFingerprint: String,
    val generation: Int,
    val importReport: MuseManifestImportReport,
    val playlists: List<MuseManifestPlaylist>,
)

@Serializable
internal data class MuseManifestUpload(
    val contract: String = MUSE_COMPANION_CONTRACT,
    val idempotencyKey: String,
    val source: String = "file_import",
    val accountFingerprint: String,
    val generation: Int,
    val manifestFingerprint: String,
    val importReport: MuseManifestImportReport,
    val playlists: List<MuseManifestPlaylist>,
)

internal data class MusePreparedManifest(
    val uploadTemplate: MuseManifestUpload,
    val byteSize: Int,
    val formatLabel: String,
)

@Serializable
internal data class MuseSpotifyImportProfile(
    val source: String,
    val accountFingerprint: String,
    val sourceDeviceId: String,
    val generation: Int,
    val manifestFingerprint: String,
    val contentFingerprint: String,
    val playlistCount: Int,
    val itemCount: Int,
    val importedAt: String,
)

@Serializable
internal data class MuseSpotifyImportStatus(
    val contract: String,
    val profile: MuseSpotifyImportProfile? = null,
)

@Serializable
internal data class MuseSpotifyImportApplyResult(
    val contract: String,
    val applied: Boolean,
    val profile: MuseSpotifyImportProfile,
)

@Serializable
internal data class MuseSpotifyImportDisconnectRequest(
    val contract: String = MUSE_COMPANION_CONTRACT,
    val idempotencyKey: String,
)

@Serializable
internal data class MuseSpotifyImportDisconnectResult(
    val contract: String,
    val deleted: Boolean,
)
