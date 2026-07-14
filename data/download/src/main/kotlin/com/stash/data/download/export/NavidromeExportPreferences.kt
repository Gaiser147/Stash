package com.stash.data.download.export

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.auth.crypto.TinkEncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.navidromeExportDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "navidrome_export_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class NavidromeExportConfig(
    val enabled: Boolean,
    val serverUrl: String,
    val token: String,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long,
    val lastResult: String,
) {
    val tokenConfigured: Boolean get() = token.isNotBlank()
    val configured: Boolean get() =
        enabled && NavidromeEndpoint.normalize(serverUrl) != null && tokenConfigured
}

object NavidromeEndpoint {
    /**
     * Accept only an origin/path over HTTPS, without credentials, query, or
     * fragment. The returned value has no trailing slash.
     */
    fun normalize(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.port !in listOf(-1, 443) && uri.port !in 1..65535) return null
        return value
    }
}

@Singleton
class NavidromeExportPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryption: TinkEncryptionManager,
) {
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val serverUrl = stringPreferencesKey("server_url")
        val encryptedToken = stringPreferencesKey("token_encrypted_v2")
        val legacyPlaintextToken = stringPreferencesKey("token")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val chargingOnly = booleanPreferencesKey("charging_only")
        val lastAttemptAt = longPreferencesKey("last_attempt_at")
        val lastSuccessAt = longPreferencesKey("last_success_at")
        val lastResult = stringPreferencesKey("last_result")
    }

    val config: Flow<NavidromeExportConfig> = context.navidromeExportDataStore.data.map(::decode)

    suspend fun current(): NavidromeExportConfig {
        val prefs = context.navidromeExportDataStore.data.first()
        val legacyToken = prefs[Keys.legacyPlaintextToken].orEmpty()
        if (prefs[Keys.encryptedToken].isNullOrBlank() && legacyToken.isNotBlank()) {
            val encrypted = encryptToken(legacyToken)
            context.navidromeExportDataStore.edit {
                it[Keys.encryptedToken] = encrypted
                it.remove(Keys.legacyPlaintextToken)
            }
            return decode(prefs).copy(token = legacyToken)
        }
        return decode(prefs)
    }

    suspend fun saveConnection(serverUrl: String, replacementToken: String) {
        val normalized = requireNotNull(NavidromeEndpoint.normalize(serverUrl)) {
            "Use an HTTPS ingest URL without credentials, query parameters, or a fragment."
        }
        val existing = current().token
        val token = replacementToken.trim().ifBlank { existing }
        require(token.isNotBlank()) { "An ingest token is required." }
        val encrypted = encryptToken(token)
        context.navidromeExportDataStore.edit {
            it[Keys.serverUrl] = normalized
            it[Keys.encryptedToken] = encrypted
            it.remove(Keys.legacyPlaintextToken)
            it[Keys.lastResult] = "configuration_saved"
        }
    }

    suspend fun clearConnection() {
        context.navidromeExportDataStore.edit {
            it[Keys.enabled] = false
            it.remove(Keys.serverUrl)
            it.remove(Keys.encryptedToken)
            it.remove(Keys.legacyPlaintextToken)
            it[Keys.lastResult] = "configuration_cleared"
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            val current = current()
            require(NavidromeEndpoint.normalize(current.serverUrl) != null && current.tokenConfigured) {
                "Configure the HTTPS endpoint and token before enabling export."
            }
        }
        context.navidromeExportDataStore.edit { it[Keys.enabled] = enabled }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.navidromeExportDataStore.edit { it[Keys.wifiOnly] = enabled }
    }

    suspend fun setChargingOnly(enabled: Boolean) {
        context.navidromeExportDataStore.edit { it[Keys.chargingOnly] = enabled }
    }

    suspend fun recordAttempt(now: Long = System.currentTimeMillis()) {
        context.navidromeExportDataStore.edit { it[Keys.lastAttemptAt] = now }
    }

    suspend fun recordResult(result: String, successful: Boolean, now: Long = System.currentTimeMillis()) {
        context.navidromeExportDataStore.edit {
            it[Keys.lastAttemptAt] = now
            it[Keys.lastResult] = result.take(80)
            if (successful) it[Keys.lastSuccessAt] = now
        }
    }

    private fun decode(prefs: Preferences): NavidromeExportConfig {
        val encrypted = prefs[Keys.encryptedToken]
        val token = when {
            !encrypted.isNullOrBlank() -> decryptToken(encrypted)
            else -> prefs[Keys.legacyPlaintextToken].orEmpty()
        }
        return NavidromeExportConfig(
            enabled = prefs[Keys.enabled] ?: false,
            serverUrl = prefs[Keys.serverUrl].orEmpty(),
            token = token,
            wifiOnly = prefs[Keys.wifiOnly] ?: true,
            chargingOnly = prefs[Keys.chargingOnly] ?: true,
            lastAttemptAt = prefs[Keys.lastAttemptAt] ?: 0L,
            lastSuccessAt = prefs[Keys.lastSuccessAt] ?: 0L,
            lastResult = prefs[Keys.lastResult].orEmpty(),
        )
    }

    private fun encryptToken(token: String): String = Base64.encodeToString(
        encryption.encrypt(token.toByteArray(Charsets.UTF_8)),
        Base64.NO_WRAP,
    )

    private fun decryptToken(value: String): String = runCatching {
        val encrypted = Base64.decode(value, Base64.NO_WRAP)
        encryption.decrypt(encrypted).toString(Charsets.UTF_8)
    }.getOrDefault("")
}
