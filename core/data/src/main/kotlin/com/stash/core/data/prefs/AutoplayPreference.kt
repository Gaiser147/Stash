package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.autoplay.DiscoveryAcceptance
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.autoplayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "autoplay_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Autoplay settings: the on/off toggle (default on — like Spotify, the music
 * keeps going when a playlist or album ends) and the learned long-term
 * [DiscoveryAcceptance] belief. Nothing here leaves the device.
 */
@Singleton
class AutoplayPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("autoplay_enabled")
    private val alphaKey = floatPreferencesKey("discovery_alpha")
    private val betaKey = floatPreferencesKey("discovery_beta")

    val enabled: Flow<Boolean> = context.autoplayDataStore.data.map { prefs ->
        prefs[enabledKey] ?: true
    }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        context.autoplayDataStore.edit { it[enabledKey] = value }
    }

    suspend fun acceptance(): DiscoveryAcceptance {
        val prefs = context.autoplayDataStore.data.first()
        return DiscoveryAcceptance(
            alpha = prefs[alphaKey] ?: DiscoveryAcceptance.PRIOR,
            beta = prefs[betaKey] ?: DiscoveryAcceptance.PRIOR,
        )
    }

    /** Atomically applies [transform] to the stored belief. */
    suspend fun updateAcceptance(transform: (DiscoveryAcceptance) -> DiscoveryAcceptance) {
        context.autoplayDataStore.edit { prefs ->
            val current = DiscoveryAcceptance(
                alpha = prefs[alphaKey] ?: DiscoveryAcceptance.PRIOR,
                beta = prefs[betaKey] ?: DiscoveryAcceptance.PRIOR,
            )
            val next = transform(current)
            prefs[alphaKey] = next.alpha
            prefs[betaKey] = next.beta
        }
    }
}
