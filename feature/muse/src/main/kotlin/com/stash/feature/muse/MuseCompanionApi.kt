package com.stash.feature.muse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

internal class MuseApiException(
    val statusCode: Int,
    val code: String,
    val retryAfterSeconds: Long? = null,
) : Exception("Muse Companion request failed ($code)")

@Serializable
internal data class MuseCapabilities(
    val contract: String,
    val capabilities: List<String>,
)

/** Raw HTTP implementation of the stable `muse-companion/v1` contract. */
@Singleton
internal class MuseCompanionApi @Inject constructor(
    private val httpClient: OkHttpClient,
    private val identity: MuseDeviceIdentity,
) {
    private val json = MuseCompanionJson

    suspend fun capabilities(endpoint: String): MuseCapabilities {
        val request = Request.Builder()
            .url(MuseEndpoint.route(endpoint, "capabilities"))
            .get()
            .build()
        return executeJson(request)
    }

    suspend fun createPairingChallenge(
        endpoint: String,
        request: MusePairingChallengeRequest,
    ): MusePairingChallengeResponse {
        val url = MuseEndpoint.route(endpoint, "pairing-challenges")
        return executeJson(
            jsonRequest(
                url.toString(),
                json.encodeToString(MusePairingChallengeRequest.serializer(), request),
            ),
        )
    }

    suspend fun createPairing(endpoint: String, request: MusePairingSubmission): MusePairingStart {
        val url = MuseEndpoint.route(endpoint, "pairings")
        return executeJson(
            jsonRequest(url.toString(), json.encodeToString(MusePairingSubmission.serializer(), request)),
        )
    }

    suspend fun pollPairing(
        endpoint: String,
        pairingId: String,
        pairingSecret: String,
    ): MusePairingPollResult {
        val body = json.encodeToString(
            MusePairingPollRequest.serializer(),
            MusePairingPollRequest(pairingSecret = pairingSecret),
        )
        val request = jsonRequest(
            MuseEndpoint.route(endpoint, "pairings", pairingId, "poll").toString(),
            body,
        )
        val raw = executeText(request)
        val status = json.parseToJsonElement(raw).jsonObject["status"]?.jsonPrimitive?.content
            ?: throw MuseApiException(502, "invalid_pairing_response")
        return when (status) {
            "pending" -> MusePairingPollResult.Pending
            "expired" -> MusePairingPollResult.Expired
            "paired" -> MusePairingPollResult.Paired(
                json.decodeFromString(MusePairingPairedResponse.serializer(), raw),
            )
            else -> throw MuseApiException(502, "invalid_pairing_response")
        }
    }

    suspend fun refresh(
        endpoint: String,
        credential: MusePairedCredential,
        payload: MuseRefreshRequest,
    ): MuseTokenPair {
        val body = json.encodeToString(MuseRefreshRequest.serializer(), payload).encodeToByteArray()
        val request = signedJsonRequest(
            url = MuseEndpoint.route(endpoint, "token", "refresh").toString(),
            body = body,
            proofToken = credential.refreshToken,
            bearerToken = null,
        )
        return executeJson(request)
    }

    suspend fun revokeSelf(
        endpoint: String,
        credential: MusePairedCredential,
    ): MuseSelfRevokeResponse {
        val body = json.encodeToString(
            MuseSelfRevokeRequest.serializer(),
            MuseSelfRevokeRequest(),
        ).encodeToByteArray()
        val request = signedJsonRequest(
            url = MuseEndpoint.route(endpoint, "device", "revoke").toString(),
            body = body,
            proofToken = credential.accessToken,
            bearerToken = credential.accessToken,
        )
        return executeJson(request)
    }

    suspend fun player(endpoint: String, credential: MusePairedCredential): MusePlayerResponse {
        val request = signedRequest(
            Request.Builder()
                .url(MuseEndpoint.route(endpoint, "guilds", credential.grant.guildId, "player"))
                .get(),
            body = ByteArray(0),
            proofToken = credential.accessToken,
            bearerToken = credential.accessToken,
        ).build()
        return executeJson(request)
    }

    suspend fun action(
        endpoint: String,
        credential: MusePairedCredential,
        envelope: MuseActionEnvelope,
    ): MusePlayerResponse {
        val body = json.encodeToString(MuseActionEnvelope.serializer(), envelope).encodeToByteArray()
        val request = signedJsonRequest(
            url = MuseEndpoint.route(endpoint, "guilds", credential.grant.guildId, "actions").toString(),
            body = body,
            proofToken = credential.accessToken,
            bearerToken = credential.accessToken,
        )
        return executeJson(request)
    }

    suspend fun spotifyImportStatus(
        endpoint: String,
        credential: MusePairedCredential,
    ): MuseSpotifyImportStatus {
        val request = signedRequest(
            Request.Builder()
                .url(
                    MuseEndpoint.route(
                        endpoint,
                        "guilds",
                        credential.grant.guildId,
                        "spotify",
                        "import",
                    ),
                )
                .get(),
            body = ByteArray(0),
            proofToken = credential.accessToken,
            bearerToken = credential.accessToken,
        ).build()
        return executeJson(request)
    }

    suspend fun uploadSpotifyManifest(
        endpoint: String,
        credential: MusePairedCredential,
        rawBody: ByteArray,
    ): MuseSpotifyImportApplyResult {
        val request = signedJsonRequest(
            url = MuseEndpoint.route(
                endpoint,
                "guilds",
                credential.grant.guildId,
                "spotify",
                "import",
                "manifest",
            ).toString(),
            body = rawBody,
            proofToken = credential.accessToken,
            bearerToken = credential.accessToken,
            method = "PUT",
        )
        return executeJson(request)
    }

    suspend fun disconnectSpotifyImport(
        endpoint: String,
        credential: MusePairedCredential,
        rawBody: ByteArray,
    ): MuseSpotifyImportDisconnectResult {
        val request = signedJsonRequest(
            url = MuseEndpoint.route(
                endpoint,
                "guilds",
                credential.grant.guildId,
                "spotify",
                "import",
            ).toString(),
            body = rawBody,
            proofToken = credential.accessToken,
            bearerToken = credential.accessToken,
            method = "DELETE",
        )
        return executeJson(request)
    }

    /** One foreground-only stream. Callers reconcile over REST before every reconnect. */
    fun playerEvents(endpoint: String, credential: MusePairedCredential): Flow<MusePlayerResponse> = flow {
        val builder = Request.Builder()
            .url(MuseEndpoint.route(endpoint, "guilds", credential.grant.guildId, "events"))
            .get()
            .header("Accept", "text/event-stream")
        val request = signedRequest(
            builder,
            body = ByteArray(0),
            proofToken = credential.accessToken,
            bearerToken = credential.accessToken,
        ).build()
        val call = httpClient.newCall(request)
        val completion = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw response.toApiException()
                val source = response.body?.source()
                    ?: throw MuseApiException(502, "empty_event_stream")
                var eventName: String? = null
                val data = StringBuilder()
                while (currentCoroutineContext().isActive && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            if (eventName == "player" && data.isNotEmpty()) {
                                emit(json.decodeFromString(MusePlayerResponse.serializer(), data.toString()))
                            }
                            eventName = null
                            data.clear()
                        }
                        line.startsWith(":") -> Unit
                        line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substringAfter(':').trimStart())
                            if (data.length > MAX_RESPONSE_CHARS) {
                                throw MuseApiException(502, "event_too_large")
                            }
                        }
                    }
                }
            }
        } finally {
            completion.dispose()
            call.cancel()
        }
        if (currentCoroutineContext().isActive) {
            throw MuseApiException(503, "event_stream_closed")
        }
    }.flowOn(Dispatchers.IO)

    private fun jsonRequest(url: String, jsonBody: String): Request = Request.Builder()
        .url(url)
        .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        .header("Accept", "application/json")
        .build()

    private fun signedJsonRequest(
        url: String,
        body: ByteArray,
        proofToken: String,
        bearerToken: String?,
        method: String = "POST",
    ): Request = signedRequest(
        Request.Builder()
            .url(url)
            .method(method, body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json"),
        body,
        proofToken,
        bearerToken,
    ).build()

    private fun signedRequest(
        builder: Request.Builder,
        body: ByteArray,
        proofToken: String,
        bearerToken: String?,
    ): Request.Builder {
        val unsigned = builder.build()
        val timestamp = Instant.now().epochSecond
        val nonce = MuseProofCanonicalizer.nonce()
        val message = MuseProofCanonicalizer.message(
            method = unsigned.method,
            pathAndQuery = MuseProofCanonicalizer.pathAndQuery(unsigned.url),
            timestampSeconds = timestamp,
            nonce = nonce,
            body = body,
            accessOrRefreshToken = proofToken,
        )
        return unsigned.newBuilder().apply {
            header("X-Muse-Timestamp", timestamp.toString())
            header("X-Muse-Nonce", nonce)
            header("X-Muse-Signature", identity.sign(message))
            bearerToken?.let { header("Authorization", "Bearer $it") }
        }
    }

    private suspend inline fun <reified T> executeJson(request: Request): T =
        json.decodeFromString(executeText(request))

    private suspend fun executeText(request: Request): String = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toApiException()
            val contentLength = response.body?.contentLength() ?: 0L
            if (contentLength > MAX_RESPONSE_CHARS) throw MuseApiException(502, "response_too_large")
            response.body?.string()?.also {
                if (it.length > MAX_RESPONSE_CHARS) throw MuseApiException(502, "response_too_large")
            } ?: throw MuseApiException(502, "empty_response")
        }
    }

    private fun okhttp3.Response.toApiException(): MuseApiException {
        val raw = body?.string()?.take(MAX_ERROR_CHARS).orEmpty()
        val code = runCatching {
            json.decodeFromString(MuseErrorResponse.serializer(), raw).error
        }.getOrDefault("http_$code")
        return MuseApiException(
            statusCode = this.code,
            code = code,
            retryAfterSeconds = header("Retry-After")?.toLongOrNull(),
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESPONSE_CHARS = 512 * 1024
        const val MAX_ERROR_CHARS = 2048
    }
}
