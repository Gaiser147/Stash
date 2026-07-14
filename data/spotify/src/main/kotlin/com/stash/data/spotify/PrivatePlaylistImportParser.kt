package com.stash.data.spotify

import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val MAX_IMPORT_BYTES = 25 * 1024 * 1024
private const val MAX_PLAYLISTS = 5_000
private const val MAX_TRACKS = 100_000
private const val MAX_PLAYLIST_NAME_LENGTH = 256
private const val MAX_METADATA_LENGTH = 512
private const val MAX_URI_LENGTH = 256
private val SPOTIFY_TRACK_URI = Regex("^spotify:track:[A-Za-z0-9]{22}$")
private val SPOTIFY_TRACK_URL = Regex("^https://open\\.spotify\\.com/track/([A-Za-z0-9]{22})(?:[?#].*)?$")
private val ISRC = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

enum class PrivatePlaylistImportFormat {
    SPOTIFY_JSON,
    NORMALIZED_JSON,
    CSV,
    M3U,
}

data class ImportedPrivateTrack(
    val position: Int,
    val trackUri: String?,
    val isrc: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

data class ImportedPrivatePlaylist(
    val sourcePlaylistId: String,
    val snapshotFingerprint: String,
    val name: String,
    val tracks: List<ImportedPrivateTrack>,
)

data class PrivatePlaylistImportResult(
    val format: PrivatePlaylistImportFormat,
    val playlists: List<ImportedPrivatePlaylist>,
    val skippedEntries: Int,
)

class PrivatePlaylistImportException(val code: String) :
    IllegalArgumentException("Private playlist import rejected ($code)")

/**
 * Parses an account export locally into the privacy-minimized manifest sent to
 * Muse. Raw files, cookies, bearer tokens and local file paths are never part
 * of the result. ZIP input is intentionally unsupported.
 */
object PrivatePlaylistImportParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(bytes: ByteArray, fileName: String? = null): PrivatePlaylistImportResult {
        if (bytes.isEmpty()) throw PrivatePlaylistImportException("empty_file")
        if (bytes.size > MAX_IMPORT_BYTES) throw PrivatePlaylistImportException("file_too_large")
        if (fileName?.endsWith(".zip", ignoreCase = true) == true || isZip(bytes)) {
            throw PrivatePlaylistImportException("zip_not_supported")
        }

        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (text.indexOf('\u0000') >= 0) throw PrivatePlaylistImportException("binary_file")
        val normalizedFileName = fileName?.lowercase().orEmpty()
        return when {
            normalizedFileName.endsWith(".m3u") || normalizedFileName.endsWith(".m3u8") ->
                parseM3u(text, fileName)
            normalizedFileName.endsWith(".csv") -> parseCsv(text, fileName)
            normalizedFileName.endsWith(".json") || text.trimStart().firstOrNull() in setOf('{', '[') ->
                parseJson(text)
            text.lineSequence().any { it.trimStart().startsWith("#EXTM3U", ignoreCase = true) } ->
                parseM3u(text, fileName)
            else -> throw PrivatePlaylistImportException("unsupported_format")
        }
    }

    private fun parseJson(text: String): PrivatePlaylistImportResult {
        val root = runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw PrivatePlaylistImportException("invalid_json") }
        val rootObject = root as? JsonObject
            ?: throw PrivatePlaylistImportException("invalid_json_shape")
        val playlistElements = rootObject.array("playlists")
            ?: throw PrivatePlaylistImportException("playlists_missing")
        val format = if (rootObject.string("contract")?.startsWith("muse-private-manifest/") == true) {
            PrivatePlaylistImportFormat.NORMALIZED_JSON
        } else {
            PrivatePlaylistImportFormat.SPOTIFY_JSON
        }
        val parsed = mutableListOf<ImportedPrivatePlaylist>()
        var skipped = 0
        var totalTracks = 0
        for ((playlistIndex, element) in playlistElements.withIndex()) {
            if (parsed.size >= MAX_PLAYLISTS) throw PrivatePlaylistImportException("too_many_playlists")
            val playlist = element as? JsonObject
            if (playlist == null) {
                skipped += 1
                continue
            }
            val name = bounded(playlist.string("name"), MAX_PLAYLIST_NAME_LENGTH)
                ?: "Imported playlist ${playlistIndex + 1}"
            val items = playlist.array("items") ?: playlist.array("tracks") ?: JsonArray(emptyList())
            val tracks = mutableListOf<ImportedPrivateTrack>()
            for ((position, itemElement) in items.withIndex()) {
                totalTracks += 1
                if (totalTracks > MAX_TRACKS) throw PrivatePlaylistImportException("too_many_tracks")
                val item = itemElement as? JsonObject
                val track = (item?.get("track") as? JsonObject) ?: item
                val parsedTrack = track?.let { parseJsonTrack(it, position) }
                if (parsedTrack == null) {
                    skipped += 1
                } else {
                    tracks += parsedTrack
                }
            }
            val suppliedId = bounded(
                playlist.string("id") ?: playlist.string("playlistId") ?: playlist.string("uri"),
                MAX_URI_LENGTH,
            )
            parsed += buildPlaylist(suppliedId, name, tracks)
        }
        if (parsed.isEmpty()) throw PrivatePlaylistImportException("no_playlists")
        return PrivatePlaylistImportResult(format, parsed, skipped)
    }

    private fun parseJsonTrack(track: JsonObject, position: Int): ImportedPrivateTrack? {
        val trackUri = normalizeSpotifyTrackUri(
            track.string("trackUri") ?: track.string("track_uri") ?: track.string("uri"),
        )
        val title = bounded(
            track.string("title") ?: track.string("trackName") ?: track.string("name"),
            MAX_METADATA_LENGTH,
        )
        val artist = bounded(
            track.string("artist") ?: track.string("artistName") ?: parseArtists(track["artists"]),
            MAX_METADATA_LENGTH,
        )
        val album = bounded(
            track.string("album") ?: track.string("albumName"),
            MAX_METADATA_LENGTH,
        )
        val isrc = normalizeIsrc(track.string("isrc") ?: (track["external_ids"] as? JsonObject)?.string("isrc"))
        val duration = track.long("durationMs") ?: track.long("duration_ms")
        val durationMs = duration?.takeIf { it in 1..86_400_000 }
        if (trackUri == null && (title == null || artist == null)) return null
        return ImportedPrivateTrack(position, trackUri, isrc, title, artist, album, durationMs)
    }

    private fun parseCsv(text: String, fileName: String?): PrivatePlaylistImportResult {
        val rows = parseCsvRows(text)
        if (rows.isEmpty()) throw PrivatePlaylistImportException("empty_file")
        val headers = rows.first().map(::headerKey)
        val playlistColumn = headers.firstIndexOf("playlist", "playlistname", "playlist_name")
        val positionColumn = headers.firstIndexOf("position", "index", "tracknumber")
        val uriColumn = headers.firstIndexOf("trackuri", "track_uri", "spotifyuri", "uri", "url")
        val isrcColumn = headers.firstIndexOf("isrc")
        val titleColumn = headers.firstIndexOf("title", "track", "trackname", "track_name")
        val artistColumn = headers.firstIndexOf("artist", "artistname", "artist_name")
        val albumColumn = headers.firstIndexOf("album", "albumname", "album_name")
        val durationColumn = headers.firstIndexOf("durationms", "duration_ms", "duration")
        if (uriColumn < 0 && (titleColumn < 0 || artistColumn < 0)) {
            throw PrivatePlaylistImportException("csv_columns_missing")
        }

        val fallbackName = importName(fileName)
        val grouped = linkedMapOf<String, MutableList<Pair<Int?, ImportedPrivateTrack>>>()
        var skipped = 0
        for ((rowIndex, row) in rows.drop(1).withIndex()) {
            if (row.all(String::isBlank)) continue
            if (rowIndex >= MAX_TRACKS) throw PrivatePlaylistImportException("too_many_tracks")
            val name = bounded(row.value(playlistColumn), MAX_PLAYLIST_NAME_LENGTH) ?: fallbackName
            val trackUri = normalizeSpotifyTrackUri(row.value(uriColumn))
            val title = bounded(row.value(titleColumn), MAX_METADATA_LENGTH)
            val artist = bounded(row.value(artistColumn), MAX_METADATA_LENGTH)
            if (trackUri == null && (title == null || artist == null)) {
                skipped += 1
                continue
            }
            val suppliedPosition = row.value(positionColumn)?.toIntOrNull()?.takeIf { it >= 0 }
            val duration = row.value(durationColumn)?.toLongOrNull()?.takeIf { it in 1..86_400_000 }
            val tracks = grouped.getOrPut(name) {
                if (grouped.size >= MAX_PLAYLISTS) throw PrivatePlaylistImportException("too_many_playlists")
                mutableListOf()
            }
            tracks += suppliedPosition to ImportedPrivateTrack(
                position = tracks.size,
                trackUri = trackUri,
                isrc = normalizeIsrc(row.value(isrcColumn)),
                title = title,
                artist = artist,
                album = bounded(row.value(albumColumn), MAX_METADATA_LENGTH),
                durationMs = duration,
            )
        }
        val playlists = grouped.map { (name, entries) ->
            val ordered = entries.sortedWith(compareBy<Pair<Int?, ImportedPrivateTrack>> { it.first ?: Int.MAX_VALUE })
                .mapIndexed { index, entry -> entry.second.copy(position = index) }
            buildPlaylist(null, name, ordered)
        }
        if (playlists.isEmpty()) throw PrivatePlaylistImportException("no_playlists")
        return PrivatePlaylistImportResult(PrivatePlaylistImportFormat.CSV, playlists, skipped)
    }

    private fun parseM3u(text: String, fileName: String?): PrivatePlaylistImportResult {
        var name = importName(fileName)
        val tracks = mutableListOf<ImportedPrivateTrack>()
        var pendingDurationMs: Long? = null
        var pendingArtist: String? = null
        var pendingTitle: String? = null
        var skipped = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.equals("#EXTM3U", ignoreCase = true)) continue
            if (line.startsWith("#PLAYLIST:", ignoreCase = true)) {
                name = bounded(line.substringAfter(':'), MAX_PLAYLIST_NAME_LENGTH) ?: name
                continue
            }
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val payload = line.substringAfter(':')
                val durationSeconds = payload.substringBefore(',').trim().toLongOrNull()
                pendingDurationMs = durationSeconds?.takeIf { it in 1..86_400 }?.times(1_000)
                val display = payload.substringAfter(',', "").trim()
                val split = display.split(" - ", limit = 2)
                pendingArtist = bounded(split.getOrNull(0), MAX_METADATA_LENGTH)
                pendingTitle = bounded(split.getOrNull(1) ?: display, MAX_METADATA_LENGTH)
                continue
            }
            if (line.startsWith('#')) continue
            if (tracks.size >= MAX_TRACKS) throw PrivatePlaylistImportException("too_many_tracks")
            val uri = normalizeSpotifyTrackUri(line)
            if (uri == null && (pendingArtist == null || pendingTitle == null)) {
                skipped += 1
            } else {
                tracks += ImportedPrivateTrack(
                    position = tracks.size,
                    trackUri = uri,
                    isrc = null,
                    title = pendingTitle,
                    artist = pendingArtist,
                    album = null,
                    durationMs = pendingDurationMs,
                )
            }
            pendingDurationMs = null
            pendingArtist = null
            pendingTitle = null
        }
        if (tracks.isEmpty()) throw PrivatePlaylistImportException("no_playlists")
        return PrivatePlaylistImportResult(
            PrivatePlaylistImportFormat.M3U,
            listOf(buildPlaylist(null, name, tracks)),
            skipped,
        )
    }

    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && text.getOrNull(index + 1) == '"' -> {
                    value.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row += value.toString()
                    value.clear()
                }
                !quoted && (char == '\n' || char == '\r') -> {
                    row += value.toString()
                    value.clear()
                    if (row.any(String::isNotBlank)) rows += row
                    row = mutableListOf()
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index += 1
                }
                else -> value.append(char)
            }
            index += 1
        }
        if (quoted) throw PrivatePlaylistImportException("invalid_csv")
        row += value.toString()
        if (row.any(String::isNotBlank)) rows += row
        return rows
    }

    private fun buildPlaylist(
        suppliedId: String?,
        name: String,
        tracks: List<ImportedPrivateTrack>,
    ): ImportedPrivatePlaylist {
        val canonical = buildString {
            append(name)
            tracks.forEach { track ->
                append('\n')
                append(track.trackUri.orEmpty())
                append('|')
                append(track.isrc.orEmpty())
                append('|')
                append(track.title.orEmpty())
                append('|')
                append(track.artist.orEmpty())
                append('|')
                append(track.album.orEmpty())
                append('|')
                append(track.durationMs ?: 0)
            }
        }
        val fingerprint = sha256(canonical)
        val sourceId = suppliedId?.let(::sha256) ?: fingerprint.take(32)
        return ImportedPrivatePlaylist(sourceId, fingerprint, name, tracks)
    }

    private fun normalizeSpotifyTrackUri(value: String?): String? {
        val candidate = bounded(value, MAX_URI_LENGTH) ?: return null
        if (SPOTIFY_TRACK_URI.matches(candidate)) return candidate
        val id = SPOTIFY_TRACK_URL.matchEntire(candidate)?.groupValues?.get(1) ?: return null
        return "spotify:track:$id"
    }

    private fun normalizeIsrc(value: String?): String? {
        val normalized = value?.trim()?.uppercase()?.replace("-", "") ?: return null
        return normalized.takeIf(ISRC::matches)
    }

    private fun bounded(value: String?, maximum: Int): String? {
        val normalized = value
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return normalized.takeIf { it.length <= maximum }
    }

    private fun parseArtists(element: JsonElement?): String? = when (element) {
        is JsonArray -> element.mapNotNull { artist ->
            when (artist) {
                is JsonPrimitive -> artist.contentOrNull
                is JsonObject -> artist.string("name")
                else -> null
            }
        }.joinToString(", ").ifBlank { null }
        is JsonPrimitive -> element.contentOrNull
        else -> null
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

    private fun List<String>.firstIndexOf(vararg candidates: String): Int =
        indexOfFirst { it in candidates }

    private fun List<String>.value(index: Int): String? = getOrNull(index)

    private fun headerKey(value: String): String = value.trim().lowercase().replace(" ", "")

    private fun importName(fileName: String?): String = fileName
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.')
        ?.let { bounded(it, MAX_PLAYLIST_NAME_LENGTH) }
        ?: "Imported playlist"

    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] in listOf(3, 5, 7).map(Int::toByte) && bytes[3] in listOf(4, 6, 8).map(Int::toByte)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
