package com.stash.feature.muse

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.stash.data.spotify.PrivatePlaylistImportException
import com.stash.data.spotify.PrivatePlaylistImportFormat
import com.stash.data.spotify.PrivatePlaylistImportParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

internal class MuseManifestValidationException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

internal data class MuseSelectedDocument(
    val bytes: ByteArray,
    val fileName: String?,
)

@Singleton
internal class MuseDocumentReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun read(uri: Uri): MuseSelectedDocument = withContext(Dispatchers.IO) {
        val fileName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.take(256) else null
        }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw MuseManifestValidationException("cannot_open", "Die ausgewählte Datei kann nicht geöffnet werden.")
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_LOCAL_IMPORT_BYTES) {
                    throw MuseManifestValidationException(
                        "file_too_large",
                        "Die Datei ist größer als das lokale 25-MiB-Importlimit.",
                    )
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        MuseSelectedDocument(bytes, fileName)
    }

    private companion object {
        const val MAX_LOCAL_IMPORT_BYTES = 25 * 1024 * 1024
    }
}

/** Converts the broad local parser result into Muse's strict atomic manifest. */
@Singleton
internal class MusePrivateManifestAdapter @Inject constructor() {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun prepare(
        selected: MuseSelectedDocument,
        accountFingerprint: String,
        generation: Int,
    ): MusePreparedManifest {
        try {
            require(accountFingerprint.matches(FINGERPRINT)) { "Ungültige lokale Importprofil-ID." }
            require(generation in 1..Int.MAX_VALUE) { "Ungültige Importgeneration." }
            val parsed = try {
                PrivatePlaylistImportParser.parse(selected.bytes, selected.fileName)
            } catch (error: PrivatePlaylistImportException) {
                throw MuseManifestValidationException(error.code, parserError(error.code))
            }
            if (parsed.skippedEntries != 0) {
                throw MuseManifestValidationException(
                    "partial_import_not_allowed",
                    "${parsed.skippedEntries} Einträge konnten nicht sicher gelesen werden. " +
                        "Es wurde nichts an Muse übertragen.",
                )
            }
            if (parsed.playlists.size > MAX_PLAYLISTS) {
                throw MuseManifestValidationException(
                    "too_many_playlists",
                    "${parsed.playlists.size} Playlists überschreiten das Serverlimit von $MAX_PLAYLISTS. " +
                        "Muse behält die letzte gute Generation.",
                )
            }
            val duplicateIds = parsed.playlists.groupingBy { it.sourcePlaylistId }.eachCount().filterValues { it > 1 }
            if (duplicateIds.isNotEmpty()) {
                throw MuseManifestValidationException(
                    "duplicate_playlist",
                    "Die Datei enthält doppelte stabile Playlist-IDs. Es wurde nichts übertragen.",
                )
            }

            var itemCount = 0
            val playlists = parsed.playlists.map { playlist ->
                if (playlist.tracks.size > MAX_ITEMS_PER_PLAYLIST) {
                    throw MuseManifestValidationException(
                        "playlist_too_large",
                        "„${playlist.name}“ enthält mehr als $MAX_ITEMS_PER_PLAYLIST Titel.",
                    )
                }
                val playlistId = bounded(playlist.sourcePlaylistId, 256, "Playlist-ID")
                val name = bounded(playlist.name, 256, "Playlistname")
                if (!playlist.snapshotFingerprint.matches(FINGERPRINT)) {
                    throw MuseManifestValidationException("invalid_fingerprint", "Ungültiger Playlist-Fingerprint.")
                }
                val items = playlist.tracks.mapIndexed { index, track ->
                    itemCount += 1
                    if (itemCount > MAX_ITEMS) {
                        throw MuseManifestValidationException(
                            "too_many_items",
                            "Die vollständige Sammlung überschreitet $MAX_ITEMS Titel. " +
                                "Es wurde nichts gekürzt oder übertragen.",
                        )
                    }
                    val title = track.title?.let { bounded(it, 512, "Titel ${index + 1}") }
                        ?: throw missingMetadata(name, index, "Titel")
                    val artist = track.artist?.let { bounded(it, 512, "Künstler ${index + 1}") }
                        ?: throw missingMetadata(name, index, "Künstler")
                    val duration = track.durationMs?.takeIf { it in 1..86_400_000 }?.toInt()
                        ?: throw missingMetadata(name, index, "Dauer")
                    track.trackUri?.takeUnless { it.matches(SPOTIFY_TRACK_URI) }?.let {
                        throw MuseManifestValidationException("invalid_track_uri", "Ungültige Spotify-Track-URI.")
                    }
                    track.isrc?.takeUnless { it.matches(ISRC) }?.let {
                        throw MuseManifestValidationException("invalid_isrc", "Ungültiger ISRC in „$name“.")
                    }
                    MuseManifestTrack(
                        trackUri = track.trackUri,
                        isrc = track.isrc,
                        title = title,
                        artists = listOf(artist),
                        album = track.album?.let { bounded(it, 512, "Album") },
                        durationMs = duration,
                    )
                }
                MuseManifestPlaylist(
                    playlistId = playlistId,
                    name = name,
                    playlistFingerprint = playlist.snapshotFingerprint.lowercase(),
                    items = items,
                )
            }

            val report = MuseManifestImportReport(
                acceptedPlaylists = playlists.size,
                acceptedItems = itemCount,
                skippedEntries = 0,
            )
            val fingerprintContent = MuseManifestFingerprintContent(
                accountFingerprint = accountFingerprint,
                generation = generation,
                importReport = report,
                playlists = playlists,
            )
            val manifestFingerprint = json.encodeToString(
                MuseManifestFingerprintContent.serializer(),
                fingerprintContent,
            ).encodeToByteArray().sha256Hex()
            val template = MuseManifestUpload(
                idempotencyKey = IDEMPOTENCY_PLACEHOLDER,
                accountFingerprint = accountFingerprint,
                generation = generation,
                manifestFingerprint = manifestFingerprint,
                importReport = report,
                playlists = playlists,
            )
            val byteSize = encode(template).size
            if (byteSize > MAX_REMOTE_BODY_BYTES) {
                throw MuseManifestValidationException(
                    "manifest_too_large",
                    "Das normalisierte Manifest ist ${formatBytes(byteSize)} groß und überschreitet 2 MiB. " +
                        "Es wurde nichts an Muse übertragen.",
                )
            }
            return MusePreparedManifest(template, byteSize, parsed.format.label())
        } finally {
            selected.bytes.fill(0)
        }
    }

