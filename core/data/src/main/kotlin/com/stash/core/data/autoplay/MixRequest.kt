package com.stash.core.data.autoplay

import com.stash.core.data.mix.GenreCatalog
import com.stash.core.data.mix.MoodTagMap

/**
 * What the listener asked for when building a "Mix für mich": a mood, a
 * pace, genres, artists and how much new music. Every field is optional; an
 * empty request is the plain personal mix.
 *
 * @property energy wanted energy band in [0, 1] ([AudioFlow.energy]).
 * @property bpmMin / bpmMax tempo limits.
 * @property moods [MoodTagMap] keys.
 * @property genres [GenreCatalog] genre tags (lowercase Last.fm tags).
 * @property artists artist names as typed or picked.
 * @property familiarity 0 = only new songs … 1 = only songs from the
 *   library; null = learned default.
 */
data class MixRequest(
    val energy: ClosedFloatingPointRange<Float>? = null,
    val bpmMin: Float? = null,
    val bpmMax: Float? = null,
    val moods: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val artists: List<String> = emptyList(),
    val familiarity: Float? = null,
    val arc: ArcShape? = null,
    val size: Int = DEFAULT_SIZE,
) {
    val isEmpty: Boolean
        get() = energy == null && bpmMin == null && bpmMax == null && moods.isEmpty() &&
            genres.isEmpty() && artists.isEmpty() && familiarity == null && arc == null

    /** The arc to use: asked for, else implied by the mood, else the default rise. */
    fun arcShape(): ArcShape = arc ?: when {
        "party" in moods || "energetic" in moods -> ArcShape.PEAK_EARLY
        "chill" in moods || "focus" in moods || "melancholy" in moods -> ArcShape.FLAT
        energy != null && energy.endInclusive <= 0.45f -> ArcShape.FLAT
        else -> ArcShape.RISE
    }

    /** The sound band to hold, or null when the request says nothing about sound. */
    fun corridor(): MoodCorridor? {
        val e = energy ?: moods.mapNotNull { MOOD_ENERGY[it] }.takeIf { it.isNotEmpty() }?.let { bands ->
            bands.minOf { it.start }..bands.maxOf { it.endInclusive }
        }
        if (e == null && bpmMin == null && bpmMax == null) return null
        val band = e ?: 0f..1f
        return MoodCorridor(
            energyCenter = (band.start + band.endInclusive) / 2f,
            energyWidth = ((band.endInclusive - band.start) / 2f).coerceAtLeast(0.08f),
            bpmMin = bpmMin,
            bpmMax = bpmMax,
        )
    }

    /** Tags the mix should lean towards: the moods' tags and the genres. */
    fun tags(): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        moods.flatMap { MoodTagMap.MAP[it].orEmpty() }.forEach { out[it.lowercase()] = 1f }
        genres.forEach { out[it.lowercase()] = 1f }
        return out
    }

    /** Compact one-line form for storing recent wishes; see [decode]. */
    fun encode(): String = buildList {
        energy?.let { add("e=${it.start},${it.endInclusive}") }
        bpmMin?.let { add("bmin=$it") }
        bpmMax?.let { add("bmax=$it") }
        if (moods.isNotEmpty()) add("m=" + moods.joinToString(","))
        if (genres.isNotEmpty()) add("g=" + genres.joinToString(","))
        if (artists.isNotEmpty()) add("a=" + artists.joinToString(",") { it.replace(",", " ").replace(";", " ") })
        familiarity?.let { add("f=$it") }
        arc?.let { add("arc=${it.name}") }
        add("n=${this@MixRequest.size}") // plain `size` would be the list being built
    }.joinToString(";")

    companion object {
        /** Inverse of [encode]; unknown or broken parts are ignored. */
        fun decode(s: String): MixRequest {
            var r = MixRequest()
            for (part in s.split(";")) {
                val k = part.substringBefore("=", "")
                val v = part.substringAfter("=", "")
                fun list() = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                r = when (k) {
                    "e" -> list().mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 }?.let { r.copy(energy = it[0]..it[1]) } ?: r
                    "bmin" -> r.copy(bpmMin = v.toFloatOrNull())
                    "bmax" -> r.copy(bpmMax = v.toFloatOrNull())
                    "m" -> r.copy(moods = list().toSet())
                    "g" -> r.copy(genres = list().toSet())
                    "a" -> r.copy(artists = list())
                    "f" -> r.copy(familiarity = v.toFloatOrNull())
                    "arc" -> r.copy(arc = ArcShape.entries.firstOrNull { it.name == v })
                    "n" -> r.copy(size = v.toIntOrNull()?.coerceIn(10, 100) ?: DEFAULT_SIZE)
                    else -> r
                }
            }
            return r
        }

        const val DEFAULT_SIZE = 30
        const val MAX_DISCOVERY_SHARE = 0.7f

        /** Energy band each mood implies when no explicit energy was asked for. */
        val MOOD_ENERGY: Map<String, ClosedFloatingPointRange<Float>> = mapOf(
            "chill" to 0f..0.45f,
            "focus" to 0.1f..0.5f,
            "melancholy" to 0f..0.5f,
            "romantic" to 0.1f..0.55f,
            "energetic" to 0.6f..1f,
            "party" to 0.6f..1f,
        )

        // Fixed requests for the Android Auto menu.
        val CALM = MixRequest(moods = setOf("chill"), energy = 0f..0.4f)
        val ENERGY = MixRequest(moods = setOf("energetic"), energy = 0.6f..1f)
        val FOCUS = MixRequest(moods = setOf("focus"), energy = 0.1f..0.5f, arc = ArcShape.FLAT)
        val PARTY = MixRequest(moods = setOf("party"), energy = 0.65f..1f, arc = ArcShape.PEAK_EARLY)
    }
}

