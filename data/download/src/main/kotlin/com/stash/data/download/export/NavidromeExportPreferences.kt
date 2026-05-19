package com.stash.data.download.export

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.navidromeExportDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "navidrome_export_preferences",
)

data class NavidromeExportConfig(
    val enabled: Boolean,
    val serverUrl: String,
    val token: String,
) {
    val configured: Boolean get() = enabled && serverUrl.isNotBlank() && token.isNotBlank()
}

object NavidromeExportDefaults {
    const val SERVER_URL = "https://learnwithclawdbot.com/stash-ingest"
}

@Singleton
class NavidromeExportPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
    }

    val config: Flow<NavidromeExportConfig> = context.navidromeExportDataStore.data.map { prefs ->
        NavidromeExportConfig(
            enabled = prefs[Keys.enabled] ?: true,
            serverUrl = prefs[Keys.serverUrl] ?: NavidromeExportDefaults.SERVER_URL,
            token = prefs[Keys.token].orEmpty(),
        )
    }

    suspend fun current(): NavidromeExportConfig = config.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.navidromeExportDataStore.edit { prefs -> prefs[Keys.enabled] = enabled }
    }

    suspend fun setServerUrl(url: String) {
        context.navidromeExportDataStore.edit { prefs -> prefs[Keys.serverUrl] = url.trim() }
    }

    suspend fun setToken(token: String) {
        context.navidromeExportDataStore.edit { prefs -> prefs[Keys.token] = token.trim() }
    }
}
