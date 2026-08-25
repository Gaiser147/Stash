package com.stash.feature.muse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.stash.core.common.constants.StashConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.math.min

internal enum class MuseConnectionStage {
    UNCONFIGURED,
    READY_TO_PAIR,
    PAIRING,
    PAIRED,
}

internal data class MusePairingUi(
    val code: String,
    val expiresAt: String,
)

internal data class MuseDeviceUi(
    val id: String,
    val name: String,
    val role: String,
    val fingerprint: String,
    val guildId: String,
    val scopes: List<String>,
)

internal data class MuseImportPreviewUi(
    val format: String,
    val playlistCount: Int,
    val itemCount: Int,
    val generation: Int,
    val byteSize: Int,
)

internal data class MuseUiState(
    val endpointDraft: String = "",
    val endpoint: String? = null,
    val connectionStage: MuseConnectionStage = MuseConnectionStage.UNCONFIGURED,
    val pairing: MusePairingUi? = null,
    val device: MuseDeviceUi? = null,
    val playbackTarget: MusePlaybackTarget = MusePlaybackTarget.DEVICE,
    val section: MuseSection = MuseSection.PLAYER,
    val snapshot: MusePlayerResponse? = null,
    val eventStreamConnected: Boolean = false,
    val busy: Boolean = false,
    val actionBusy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val spotifyImportIdentityCreated: Boolean = false,
    val spotifyAcknowledgedGeneration: Int = 0,
    val spotifyImportEnabledOnServer: Boolean? = null,
    val spotifyImportProfile: MuseSpotifyImportProfile? = null,
    val importPreview: MuseImportPreviewUi? = null,
    val importBusy: Boolean = false,
)