    fun encodeForUpload(prepared: MusePreparedManifest, idempotencyKey: String): ByteArray {
        require(idempotencyKey.matches(Regex("^[A-Za-z0-9._-]{16,128}$")))
        val bytes = encode(prepared.uploadTemplate.copy(idempotencyKey = idempotencyKey))
        if (bytes.size > MAX_REMOTE_BODY_BYTES) {
            throw MuseManifestValidationException("manifest_too_large", "Das Manifest überschreitet 2 MiB.")
        }
        return bytes
    }

    private fun encode(upload: MuseManifestUpload): ByteArray = json.encodeToString(
        MuseManifestUpload.serializer(),
        upload,
    ).encodeToByteArray()

    private fun bounded(value: String, maximum: Int, label: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        if (normalized.isEmpty() || normalized.length > maximum || CONTROL.containsMatchIn(normalized)) {
            throw MuseManifestValidationException("invalid_metadata", "$label ist ungültig oder zu lang.")
        }
        return normalized
    }

    private fun missingMetadata(playlist: String, index: Int, field: String) =
        MuseManifestValidationException(
            "missing_metadata",
            "„$playlist“, Eintrag ${index + 1}: $field fehlt. Es wurde nichts an Muse übertragen.",
        )

    private fun PrivatePlaylistImportFormat.label(): String = when (this) {
        PrivatePlaylistImportFormat.SPOTIFY_JSON -> "Spotify JSON"
        PrivatePlaylistImportFormat.NORMALIZED_JSON -> "Normalisiertes JSON"
        PrivatePlaylistImportFormat.CSV -> "CSV"
        PrivatePlaylistImportFormat.M3U -> "M3U/M3U8"
    }

    private fun parserError(code: String): String = when (code) {
        "zip_not_supported" -> "ZIP-Dateien werden aus Sicherheitsgründen nicht verarbeitet. Bitte vorher entpacken."
        "file_too_large" -> "Die Datei überschreitet das lokale 25-MiB-Limit."
        "binary_file" -> "Die ausgewählte Datei ist keine unterstützte Textdatei."
        "invalid_json", "invalid_json_shape" -> "Die JSON-Datei ist ungültig."
        "unsupported_format" -> "Unterstützt werden JSON, CSV, M3U und M3U8."
        else -> "Die Datei konnte nicht vollständig gelesen werden ($code)."
    }

    private fun formatBytes(bytes: Int): String = "%.2f MiB".format(bytes / 1024.0 / 1024.0)

    companion object {
        const val MAX_REMOTE_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_PLAYLISTS = 100
        const val MAX_ITEMS = 5_000
        const val MAX_ITEMS_PER_PLAYLIST = 2_000
        private const val IDEMPOTENCY_PLACEHOLDER = "00000000-0000-0000-0000-000000000000"
        private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
        private val SPOTIFY_TRACK_URI = Regex("^spotify:track:[A-Za-z0-9]{22}$")
        private val ISRC = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")
        private val CONTROL = Regex("[\\p{C}]")
    }
}