/**
 * Reads a free-text wish ("ruhig, eher langsam, wie Billie Eilish", "party
 * mit neuen Songs", "nicht zu laut") into a [MixRequest]. Local, German and
 * English, no network. Artists are matched against [knownArtists] (the
 * library) first; "wie X"/"like X"/"von X"/"by X" also accepts an unknown
 * name, which then seeds discoveries.
 */
object MixRequestParser {

    /** One thing the parser understood, for the UI's chips. */
    data class Token(val kind: Kind, val value: String)

    enum class Kind { MOOD, ENERGY, TEMPO, GENRE, ARTIST, FAMILIARITY }

    data class Result(val request: MixRequest, val tokens: List<Token>)

    private data class Rule(val words: List<String>, val apply: (Builder) -> Unit)

    private class Builder {
        var energy: ClosedFloatingPointRange<Float>? = null
        var bpmMin: Float? = null
        var bpmMax: Float? = null
        val moods = LinkedHashSet<String>()
        val genres = LinkedHashSet<String>()
        val artists = ArrayList<String>()
        var familiarity: Float? = null
        val tokens = ArrayList<Token>()

        fun mood(m: String) { if (moods.add(m)) tokens += Token(Kind.MOOD, m) }
        fun energy(r: ClosedFloatingPointRange<Float>, label: String) {
            energy = energy?.let { maxOf(it.start, r.start)..minOf(it.endInclusive, r.endInclusive) }
                ?.takeIf { it.start <= it.endInclusive } ?: r
            tokens += Token(Kind.ENERGY, label)
        }
    }

    private val MOOD_RULES = listOf(
        Rule(listOf("ruhig", "ruhige", "ruhiges", "ruhigen", "entspannt", "entspannend", "entspannte", "chill", "chillig", "gechillt", "relax", "relaxed", "calm", "mellow", "sanft", "sanfte", "soft", "leise", "gemütlich")) {
            it.mood("chill")
        },
        Rule(listOf("energie", "energisch", "energiegeladen", "energetic", "power", "powerful", "workout", "sport", "training", "gym", "laufen", "running", "pump", "hype", "upbeat")) {
            it.mood("energetic")
        },
        Rule(listOf("party", "feiern", "tanzen", "tanzbar", "dance", "danceable", "club", "feier")) { it.mood("party") },
        Rule(listOf("fokus", "focus", "konzentration", "konzentrieren", "lernen", "arbeiten", "arbeit", "study", "studying", "work", "coding", "lesen")) {
            it.mood("focus")
        },
        Rule(listOf("traurig", "melancholisch", "melancholie", "sad", "melancholy", "nachdenklich", "moody", "herzschmerz", "emotional")) {
            it.mood("melancholy")
        },
        Rule(listOf("romantisch", "romantic", "liebe", "love", "kuscheln", "date")) { it.mood("romantic") },
    )

    private val SLOW = listOf("langsam", "langsame", "langsamer", "langsames", "slow", "slower", "gemächlich")
    private val FAST = listOf("schnell", "schnelle", "schneller", "schnelles", "fast", "faster", "flott", "tempo")
    private val LOUD = listOf("laut", "laute", "lauter", "loud", "hart", "harte", "heavy", "intensiv", "intense", "wild")
    private val NEW = listOf("neu", "neue", "neues", "neuen", "new", "entdecken", "discover", "discovery", "unbekannt", "unbekannte", "frisch", "fresh")
    private val KNOWN = listOf("bekannt", "bekannte", "bekannten", "lieblings", "lieblingssongs", "favoriten", "favourites", "favorites", "known", "familiar", "alte", "klassiker")
    private val NEGATIONS = setOf("nicht", "kein", "keine", "not", "no", "ohne", "without")

    private val GENRE_SYNONYMS: Map<String, String> = mapOf(
        "hiphop" to "hip-hop", "hip hop" to "hip-hop", "deutschrap" to "rap", "rnb" to "r&b", "r'n'b" to "r&b",
        "elektro" to "house", "electro" to "house", "edm" to "house", "electronic" to "house", "elektronisch" to "house",
        "dnb" to "drum and bass", "lofi" to "lo-fi", "lo fi" to "lo-fi", "klassik" to "classical",
        "klavier" to "piano", "akustisch" to "singer-songwriter", "acoustic" to "singer-songwriter",
        "kpop" to "k-pop", "indie" to "indie rock", "punkrock" to "punk",
    )