@HiltViewModel
internal class MuseViewModel @Inject constructor(
    private val repository: MuseCompanionRepository,
    private val documentReader: MuseDocumentReader,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MuseUiState())
    val state: StateFlow<MuseUiState> = mutableState.asStateFlow()
    private val actionMutex = Mutex()
    private var foregroundJob: Job? = null
    private var pairingJob: Job? = null
    private var spotifyStatusJob: Job? = null
    private var preparedImport: MusePreparedManifest? = null
    private var visible = false
    private var defaultEndpointAttempted = false

    init {
        viewModelScope.launch {
            repository.storedState.collectLatest { stored ->
                mutableState.update { previous -> previous.fromStored(stored) }
                autoConfigureDefaultEndpoint(stored)
                restartForegroundIfNeeded()
                restartPairingPollIfNeeded()
                refreshSpotifyImportStatusIfNeeded()
            }
        }
    }

    /**
     * Persist the build's default endpoint on first launch so a fresh install
     * lands directly in READY_TO_PAIR — one tap on "Koppeln" instead of the
     * save-then-pair two-step that made pairing look broken.
     */
    private fun autoConfigureDefaultEndpoint(stored: MuseStoredState) {
        if (defaultEndpointAttempted ||
            stored.endpoint != null ||
            stored.credential != null ||
            StashConstants.MUSE_DEFAULT_ENDPOINT.isBlank()
        ) {
            return
        }
        defaultEndpointAttempted = true
        viewModelScope.launch {
            runCatching { repository.configureEndpoint(StashConstants.MUSE_DEFAULT_ENDPOINT) }
        }
    }

    fun onVisible() {
        visible = true
        restartForegroundIfNeeded()
        restartPairingPollIfNeeded()
        refreshSpotifyImportStatusIfNeeded()
    }

    fun onHidden() {
        visible = false
        foregroundJob?.cancel()
        pairingJob?.cancel()
        spotifyStatusJob?.cancel()
        mutableState.update { it.copy(eventStreamConnected = false) }
    }

    fun onEndpointChanged(value: String) {
        mutableState.update { it.copy(endpointDraft = value, error = null, message = null) }
    }

    fun saveEndpoint() = runBusy {
        repository.configureEndpoint(state.value.endpointDraft)
        mutableState.update { it.copy(message = "Muse-Adresse gespeichert.") }
    }

    fun startPairing() = runBusy {
        // Self-healing: a typed-but-unsaved address must never block pairing.
        if (state.value.endpoint == null && state.value.endpointDraft.isNotBlank()) {
            repository.configureEndpoint(state.value.endpointDraft)
        }
        repository.createPairing()
        mutableState.update {
            it.copy(message = "Code in Discord unter /music → Geräte bestätigen.")
        }
    }

    fun cancelPairing() = runBusy {
        repository.cancelPendingPairing()
        mutableState.update { it.copy(message = "Kopplung lokal abgebrochen.") }
    }

    fun disconnectLocally() = runBusy {
        foregroundJob?.cancel()
        repository.revokeAndDisconnect()
        preparedImport = null
        mutableState.update {
            it.copy(
                snapshot = null,
                eventStreamConnected = false,
                message = "Gerät in Muse widerrufen; lokale Zugangsdaten und Schlüssel wurden gelöscht.",
            )
        }
    }

    fun setPlaybackTarget(target: MusePlaybackTarget) {
        viewModelScope.launch {
            repository.setPlaybackTarget(target)
            if (target == MusePlaybackTarget.DISCORD) restartForegroundIfNeeded()
            else foregroundJob?.cancel()
        }
    }

    fun setSection(section: MuseSection) {
        mutableState.update { it.copy(section = section) }
    }

    fun reconcileNow() {
        if (state.value.connectionStage != MuseConnectionStage.PAIRED) return
        viewModelScope.launch {
            runCatching { repository.reconcile() }
                .onSuccess { snapshot ->
                    mutableState.update {
                        it.copy(snapshot = snapshot, eventStreamConnected = true, error = null)
                    }
                }
                .onFailure { error -> mutableState.update { it.copy(error = error.toUserMessage()) } }
        }
    }

    fun togglePause() {
        val snapshot = state.value.snapshot ?: return
        val paused = snapshot.player.phase == "paused" || snapshot.player.status == "paused"
        perform(if (paused) MuseRemoteAction.resume() else MuseRemoteAction.pause())
    }

    fun previous() = perform(MuseRemoteAction.previous())
    fun next() = perform(MuseRemoteAction.next())
    fun seekRelative(seconds: Int) = perform(MuseRemoteAction.seekRelative(seconds.coerceIn(-3600, 3600)))
    fun seekTo(seconds: Int) = perform(MuseRemoteAction.seek(seconds.coerceAtLeast(0)))
    fun setVolume(volume: Int) = perform(MuseRemoteAction.setVolume(volume.coerceIn(0, 100)))
    fun stop() = perform(MuseRemoteAction.stop())
    fun toggleRepeatSong() = state.value.snapshot?.let { perform(MuseRemoteAction.repeatSong(!it.player.repeatSong)) }
    fun toggleRepeatQueue() = state.value.snapshot?.let { perform(MuseRemoteAction.repeatQueue(!it.player.repeatQueue)) }
    fun toggleAutoplay() = state.value.snapshot?.let { perform(MuseRemoteAction.autoplay(!it.player.autoplay.active)) }
    fun shuffle() = perform(MuseRemoteAction.shuffle())
    fun clearQueue() = perform(MuseRemoteAction.clearQueue())
    fun undoQueueChange() = perform(MuseRemoteAction.undoQueueChange())
    fun removeQueueEntry(entryId: String) = perform(MuseRemoteAction.remove(listOf(entryId)))
    fun moveQueueEntry(entryId: String, oneBasedPosition: Int) =
        perform(MuseRemoteAction.move(entryId, oneBasedPosition.coerceIn(1, 10_000)))

    fun clearNotice() {
        mutableState.update { it.copy(message = null, error = null) }
    }

    fun createSpotifyImportIdentity() = runBusy {
        repository.createSpotifyImportIdentity()
        mutableState.update {
            it.copy(message = "Privates Importprofil wurde nur auf diesem Gerät angelegt.")
        }
    }

    fun onSpotifyDocumentSelected(uri: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(importBusy = true, error = null, message = null, importPreview = null) }
            try {
                val selected = documentReader.read(uri)
                val prepared = repository.prepareSpotifyImport(selected)
                preparedImport = prepared
                mutableState.update {
                    it.copy(
                        importPreview = MuseImportPreviewUi(
                            format = prepared.formatLabel,
                            playlistCount = prepared.uploadTemplate.importReport.acceptedPlaylists,
                            itemCount = prepared.uploadTemplate.importReport.acceptedItems,
                            generation = prepared.uploadTemplate.generation,
                            byteSize = prepared.byteSize,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                preparedImport = null
                mutableState.update { it.copy(error = error.toUserMessage()) }
            } finally {
                mutableState.update { it.copy(importBusy = false) }
            }
        }
    }

    fun cancelSpotifyImportPreview() {
        preparedImport = null
        mutableState.update { it.copy(importPreview = null) }
    }

    fun confirmSpotifyImport() {
        val prepared = preparedImport ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(importBusy = true, error = null, message = null) }
            try {
                val result = repository.uploadSpotifyImport(prepared)
                preparedImport = null
                mutableState.update {
                    it.copy(
                        importPreview = null,
                        spotifyImportEnabledOnServer = true,
                        spotifyImportProfile = result.profile,
                        message = if (result.applied) {
                            "Private Playlistgeneration ${result.profile.generation} wurde atomar übernommen."
                        } else {
                            "Diese Generation war bereits vollständig in Muse vorhanden."
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Keep normalized preview for an explicit retry. Raw file bytes
                // were already zeroed by the adapter and are never retained.
                mutableState.update { it.copy(error = error.toUserMessage()) }
            } finally {
                mutableState.update { it.copy(importBusy = false) }
            }
        }
    }

    fun disconnectSpotifyImport() {
        viewModelScope.launch {
            mutableState.update { it.copy(importBusy = true, error = null, message = null) }
            try {
                repository.disconnectSpotifyImport()
                preparedImport = null
                mutableState.update {
                    it.copy(
                        importPreview = null,
                        spotifyImportProfile = null,
                        message = "Privates Muse-Importprofil und lokale Profil-ID wurden gelöscht.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(error = error.toUserMessage()) }
            } finally {
                mutableState.update { it.copy(importBusy = false) }
            }
        }
    }

    private fun perform(action: MuseRemoteAction) {
        val current = state.value
        val snapshot = current.snapshot ?: return
        val permitted = if (action.needsQueueRevision && !action.needsPlayerRevision) {
            snapshot.permissions.canWriteQueue
        } else {
            snapshot.permissions.canControl
        }
        if (!permitted || current.playbackTarget != MusePlaybackTarget.DISCORD) return
        viewModelScope.launch {
            actionMutex.withLock {
                mutableState.update { it.copy(actionBusy = true, error = null) }
                try {
                    val latest = state.value.snapshot ?: return@withLock
                    val result = repository.performAction(action, latest)
                    mutableState.update { it.copy(snapshot = result, eventStreamConnected = true) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // Never blindly replay a possibly-applied mutation. Reconcile
                    // state and let the user decide whether to issue a new action.
                    val reconciled = runCatching { repository.reconcile() }.getOrNull()
                    mutableState.update {
                        it.copy(snapshot = reconciled ?: it.snapshot, error = error.toUserMessage())
                    }
                } finally {
                    mutableState.update { it.copy(actionBusy = false) }
                }
            }
        }
    }

    private fun restartForegroundIfNeeded() {
        foregroundJob?.cancel()
        val current = state.value
        if (!visible || current.connectionStage != MuseConnectionStage.PAIRED ||
            current.playbackTarget != MusePlaybackTarget.DISCORD
        ) {
            mutableState.update { it.copy(eventStreamConnected = false) }
            return
        }
        foregroundJob = viewModelScope.launch {
            var backoffMillis = 1_000L
            while (isActive) {
                try {
                    repository.foregroundCycle().collect { snapshot ->
                        backoffMillis = 1_000L
                        mutableState.update {
                            it.copy(snapshot = snapshot, eventStreamConnected = true, error = null)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    mutableState.update {
                        it.copy(eventStreamConnected = false, error = error.toUserMessage())
                    }
                    delay(backoffMillis)
                    backoffMillis = min(backoffMillis * 2, 15_000L)
                }
            }
        }
    }

    private fun restartPairingPollIfNeeded() {
        pairingJob?.cancel()
        if (!visible || state.value.connectionStage != MuseConnectionStage.PAIRING) return
        pairingJob = viewModelScope.launch {
            while (isActive && state.value.connectionStage == MuseConnectionStage.PAIRING) {
                try {
                    when (repository.pollPairing()) {
                        MusePairingPollResult.Pending -> delay(PAIRING_POLL_INTERVAL_MS)
                        MusePairingPollResult.Expired -> {
                            mutableState.update { it.copy(error = "Der Kopplungscode ist abgelaufen.") }
                            return@launch
                        }
                        is MusePairingPollResult.Paired -> {
                            mutableState.update { it.copy(message = pairedMessage()) }
                            return@launch
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MuseApiException) {
                    mutableState.update { it.copy(error = error.toUserMessage()) }
                    delay((error.retryAfterSeconds?.times(1_000) ?: PAIRING_POLL_INTERVAL_MS).coerceAtLeast(1_000))
                } catch (error: Throwable) {
                    mutableState.update { it.copy(error = error.toUserMessage()) }
                    delay(PAIRING_POLL_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Report whether the request inbox configured itself from the pairing. A
     * failed adoption used to be invisible, leaving the user to wonder why
     * uploads never started.
     */
    private fun pairedMessage(): String = when (val adoption = repository.lastAcquisitionAdoption) {
        MuseAcquisitionAdoption.Adopted ->
            "Muse-Gerät gekoppelt. Die Anfragen-Verbindung wurde automatisch eingerichtet."
        is MuseAcquisitionAdoption.Failed ->
            "Muse-Gerät gekoppelt, aber die Anfragen-Verbindung konnte nicht automatisch " +
                "eingerichtet werden (${adoption.reason}). Trage sie unter Einstellungen → " +
                "Konten & Sync → Muse-Anfragen manuell ein."
        MuseAcquisitionAdoption.None -> "Muse-Gerät erfolgreich gekoppelt."
    }

    private fun refreshSpotifyImportStatusIfNeeded() {
        spotifyStatusJob?.cancel()
        val current = state.value
        if (!visible || current.connectionStage != MuseConnectionStage.PAIRED ||
            current.device?.scopes?.contains("spotify:manifest") != true
        ) {
            if (current.connectionStage != MuseConnectionStage.PAIRED) {
                mutableState.update {
                    it.copy(spotifyImportEnabledOnServer = null, spotifyImportProfile = null)
                }
            }
            return
        }
        spotifyStatusJob = viewModelScope.launch {
            try {
                val status = repository.spotifyImportStatus()
                mutableState.update {
                    it.copy(
                        spotifyImportEnabledOnServer = true,
                        spotifyImportProfile = status.profile,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: MuseApiException) {
                if (error.statusCode == 404) {
                    mutableState.update {
                        it.copy(spotifyImportEnabledOnServer = false, spotifyImportProfile = null)
                    }
                } else {
                    mutableState.update { it.copy(error = error.toUserMessage()) }
                }
            } catch (error: Throwable) {
                mutableState.update { it.copy(error = error.toUserMessage()) }
            }
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, message = null) }
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(error = error.toUserMessage()) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun MuseUiState.fromStored(stored: MuseStoredState): MuseUiState {
        val stage = when {
            stored.credential != null -> MuseConnectionStage.PAIRED
            stored.pendingPairing != null -> MuseConnectionStage.PAIRING
            stored.endpoint != null -> MuseConnectionStage.READY_TO_PAIR
            else -> MuseConnectionStage.UNCONFIGURED
        }
        return copy(
            // Prefill the connect field: stored endpoint first, otherwise the
            // build's default so a fresh install only taps "Koppeln".
            endpointDraft = endpointDraft
                .ifBlank { stored.endpoint.orEmpty() }
                .ifBlank { StashConstants.MUSE_DEFAULT_ENDPOINT },
            endpoint = stored.endpoint,
            connectionStage = stage,
            pairing = stored.pendingPairing?.let { MusePairingUi(it.pairingCode, it.expiresAt) },
            device = stored.credential?.let {
                MuseDeviceUi(
                    id = it.device.id,
                    name = it.device.deviceName,
                    role = it.device.role,
                    fingerprint = it.device.publicKeyFingerprint,
                    guildId = it.grant.guildId,
                    scopes = it.grant.scopes,
                )
            },
            playbackTarget = stored.playbackTarget,
            spotifyImportIdentityCreated = stored.spotifyImportIdentity != null,
            spotifyAcknowledgedGeneration = stored.spotifyAcknowledgedGeneration,
        )
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is MuseApiException -> when (code) {
            "stale_revision" -> "Die Ansicht war veraltet und wurde neu geladen. Bitte Aktion erneut wählen."
            "active_voice_membership_required" -> "Du musst im aktiven Discord-Sprachkanal sein."
            "dj_control_required" -> "Diese Sitzung wird von einem DJ gesteuert."
            "scope_denied", "guild_access_denied" -> "Dem Gerät fehlt die erforderliche Berechtigung."
            "remote_control_disabled" -> "Muse-Fernsteuerung ist serverseitig noch deaktiviert."
            "invalid_access_token", "invalid_refresh_credential" ->
                "Die Muse-Geräteanmeldung ist ungültig. Bitte in Discord widerrufen und neu koppeln."
            "pairing_rate_limited" -> "Zu viele Kopplungsversuche. Bitte kurz warten."
            "event_stream_closed" -> "Muse-Verbindung wird neu aufgebaut …"
            "partial_import_not_allowed" -> "Muse akzeptiert keine unvollständige Playlistgeneration."
            "too_many_playlists", "too_many_items", "playlist_too_large", "body_too_large" ->
                "Das vollständige Manifest überschreitet ein Serverlimit; die letzte gute Generation bleibt erhalten."
            "account_mismatch" ->
                "Muse ist bereits an ein anderes privates Importprofil gebunden. Bitte das alte Profil zuerst trennen."
            "source_device_mismatch" ->
                "Ein anderes gekoppeltes Gerät ist derzeit die Manifest-Sync-Quelle."
            "stale_generation", "generation_conflict" ->
                "Die lokale Importgeneration kollidiert mit Muse. Bitte Status neu laden oder das Profil trennen."
            "disabled", "not_found" -> "Privater Playlistimport ist auf Muse noch deaktiviert."
            else -> "Muse-Anfrage fehlgeschlagen ($code)."
        }
        is IllegalArgumentException, is IllegalStateException -> message ?: "Ungültige Muse-Einstellung."
        else -> "Muse ist momentan nicht erreichbar."
    }

    private companion object {
        const val PAIRING_POLL_INTERVAL_MS = 5_000L
    }
}
