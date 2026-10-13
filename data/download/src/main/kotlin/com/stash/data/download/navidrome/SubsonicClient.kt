package com.stash.data.download.navidrome

import com.stash.core.data.sync.TrackMatcher
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A Subsonic `failed` response. [code] follows the Subsonic spec (40 = wrong credentials). */
class SubsonicException(val code: Int, message: String) : IOException("Subsonic error $code: $message") {
    /** Credentials or permissions are wrong: retrying won't help until the user fixes them. */
    val isPermanent: Boolean get() = code in PERMANENT_CODES

    private companion object {
        val PERMANENT_CODES = setOf(40, 41, 42, 43, 44, 50)
    }
}

/** A song on the server, as far as matching and reporting need it. */
data class SubsonicSong(
    val id: String,
    val artist: String,
    val title: String,
    val durationSec: Int,
    val isrcs: List<String>,
    /** File extension on the server ("flac", "opus", "m4a", "mp3"); empty when unknown. */
    val suffix: String = "",
    val bitRateKbps: Int = 0,
    val sizeBytes: Long = 0,
)

data class SubsonicServerInfo(val serverVersion: String, val songCount: Int?)

/**
 * Minimal Subsonic/OpenSubsonic client for the user's Navidrome server.
 *
 * Authentication uses the token scheme (`t = md5(password + salt)`, a fresh
 * random `s` per request), so the password itself never travels. The URLs
 * still carry `u`/`t`/`s`; anything that logs a request URL must pass it
 * through [redact] first.
 */
