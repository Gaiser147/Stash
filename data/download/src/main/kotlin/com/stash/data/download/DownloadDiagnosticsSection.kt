package com.stash.data.download

import com.stash.core.data.diagnostics.DiagnosticsSection
import com.stash.data.download.lossless.AggregatorRateLimiter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

/** "Download timing" in the diagnostics bundle: where each song's time went. */
class DownloadDiagnosticsSection @Inject constructor(
    private val timings: DownloadTimings,
    private val rateLimiter: AggregatorRateLimiter,
) : DiagnosticsSection {
    override val title = "Download timing"

    override fun render(): String =
        timings.summary() + "\nwaited on lossless rate limits: " + rateLimiter.waitSummary()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadDiagnosticsModule {
    @Binds
    @IntoSet
    abstract fun bindDownloadTimingSection(impl: DownloadDiagnosticsSection): DiagnosticsSection
}
