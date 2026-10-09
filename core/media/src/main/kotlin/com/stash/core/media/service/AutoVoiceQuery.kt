package com.stash.core.media.service

import java.text.Normalizer

/**
 * What a spoken "play … on Stash" request means. Pure, so it's testable
 * without a car: Android Auto hands the recognised text to
 * `onSetMediaItems` as `requestMetadata.searchQuery`.
 */
internal object AutoVoiceQuery {

    sealed interface Target {
        /** Play the generated "Mix for you". Also the answer to a bare "play Stash". */
        data object MixForYou : Target
        data class Playlist(val id: Long) : Target
        data class Songs(val query: String) : Target
    }

    private val MIX_PHRASES = setOf(
        "mix", "mix for me", "mix for you", "my mix", "a mix", "something", "music",
        "mix fur mich", "mix fuer mich", "mein mix", "einen mix", "musik", "irgendwas",
    )

    /**
     * @param playlists id and name of every playlist the car may play.
     */
    fun resolve(query: String?, playlists: List<Pair<Long, String>>): Target {
        val q = normalize(query.orEmpty())
        if (q.isEmpty()) return Target.MixForYou
        val named = playlists.map { (id, name) -> id to normalize(name) }.filter { it.second.isNotEmpty() }
        named.firstOrNull { it.second == q }?.let { return Target.Playlist(it.first) }
        if (q in MIX_PHRASES) return Target.MixForYou
        // "play my road trip playlist" → the longest playlist name inside the request.
        val stripped = q.removeSuffix(" playlist").removePrefix("playlist ").trim()
        named.filter { it.second.length >= 3 && (stripped.contains(it.second) || it.second == stripped) }
            .maxByOrNull { it.second.length }
            ?.let { return Target.Playlist(it.first) }
        return Target.Songs(query!!.trim())
    }

    /** Lower case, accents folded (für → fur), punctuation dropped, spaces collapsed. */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
