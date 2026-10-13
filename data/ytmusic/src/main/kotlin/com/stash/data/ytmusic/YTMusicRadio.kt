package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.serialization.json.JsonObject

/** One song of a YouTube Music radio ("song radio" / "Mix") queue. */
data class RadioTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val musicVideoType: MusicVideoType?,
)

/**
 * Parses the queue of a `next` response for a radio playlist
 * (`RDAMVM<videoId>`): YouTube Music's own "songs like this one" list.
 *
 * Video uploads come back with their video title ("Dua Lipa - New Rules
 * (Official Music Video)"); [cleanTitle] strips the artist prefix and the
 * "official video" decorations so the title matches the song.
 */
object YTMusicRadio {

    /** Radio playlist id for a seed video. */
    fun playlistIdFor(videoId: String) = "RDAMVM$videoId"

    fun parse(response: JsonObject, seedVideoId: String? = null): List<RadioTrack> {
        val items = response.navigatePath(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
            "watchNextTabbedResultsRenderer", "tabs",
        )?.asArray()?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer", "contents")
            ?.asArray() ?: return emptyList()
        val out = ArrayList<RadioTrack>()
        val seen = HashSet<String>()
        for (item in items) {
            val r = item.asObject()?.get("playlistPanelVideoRenderer")?.asObject() ?: continue
            val videoId = r["videoId"]?.asString() ?: continue
            if (videoId == seedVideoId || !seen.add(videoId)) continue
            val rawTitle = r.navigatePath("title", "runs")?.asArray()?.firstOrNull()?.asObject()
                ?.get("text")?.asString()?.takeIf { it.isNotBlank() } ?: continue
            val byline = r.navigatePath("longBylineText", "runs")?.asArray()
                ?.mapNotNull { it.asObject()?.get("text")?.asString() }.orEmpty()
            // "Artist • 3.3B views • 18M likes" or "Artist • Album • 2021".
            val artist = byline.joinToString("").split(" • ").firstOrNull()?.trim().orEmpty()
            if (artist.isBlank()) continue
            val type = r.navigatePath(
                "navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs",
                "watchEndpointMusicConfig", "musicVideoType",
            )?.asString()
            val length = r.navigatePath("lengthText", "runs")?.asArray()?.firstOrNull()?.asObject()
                ?.get("text")?.asString()
            out += RadioTrack(
                videoId = videoId,
                title = cleanTitle(rawTitle, artist),
                artist = cleanArtist(artist),
                durationMs = length?.let(::parseDuration),
                musicVideoType = MusicVideoType.fromInnerTube(type),
            )
        }
        return out
    }

    private val DECORATION = Regex(
        """\s*[(\[](?:official\s+)?(?:4k\s+|hd\s+)?(?:music\s+)?(?:video|audio|visualizer|lyric(?:s)?(?:\s+video)?|mv)(?:\s+version)?[)\]]""" +
            """|\s*[(\[]official[^)\]]*[)\]]""" +
            """|\s+official\s+(?:music\s+)?(?:video|mv)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "Dua Lipa - New Rules (Official Music Video)" → "New Rules". */
    fun cleanTitle(title: String, artist: String): String {
        var t = title.replace(DECORATION, "").trim()
        val dash = Regex("""^(.+?)\s+[-–—]\s+(.+)$""").find(t)
        if (dash != null) {
            val (left, right) = dash.destructured
            if (similarArtist(left, artist)) t = right.trim()
        }
        return t.trim('\'', '"', ' ').ifBlank { title }
    }

    /** Channel names like "Major Lazer Official" or "Dua Lipa - Topic" → the artist. */
    fun cleanArtist(artist: String): String =
        artist.replace(Regex("""\s*-\s*Topic$|\s+Official$|VEVO$""", RegexOption.IGNORE_CASE), "").trim()
            .ifBlank { artist }

    private fun similarArtist(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")
        val na = norm(a)
        val nb = norm(cleanArtist(b))
        return na.isNotEmpty() && nb.isNotEmpty() && (na == nb || na.startsWith(nb) || nb.startsWith(na))
    }

    private fun parseDuration(text: String): Long? {
        val parts = text.split(":").map { it.trim().toLongOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }
}
