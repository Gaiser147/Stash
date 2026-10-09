package com.stash.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadTimingsTest {

    private fun entry(at: Long, total: Long, outcome: String = "ok", source: String? = "youtube", ytdlp: Long = 0) =
        DownloadTimings.Entry(atMs = at, outcome = outcome, source = source, totalMs = total, ytDlpMs = ytdlp)

    @Test fun `percentile is nearest-rank`() {
        val values = (1L..10L).map { it * 100 }
        assertThat(DownloadTimings.percentile(values, 50)).isEqualTo(500)
        assertThat(DownloadTimings.percentile(values, 90)).isEqualTo(900)
        assertThat(DownloadTimings.percentile(listOf(42L), 90)).isEqualTo(42)
    }

    @Test fun `keeps only the last hundred downloads`() {
        val t = DownloadTimings()
        repeat(150) { t.record(entry(it.toLong(), 1)) }
        assertThat(t.snapshot()).hasSize(DownloadTimings.CAPACITY)
        assertThat(t.snapshot().first().atMs).isEqualTo(50)
    }

    @Test fun `summary shows throughput, sources and per-phase numbers`() {
        val t = DownloadTimings()
        t.record(entry(0, 10_000, source = "navidrome"))
        t.record(entry(30_000, 40_000, ytdlp = 30_000))
        t.record(entry(60_000, 5_000, outcome = "unmatched", source = null))

        val text = t.summary()

        assertThat(text).contains("last 3 downloads: 2 ok, 1 unmatched")
        assertThat(text).contains("throughput: 2.0 songs/min")
        assertThat(text).contains("navidrome 1")
        assertThat(text).contains("yt-dlp: median 30000 ms")
    }

    @Test fun `nothing recorded says so`() {
        assertThat(DownloadTimings().summary()).contains("no downloads recorded")
    }
}
