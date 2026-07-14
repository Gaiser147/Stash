package com.stash.feature.muse

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.auth.crypto.TinkEncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.museCompanionDataStore by preferencesDataStore(
    name = "muse_companion_credentials",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Persists all Companion bearer material as one authenticated-encrypted blob. */
@Singleton
internal class MuseCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryption: TinkEncryptionManager,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val state: Flow<MuseStoredState> = context.museCompanionDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[STATE]?.let(::decrypt) ?: MuseStoredState()
        }

    suspend fun current(): MuseStoredState = state.first()

    suspend fun setEndpoint(endpoint: String) {
        update { previous ->
            if (previous.endpoint == endpoint) previous
            else MuseStoredState(endpoint = endpoint, playbackTarget = previous.playbackTarget)
        }
    }

    suspend fun savePending(endpoint: String, pairing: MusePairingStart) {
        update { previous ->
            previous.copy(
                endpoint = endpoint,
                credential = null,
                pendingPairing = MusePendingPairing(
                    pairingId = pairing.pairingId,
                    pairingCode = pairing.pairingCode,
                    pairingSecret = pairing.pairingSecret,
                    expiresAt = pairing.expiresAt,
                ),
            )
        }
    }

    suspend fun savePaired(endpoint: String, response: MusePairingPairedResponse) {
        update { previous ->
            previous.copy(
                endpoint = endpoint,
                pendingPairing = null,
                credential = MusePairedCredential(
                    device = response.device,
                    grant = response.grant,
                    accessToken = response.accessToken,
                    accessTokenExpiresAt = response.accessTokenExpiresAt,
                    refreshToken = response.refreshToken,
                    refreshTokenExpiresAt = response.refreshTokenExpiresAt,
                ),
                playbackTarget = MusePlaybackTarget.DISCORD,
            )
        }
    }

    suspend fun rotateTokens(deviceId: String, pair: MuseTokenPair): MusePairedCredential {
        var rotated: MusePairedCredential? = null
        update { previous ->
            val current = previous.credential
                ?.takeIf { it.device.id == deviceId }
                ?: error("Muse credential changed while refreshing")
            current.copy(
                accessToken = pair.accessToken,
                accessTokenExpiresAt = pair.accessTokenExpiresAt,
                refreshToken = pair.refreshToken,
                refreshTokenExpiresAt = pair.refreshTokenExpiresAt,
            ).also { next -> rotated = next }.let { next -> previous.copy(credential = next) }
        }
        return checkNotNull(rotated)
    }

    suspend fun setPlaybackTarget(target: MusePlaybackTarget) {
        update { it.copy(playbackTarget = target) }
    }

    suspend fun createSpotifyImportIdentity(): String {
        var identity: String? = null
        update { previous ->
            val value = previous.spotifyImportIdentity ?: ByteArray(32)
                .also(java.security.SecureRandom()::nextBytes)
                .sha256Hex()
            identity = value
            previous.copy(spotifyImportIdentity = value)
        }
        return checkNotNull(identity)
    }

    suspend fun acknowledgeSpotifyImport(generation: Int, manifestFingerprint: String) {
        update { previous ->
            check(generation >= previous.spotifyAcknowledgedGeneration) {
                "Spotify import generation went backwards"
            }
            if (generation == previous.spotifyAcknowledgedGeneration) {
                check(previous.spotifyLastManifestFingerprint == manifestFingerprint) {
                    "Spotify import generation conflicts with the acknowledged manifest"
                }
                previous
            } else {
                previous.copy(
                    spotifyAcknowledgedGeneration = generation,
                    spotifyLastManifestFingerprint = manifestFingerprint,
                )
            }
        }
    }

    suspend fun clearSpotifyImportProfile() {
        update {
            it.copy(
                spotifyImportIdentity = null,
                spotifyAcknowledgedGeneration = 0,
                spotifyLastManifestFingerprint = null,
            )
        }
    }

    suspend fun clearPending() {
        update { it.copy(pendingPairing = null) }
    }

    suspend fun clearLocalConnection() {
        update { MuseStoredState(endpoint = it.endpoint, playbackTarget = MusePlaybackTarget.DEVICE) }
    }

    private suspend fun update(transform: (MuseStoredState) -> MuseStoredState) {
        context.museCompanionDataStore.edit { preferences ->
            val current = preferences[STATE]?.let(::decrypt) ?: MuseStoredState()
            preferences[STATE] = encrypt(transform(current))
        }
    }

    private fun encrypt(state: MuseStoredState): String {
        val plaintext = json.encodeToString(MuseStoredState.serializer(), state).encodeToByteArray()
        return try {
            Base64.getEncoder().encodeToString(encryption.encrypt(plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decrypt(value: String): MuseStoredState = runCatching {
        val plaintext = encryption.decrypt(Base64.getDecoder().decode(value))
        try {
            json.decodeFromString(MuseStoredState.serializer(), plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }.getOrElse { MuseStoredState() }

    private companion object {
        val STATE = stringPreferencesKey("encrypted_state_v1")
    }
}
