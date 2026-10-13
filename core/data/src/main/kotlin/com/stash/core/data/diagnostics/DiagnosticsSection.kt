package com.stash.core.data.diagnostics

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * An extra section of the diagnostics bundle contributed by another module
 * (bound `@IntoSet`), e.g. download timings from `:data:download`, which
 * `:core:data` can't depend on.
 */
interface DiagnosticsSection {
    val title: String
    fun render(): String
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsSectionModule {
    /** Empty default so the set always injects. */
    @Multibinds
    abstract fun diagnosticsSections(): Set<DiagnosticsSection>
}
