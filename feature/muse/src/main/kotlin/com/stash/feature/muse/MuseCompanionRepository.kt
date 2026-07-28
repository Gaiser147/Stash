package com.stash.feature.muse

import android.content.Context
import android.os.Build
import com.stash.core.common.AcquisitionTokenSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class MuseCompanionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MuseCompanionApi,
    private val credentials: MuseCredentialStore,
    private val identity: MuseDeviceIdentity,
    private val manifestAdapter: MusePrivateManifestAdapter,
    private val acquisitionTokenSink: AcquisitionTokenSink,
) {
    private val refreshMutex = Mutex()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /**
     * Outcome of the most recent acquisition-credential adoption, so the Muse
     * screen can tell the user whether the request inbox configured itself or
     * still needs manual entry. Process-local and intentionally not persisted.
     */
    @Volatile
    var lastAcquisitionAdoption: MuseAcquisitionAdoption = MuseAcquisitionAdoption.None
        private set

    val storedState: Flow<MuseStoredState> = credentials.state

    fun publicIdentity(): MuseDevicePublicIdentity = identity.publicIdentity()

    suspend fun configureEndpoint(rawEndpoint: String) {
        val endpoint = MuseEndpoint.normalize(rawEndpoint)
        val current = credentials.current()
        require(current.credential == null || current.endpoint == endpoint) {
            "Vor einem Serverwechsel muss das Gerät lokal getrennt werden."
        }
        credentials.setEndpoint(endpoint)
    }

    suspend fun createPairing(): MusePairingStart {
        val state = credentials.current()
        check(state.credential == null) { "Dieses Gerät ist bereits gekoppelt." }
        val endpoint = state.endpoint ?: error("Bitte zuerst die Muse-Adresse speichern.")
        val capabilities = api.capabilities(endpoint)
        check(capabilities.contract == MUSE_COMPANION_CONTRACT && "pairing_challenge" in capabilities.capabilities) {
            "Der Server unterstützt die Muse-Companion-Kopplung nicht."
        }
        val publicIdentity = identity.publicIdentity()
        val deviceName = deviceName()
        val appVersion = appVersion()
        val challenge = api.createPairingChallenge(
            endpoint,
            MusePairingChallengeRequest(
                publicKeySpki = publicIdentity.publicKeySpki,
                deviceName = deviceName,
                appVersion = appVersion,
            ),
        )
        validatePairingChallenge(challenge, publicIdentity, deviceName, appVersion)
        val result = api.createPairing(
            endpoint,
            MusePairingSubmission(
                publicKeySpki = publicIdentity.publicKeySpki,
                deviceName = deviceName,
                appVersion = appVersion,
                challengeId = challenge.challengeId,
                challengeNonce = challenge.challengeNonce,
                challengeExpiresAt = challenge.challengeExpiresAt,
                signature = identity.sign(MusePairingCanonicalizer.proofMessage(challenge)),
            ),
        )
        check(result.contract == MUSE_COMPANION_CONTRACT && result.pairingCode.matches(Regex("^\\d{8}$"))) {
            "Muse hat eine ungültige Kopplungsantwort geliefert."
        }
        credentials.savePending(endpoint, result)
        return result
    }

    suspend fun pollPairing(): MusePairingPollResult {
        val state = credentials.current()
        val endpoint = state.endpoint ?: error("Muse-Adresse fehlt.")
        val pending = state.pendingPairing ?: error("Keine offene Kopplung vorhanden.")
        if (parseInstant(pending.expiresAt) <= Instant.now()) {
            credentials.clearPending()
            return MusePairingPollResult.Expired
        }
        return api.pollPairing(endpoint, pending.pairingId, pending.pairingSecret).also { result ->
            when (result) {
                MusePairingPollResult.Pending -> Unit
                MusePairingPollResult.Expired -> credentials.clearPending()
                is MusePairingPollResult.Paired -> {
                    validatePairedResponse(result.response)
                    // Adopt the acquisition credential BEFORE savePaired: that
                    // write flips the stored state to PAIRED, whose collector
                    // cancels this very job (see MuseViewModel.restartPairingPollIfNeeded).
                    // Running afterwards means the suspending sink call never
                    // completes, which silently left the request inbox unconfigured.
                    adoptAcquisitionToken(result.response)
                    credentials.savePaired(endpoint, result.response)
                }
            }
        }
    }

    /**
     * If Muse delivered a device-bound acquisition token in the pairing
     * response, hand it to the acquisition store so the request inbox works
     * without the user copying a token by hand.
     *
     * Runs inside [NonCancellable] so a cancellation racing the pairing
     * completion cannot abandon a half-adopted credential. A genuine failure
     * must not abort the pairing — the remote control still works without the
     * acquisition inbox — but it is reported instead of silently swallowed.
     */
    private suspend fun adoptAcquisitionToken(response: MusePairingPairedResponse) {
        val token = response.acquisitionToken?.trim().orEmpty()
        if (token.isEmpty()) {
            return
        }

        try {
            withContext(NonCancellable) {
                acquisitionTokenSink.acceptCompanionAcquisitionToken(response.acquisitionEndpoint, token)
            }
            lastAcquisitionAdoption = MuseAcquisitionAdoption.Adopted
        } catch (error: Throwable) {
            lastAcquisitionAdoption = MuseAcquisitionAdoption.Failed(
                error.message ?: error::class.simpleName.orEmpty(),
            )
        }
    }

    suspend fun searchLibrary(query: String): MuseLibraryResponse =
        withAuthorizedRetry { endpoint, credential ->
            api.library(endpoint, credential, listOf("search"), mapOf("q" to query))
        }

    suspend fun browseLibrary(segments: List<String>): MuseLibraryResponse =
        withAuthorizedRetry { endpoint, credential ->
            api.library(endpoint, credential, segments)
        }

    suspend fun reconcile(): MusePlayerResponse = withAuthorizedRetry { endpoint, credential ->
        api.player(endpoint, credential)
    }

    /** REST reconciliation is deliberately the first emission of every SSE connection cycle. */
    fun foregroundCycle(): Flow<MusePlayerResponse> = flow {
        emit(reconcile())
        val (endpoint, credential) = endpointAndCredential(ensureCredential())
        emitAll(api.playerEvents(endpoint, credential))
    }

    suspend fun performAction(
        action: MuseRemoteAction,
        snapshot: MusePlayerResponse,
    ): MusePlayerResponse {
        val envelope = MuseActionEnvelope(
            action = action.wireName,
            idempotencyKey = UUID.randomUUID().toString(),
            expectedPlayerRevision = snapshot.player.playerRevision.takeIf { action.needsPlayerRevision },
            expectedQueueRevision = snapshot.player.queueRevision.takeIf { action.needsQueueRevision },
            arguments = action.arguments,
        )
        return withAuthorizedRetry { endpoint, credential -> api.action(endpoint, credential, envelope) }
    }

    suspend fun setPlaybackTarget(target: MusePlaybackTarget) {
        credentials.setPlaybackTarget(target)
    }

    suspend fun cancelPendingPairing() {
        credentials.clearPending()
    }

    suspend fun createSpotifyImportIdentity(): String {
        val credential = credentials.current().credential ?: error("Muse-Gerät ist nicht gekoppelt.")
        check("spotify:manifest" in credential.grant.scopes) {
            "Dieses Gerät hat keinen Zugriff auf private Playlistmanifeste."
        }
        return credentials.createSpotifyImportIdentity()
    }

    suspend fun prepareSpotifyImport(selected: MuseSelectedDocument): MusePreparedManifest {
        try {
            val state = credentials.current()
            val credential = state.credential ?: error("Muse-Gerät ist nicht gekoppelt.")
            check("spotify:manifest" in credential.grant.scopes) {
                "Dieses Gerät hat keinen spotify:manifest-Scope."
            }
            val accountIdentity = state.spotifyImportIdentity
                ?: error("Bitte das private Importprofil zuerst ausdrücklich anlegen.")
            check(state.spotifyAcknowledgedGeneration < Int.MAX_VALUE) {
                "Die maximale Manifestgeneration ist erreicht. Bitte Importprofil trennen und neu anlegen."
            }
            return manifestAdapter.prepare(
                selected = selected,
                accountFingerprint = accountIdentity,
                generation = state.spotifyAcknowledgedGeneration + 1,
            )
        } finally {
            selected.bytes.fill(0)
        }
    }

    suspend fun uploadSpotifyImport(prepared: MusePreparedManifest): MuseSpotifyImportApplyResult {
        val idempotencyKey = UUID.randomUUID().toString()
        val rawBody = manifestAdapter.encodeForUpload(prepared, idempotencyKey)
        return try {
            val result = withAuthorizedRetry { endpoint, credential ->
                check("spotify:manifest" in credential.grant.scopes) {
                    "Dieses Gerät hat keinen spotify:manifest-Scope."
                }
                api.uploadSpotifyManifest(endpoint, credential, rawBody)
            }
            check(result.contract == MUSE_COMPANION_CONTRACT &&
                result.profile.generation == prepared.uploadTemplate.generation
            ) { "Muse hat eine ungültige Importbestätigung geliefert." }
            credentials.acknowledgeSpotifyImport(
                result.profile.generation,
                prepared.uploadTemplate.manifestFingerprint,
            )
            result
        } finally {
            rawBody.fill(0)
        }
    }

    suspend fun spotifyImportStatus(): MuseSpotifyImportStatus = withAuthorizedRetry { endpoint, credential ->
        check("spotify:manifest" in credential.grant.scopes) {
            "Dieses Gerät hat keinen spotify:manifest-Scope."
        }
        api.spotifyImportStatus(endpoint, credential)
    }

    suspend fun disconnectSpotifyImport(): MuseSpotifyImportDisconnectResult {
        val request = MuseSpotifyImportDisconnectRequest(idempotencyKey = UUID.randomUUID().toString())
        val rawBody = json.encodeToString(
            MuseSpotifyImportDisconnectRequest.serializer(),
            request,
        ).encodeToByteArray()
        return try {
            val result = withAuthorizedRetry { endpoint, credential ->
                check("spotify:manifest" in credential.grant.scopes) {
                    "Dieses Gerät hat keinen spotify:manifest-Scope."
                }
                api.disconnectSpotifyImport(endpoint, credential, rawBody)
            }
            check(result.contract == MUSE_COMPANION_CONTRACT) { "Ungültige Muse-Trennungsantwort." }
            credentials.clearSpotifyImportProfile()
            result
        } finally {
            rawBody.fill(0)
        }
    }

    suspend fun revokeAndDisconnect() {
        val result = withAuthorizedRetry { endpoint, credential ->
            api.revokeSelf(endpoint, credential)
        }
        check(result.contract == MUSE_COMPANION_CONTRACT && result.status == "revoked") {
            "Muse hat den Gerätewiderruf nicht bestätigt."
        }
        credentials.clearLocalConnection()
        identity.deleteLocalIdentity()
    }

    private suspend fun <T> withAuthorizedRetry(
        block: suspend (String, MusePairedCredential) -> T,
    ): T {
        var credential = ensureCredential()
        val endpoint = credentials.current().endpoint ?: error("Muse-Adresse fehlt.")
        return try {
            block(endpoint, credential)
        } catch (error: MuseApiException) {
            if (error.statusCode != 401) throw error
            credential = ensureCredential(force = true)
            block(endpoint, credential)
        }
    }

    private suspend fun ensureCredential(force: Boolean = false): MusePairedCredential {
        val initial = credentials.current().credential ?: error("Muse-Gerät ist nicht gekoppelt.")
        if (!force && tokenIsUsable(initial)) return initial
        return refreshMutex.withLock {
            val state = credentials.current()
            val current = state.credential ?: error("Muse-Gerät ist nicht gekoppelt.")
            if (!force && tokenIsUsable(current)) return@withLock current
            check(parseInstant(current.refreshTokenExpiresAt) > Instant.now()) {
                "Die Muse-Geräteanmeldung ist abgelaufen. Bitte in Discord widerrufen und neu koppeln."
            }
            val endpoint = state.endpoint ?: error("Muse-Adresse fehlt.")
            val request = MuseRefreshRequest(
                deviceId = current.device.id,
                refreshToken = current.refreshToken,
                idempotencyKey = UUID.randomUUID().toString(),
            )
            val pair = try {
                api.refresh(endpoint, current, request)
            } catch (_: IOException) {
                // A lost response must reuse the exact JSON values so Muse can
                // redeliver the already-rotated credential instead of rotating twice.
                api.refresh(endpoint, current, request)
            }
            check(pair.contract == MUSE_COMPANION_CONTRACT) { "Ungültige Muse-Tokenantwort." }
            credentials.rotateTokens(current.device.id, pair)
        }
    }

    private suspend fun endpointAndCredential(credential: MusePairedCredential): Pair<String, MusePairedCredential> {
        val endpoint = credentials.current().endpoint ?: error("Muse-Adresse fehlt.")
        return endpoint to credential
    }

    private fun tokenIsUsable(credential: MusePairedCredential): Boolean =
        parseInstant(credential.accessTokenExpiresAt).isAfter(Instant.now().plusSeconds(90))

    private fun validatePairedResponse(response: MusePairingPairedResponse) {
        val expectedFingerprint = identity.publicIdentity().fingerprint
        check(response.contract == MUSE_COMPANION_CONTRACT && response.status == "paired") {
            "Ungültige Muse-Kopplungsantwort."
        }
        check(response.device.status == "active" && response.device.publicKeyFingerprint == expectedFingerprint) {
            "Muse hat die Kopplung nicht an diesen Geräteschlüssel gebunden."
        }
        check(response.grant.status == "active" && "player:read" in response.grant.scopes) {
            "Der Muse-Gerätezugriff enthält keine Leseberechtigung."
        }
        check(parseInstant(response.accessTokenExpiresAt) > Instant.now()) { "Muse-Zugriffstoken ist abgelaufen." }
        check(parseInstant(response.refreshTokenExpiresAt) > Instant.now()) { "Muse-Gerätecredential ist abgelaufen." }
    }

    private fun validatePairingChallenge(
        challenge: MusePairingChallengeResponse,
        publicIdentity: MuseDevicePublicIdentity,
        deviceName: String,
        appVersion: String,
    ) {
        val expectedDigest = MusePairingCanonicalizer.requestDigest(
            publicKeySpki = publicIdentity.publicKeySpki,
            publicKeyFingerprint = publicIdentity.fingerprint,
            deviceName = deviceName,
            appVersion = appVersion,
        )
        check(challenge.contract == MUSE_COMPANION_CONTRACT &&
            challenge.publicKeyFingerprint == publicIdentity.fingerprint &&
            challenge.requestDigest == expectedDigest &&
            challenge.signatureAlgorithm == "SHA256withECDSA" &&
            challenge.signatureEncoding == "ASN.1-DER/base64url" &&
            challenge.challengeId.matches(Regex("^[0-9a-f-]{36}$")) &&
            challenge.challengeNonce.matches(Regex("^[A-Za-z0-9_-]{32,96}$")) &&
            parseInstant(challenge.challengeExpiresAt) > Instant.now()
        ) { "Muse hat eine ungültige oder fremde Pairing-Challenge geliefert." }
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replace(Regex("[\\p{C}]"), "")
        .trim()
        .ifBlank { "Android-Gerät" }
        .take(64)

    @Suppress("DEPRECATION")
    private fun appVersion(): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        return version.replace(Regex("[^A-Za-z0-9._+\\-]"), "-")
            .trimStart { !it.isLetterOrDigit() }
            .ifBlank { "0" }
            .take(64)
    }

    private fun parseInstant(value: String): Instant = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        Instant.EPOCH
    }
}
