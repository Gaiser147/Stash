package com.stash.data.download.acquisition

import com.stash.core.model.TrackItem
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import com.stash.data.ytmusic.model.TrackSummary
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MuseAcquisitionMatcher @Inject constructor(
    private val api: YTMusicApiClient,
) {
    /**
     * Resolve only a unique, high-confidence YouTube Music result. Ambiguous
     * titles (for example a query containing only "Hello") deliberately
     * return null instead of downloading the first catalog hit.
     */
    suspend fun findConfidentMatch(query: String): TrackItem? {
        val results = api.searchAll(query)
        val candidates = buildList {
            results.sections.forEach { section ->
                when (section) {
                    is SearchResultSection.Top -> {
                        val item = section.item
                        if (item is TopResultItem.TrackTop) add(item.track)
                    }
                    is SearchResultSection.Songs -> addAll(section.tracks)
                    else -> Unit
                }
            }
        }.distinctBy(TrackSummary::videoId)

        return MuseAcquisitionMatchScorer.best(query, candidates)?.toTrackItem()
    }

    private fun TrackSummary.toTrackItem() = TrackItem(
        videoId = videoId,
        title = title,
        artist = artist,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
        album = album,
    )
}

internal object MuseAcquisitionMatchScorer {
    private const val MIN_SCORE = 0.86
    private const val MIN_MARGIN = 0.12

    fun best(query: String, candidates: List<TrackSummary>): TrackSummary? {
        val ranked = candidates
            .map { candidate -> candidate to score(query, candidate) }
            .sortedByDescending { it.second }
        val top = ranked.firstOrNull() ?: return null
        val runnerUpScore = ranked.getOrNull(1)?.second ?: 0.0
        return top.first.takeIf { top.second >= MIN_SCORE && top.second - runnerUpScore >= MIN_MARGIN }
    }

    internal fun score(query: String, candidate: TrackSummary): Double {
        val normalizedQuery = normalize(query)
        val normalizedTitle = normalize(candidate.title)
        val normalizedArtist = normalize(candidate.artist)
        if (normalizedQuery.isBlank() || normalizedTitle.isBlank()) return 0.0

        val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank).toSet()
        val candidateTokens = "$normalizedTitle $normalizedArtist".split(' ').filter(String::isNotBlank).toSet()
        if (queryTokens.isEmpty()) return 0.0
        val coverage = queryTokens.count(candidateTokens::contains).toDouble() / queryTokens.size
        val phraseMatch = normalizedQuery == "$normalizedArtist $normalizedTitle" ||
            normalizedQuery == "$normalizedTitle $normalizedArtist"
        val titleExact = normalizedQuery == normalizedTitle
        val titleContained = normalizedQuery.contains(normalizedTitle) || normalizedTitle.contains(normalizedQuery)

        return when {
            phraseMatch -> 1.0
            titleExact -> 0.92
            else -> (coverage * 0.82 + if (titleContained) 0.08 else 0.0).coerceAtMost(0.99)
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