@Singleton
class SubsonicClient internal constructor(
    httpClient: OkHttpClient,
    private val matcher: TrackMatcher,
    private val saltSource: () -> String,
) {
    @Inject constructor(httpClient: OkHttpClient, matcher: TrackMatcher) :
        this(httpClient, matcher, ::randomSalt)

    private val http = httpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Every Subsonic URL carries the auth token + salt as query
        // parameters, which together log the user in. The shared client's
        // HTTP logger printed them into logcat — and from there into the
        // diagnostics bundle. Never log these requests.
        .apply { interceptors().removeAll { it.javaClass.name == HTTP_LOGGER } }
        .build()

    /**
     * Downloads the original file of [songId] (`download.view`) to [destination].
     * Throws on HTTP or I/O failure; the caller cleans up.
     */
    suspend fun download(
        config: NavidromeServerConfig,
        songId: String,
        destination: java.io.File,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val request = okhttp3.Request.Builder().url(buildUrl(config, "download", "id" to songId)).get().build()
        http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("download.view HTTP ${response.code}")
            val body = response.body ?: throw java.io.IOException("download.view: empty body")
            val type = body.contentType()?.toString().orEmpty()
            // A Subsonic error comes back as a 200 with a JSON/XML body.
            if (type.startsWith("application/json") || type.contains("xml")) {
                throw java.io.IOException("download.view returned $type instead of audio")
            }
            val total = body.contentLength()
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Checks the credentials; also returns the version and library size for the Settings status line. */
    suspend fun ping(config: NavidromeServerConfig): SubsonicServerInfo {
        val root = call(config, "ping")
        val version = root["serverVersion"]?.jsonPrimitive?.contentOrNull
            ?: root["version"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val count = runCatching {
            call(config, "getScanStatus")["scanStatus"]?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull
        }.getOrNull()
        return SubsonicServerInfo(version, count)
    }

    /**
     * Finds [title] by [artist] on the server. An ISRC match wins; otherwise the
     * canonical artist + title (same normalization as Stash's library matching)
     * must agree and, when both durations are known, lie within [DURATION_TOLERANCE_SEC].
     * Returns null when the server doesn't have the song.
     */
    suspend fun findSong(
        config: NavidromeServerConfig,
        artist: String,
        title: String,
        isrc: String?,
        durationMs: Long,
    ): SubsonicSong? {
        val result = call(
            config, "search3",
            "query" to "$artist $title",
            "songCount" to "25", "artistCount" to "0", "albumCount" to "0",
        )
        val songs = result["searchResult3"]?.jsonObject?.get("song")?.asArray().orEmpty()
            .mapNotNull { (it as? JsonObject)?.toSong() }
        if (songs.isEmpty()) return null

        val wantedIsrc = isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (wantedIsrc != null) {
            songs.firstOrNull { s -> s.isrcs.any { it.equals(wantedIsrc, ignoreCase = true) } }?.let { return it }
        }
        val wantArtist = matcher.canonicalArtist(artist)
        val wantTitle = matcher.canonicalTitle(title)
        val wantSec = (durationMs / 1000).toInt()
        fun durationDiff(s: SubsonicSong) =
            if (wantSec <= 0 || s.durationSec <= 0) 0 else abs(s.durationSec - wantSec)
        // Canonical matching strips "(Live)", "(Remastered)" etc., so several
        // versions can qualify: prefer the exact title, then the closest length.
        return songs
            .filter { s ->
                matcher.canonicalTitle(s.title) == wantTitle &&
                    matcher.canonicalArtist(s.artist) == wantArtist &&
                    durationDiff(s) <= DURATION_TOLERANCE_SEC
            }
            .minWithOrNull(
                compareBy<SubsonicSong> { if (it.title.trim().equals(title.trim(), ignoreCase = true)) 0 else 1 }
                    .thenBy(::durationDiff),
            )
    }

    /**
     * Reports a play. `submission = false` is "now playing"; `true` records the
     * listen at [timeMs] (when it started). Navidrome forwards both to the
     * Last.fm / ListenBrainz accounts linked in its UI.
     */
    suspend fun scrobble(config: NavidromeServerConfig, songId: String, timeMs: Long, submission: Boolean) {
        call(
            config, "scrobble",
            "id" to songId,
            "time" to timeMs.toString(),
            "submission" to submission.toString(),
        )
    }

    private suspend fun call(
        config: NavidromeServerConfig,
        method: String,
        vararg params: Pair<String, String>,
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = buildUrl(config, method, *params)
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${redact(url.toString())}")
            val body = response.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject }
                .getOrNull() ?: throw IOException("Not a Subsonic response from ${redact(url.toString())}")
            if (root["status"]?.jsonPrimitive?.contentOrNull != "ok") {
                val error = root["error"]?.jsonObject
                throw SubsonicException(
                    code = error?.get("code")?.jsonPrimitive?.intOrNull ?: 0,
                    message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "unknown error",
                )
            }
            root
        }
    }

    internal fun buildUrl(config: NavidromeServerConfig, method: String, vararg params: Pair<String, String>): HttpUrl {
        val salt = saltSource()
        val builder = "${config.serverUrl.trimEnd('/')}/rest/$method".toHttpUrl().newBuilder()
            .addQueryParameter("u", config.username)
            .addQueryParameter("t", md5Hex(config.password + salt))
            .addQueryParameter("s", salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun JsonObject.toSong(): SubsonicSong? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val isrcs = when (val raw = this["isrc"]) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(raw.contentOrNull)
            else -> emptyList()
        }
        return SubsonicSong(
            id = id,
            artist = this["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            title = this["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            durationSec = this["duration"]?.jsonPrimitive?.intOrNull ?: 0,
            isrcs = isrcs,
            suffix = this["suffix"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase(),
            bitRateKbps = this["bitRate"]?.jsonPrimitive?.intOrNull ?: 0,
            sizeBytes = this["size"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    /** Subsonic returns a lone object instead of a one-element array in some responses. */
    private fun JsonElement.asArray(): List<JsonElement> = when (this) {
        is JsonArray -> this
        is JsonObject -> listOf(this)
        else -> emptyList()
    }

    companion object {
        private const val HTTP_LOGGER = "okhttp3.logging.HttpLoggingInterceptor"
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "stash"
        const val DURATION_TOLERANCE_SEC = 5

        private val SECRET_PARAMS = Regex("""([?&](?:u|t|s|p)=)[^&]*""")

        /** Blanks the credential parameters in a Subsonic URL before it is logged. */
        fun redact(url: String): String = url.replace(SECRET_PARAMS, "$1***")

        internal fun md5Hex(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private val random = SecureRandom()

        private fun randomSalt(): String {
            val bytes = ByteArray(8).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
