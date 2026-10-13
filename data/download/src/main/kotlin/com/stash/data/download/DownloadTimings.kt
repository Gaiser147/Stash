package com.stash.data.download

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the time of each download went, for the diagnostics bundle: the last
 * [CAPACITY] songs, one [Entry] each. Shared singleton; thread-safe.
 */
@Singleton
class DownloadTimings @Inject constructor() {

    /** One finished (or given-up) download. Phase times in ms; 0 = phase not run. */
    data class Entry(
        val atMs: Long,
        val outcome: String,
        val source: String?,
        val waitSlotMs: Long = 0,
        val ownServerMs: Long = 0,
        val losslessResolveMs: Long = 0,
        val fetchMs: Long = 0,
        val youtubeResolveMs: Long = 0,
        val ytDlpMs: Long = 0,
        val finalizeMs: Long = 0,
        val totalMs: Long = 0,
    ) {
        fun logLine(title: String): String =
            "DLTIME outcome=$outcome source=${source ?: "-"} total=$totalMs wait=$waitSlotMs own=$ownServerMs " +
                "lossless=$losslessResolveMs fetch=$fetchMs ytResolve=$youtubeResolveMs ytdlp=$ytDlpMs " +
                "finalize=$finalizeMs '$title'"
    }

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(entry: Entry) {
        if (entries.size == CAPACITY) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    /** Human-readable summary for the diagnostics bundle. */
    fun summary(): String {
        val all = snapshot()
        if (all.isEmpty()) return "no downloads recorded since app start"
        val ok = all.filter { it.outcome == "ok" }
        return buildString {
            append("last ${all.size} downloads: ${ok.size} ok, ")
            append(all.groupingBy { it.outcome }.eachCount().filterKeys { it != "ok" }.entries.joinToString { "${it.value} ${it.key}" }.ifEmpty { "0 other" })
            append('\n')
            val spanMs = (all.last().atMs - all.first().atMs).coerceAtLeast(1)
            if (all.size >= 2) append("throughput: ${"%.1f".format(ok.size * 60_000.0 / spanMs)} songs/min\n")
            append("sources: ")
            append(ok.groupingBy { it.source ?: "?" }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
            append('\n')
            fun row(name: String, pick: (Entry) -> Long) {
                val values = all.map(pick).filter { it > 0 }
                if (values.isEmpty()) return
                append("$name: median ${percentile(values, 50)} ms, p90 ${percentile(values, 90)} ms (n=${values.size})\n")
            }
            row("total", Entry::totalMs)
            row("wait for slot", Entry::waitSlotMs)
            row("own server", Entry::ownServerMs)
            row("lossless resolve", Entry::losslessResolveMs)
            row("file fetch", Entry::fetchMs)
            row("youtube resolve", Entry::youtubeResolveMs)
            row("yt-dlp", Entry::ytDlpMs)
            row("tag + store", Entry::finalizeMs)
        }.trimEnd()
    }

    companion object {
        const val CAPACITY = 100

        /** Nearest-rank percentile of [values] (non-empty). */
        internal fun percentile(values: List<Long>, p: Int): Long {
            val sorted = values.sorted()
            val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