    private val ARTIST_INTRO = Regex("""\b(?:wie|like|von|by|ähnlich wie|similar to|im stil von|à la|a la)\s+(.+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String, knownArtists: Collection<String> = emptyList()): Result {
        val b = Builder()
        val lower = text.lowercase()
        val words = lower.split(Regex("""[^\p{L}\p{N}&'-]+""")).filter { it.isNotBlank() }
        fun negated(i: Int) = (1..2).any { d -> i - d >= 0 && words[i - d] in NEGATIONS }

        words.forEachIndexed { i, w ->
            val neg = negated(i)
            when {
                w in SLOW -> if (neg) { b.bpmMin = 95f; b.tokens += Token(Kind.TEMPO, "not slow") }
                    else { b.bpmMax = 105f; b.tokens += Token(Kind.TEMPO, "slow") }
                w in FAST -> if (neg) { b.bpmMax = 120f; b.tokens += Token(Kind.TEMPO, "not fast") }
                    else { b.bpmMin = 118f; b.tokens += Token(Kind.TEMPO, "fast") }
                w in LOUD -> if (neg) b.energy(0f..0.6f, "not loud") else b.energy(0.6f..1f, "loud")
                w in NEW -> { b.familiarity = 0.25f; b.tokens += Token(Kind.FAMILIARITY, "new") }
                w in KNOWN || w.startsWith("lieblings") -> { b.familiarity = 0.9f; b.tokens += Token(Kind.FAMILIARITY, "known") }
                else -> MOOD_RULES.firstOrNull { w in it.words }?.let { rule ->
                    if (!neg) rule.apply(b)
                }
            }
        }
        if (b.moods.contains("chill") && b.energy == null) b.energy = 0f..0.45f

        // Genres: catalog tags (multi-word ones too) and common synonyms.
        val genreNames = GenreCatalog.allGenres()
        for (g in genreNames.sortedByDescending { it.length }) {
            if (Regex("""(^|[^\p{L}])${Regex.escape(g)}($|[^\p{L}])""").containsMatchIn(lower) && b.genres.none { it.contains(g) }) {
                b.genres += g
                b.tokens += Token(Kind.GENRE, g)
            }
        }
        for ((syn, g) in GENRE_SYNONYMS) {
            if (Regex("""(^|[^\p{L}])${Regex.escape(syn)}($|[^\p{L}])""").containsMatchIn(lower) && g !in b.genres) {
                b.genres += g
                b.tokens += Token(Kind.GENRE, g)
            }
        }

        // Artists: library names mentioned anywhere, plus "like X" phrases.
        val canon = knownArtists.filter { it.isNotBlank() }.associateBy { norm(it) }
        for ((key, name) in canon.entries.sortedByDescending { it.key.length }) {
            if (key.length < MIN_ARTIST_LEN) continue
            if (Regex("""(^|\s)${Regex.escape(key)}($|\s)""").containsMatchIn(norm(text)) && b.artists.none { norm(it) == key }) {
                b.artists += name
                b.tokens += Token(Kind.ARTIST, name)
            }
        }
        ARTIST_INTRO.findAll(text).forEach { m ->
            m.groupValues[1].split(Regex("""\s*(?:,|\bund\b|\band\b|&|\boder\b|\bor\b)\s*""", RegexOption.IGNORE_CASE))
                .map { it.trim().trimEnd('.', '!', '?') }
                .filter { it.length >= MIN_ARTIST_LEN && it.split(" ").size <= 4 }
                .filter { part -> !isVocabulary(part) }
                .forEach { part ->
                    val match = canon[norm(part)] ?: fuzzy(part, canon)
                    val name = match ?: part.split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                    if (b.artists.none { norm(it) == norm(name) }) {
                        b.artists += name
                        b.tokens += Token(Kind.ARTIST, name)
                    }
                }
        }

        return Result(
            MixRequest(
                energy = b.energy,
                bpmMin = b.bpmMin,
                bpmMax = b.bpmMax,
                moods = b.moods,
                genres = b.genres,
                artists = b.artists,
                familiarity = b.familiarity,
            ),
            b.tokens,
        )
    }

    private const val MIN_ARTIST_LEN = 3

    /** True when a phrase is only words the parser already handles ("wie neu"). */
    private fun isVocabulary(phrase: String): Boolean {
        val ws = phrase.lowercase().split(Regex("""\s+"""))
        val vocab = SLOW + FAST + LOUD + NEW + KNOWN + NEGATIONS + MOOD_RULES.flatMap { it.words } +
            listOf("eher", "mehr", "musik", "music", "songs", "lieder", "etwas", "something", "immer", "heute", "bitte")
        return ws.all { it in vocab }
    }

    internal fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Close spelling of a library artist (≥ 0.85 similarity), for typos like "bilie eilish". */
    private fun fuzzy(part: String, canon: Map<String, String>): String? {
        val p = norm(part)
        return canon.entries
            .map { it to similarity(p, it.key) }
            .filter { it.second >= 0.85f }
            .maxByOrNull { it.second }?.first?.value
    }

    internal fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1f
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1f - d[b.length].toFloat() / max
    }
}
