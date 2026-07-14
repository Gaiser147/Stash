package com.stash.data.download.acquisition

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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.museAcquisitionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "muse_acquisition_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class MuseAcquisitionConfig(
    val enabled: Boolean,
    val serverUrl: String,
    val token: String,
    val consumerId: String,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long,
    val lastResult: String,
    val pendingCount: Int,
    val tokenDecryptionFailed: Boolean = false,
) {
    val tokenConfigured: Boolean get() = token.isNotBlank()
    val configured: Boolean get() =
        enabled && MuseAcquisitionEndpoint.normalize(serverUrl) != null && tokenConfigured
}

object MuseAcquisitionEndpoint {
    /**
     * Accept only HTTPS endpoints without embedded credentials, query, or
     * fragment. The Muse service is intended to sit behind a private TLS
     * reverse proxy (for example Tailscale Serve), never on the public HTTP
     * listener directly.
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
class MuseAcquisitionPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryption: TinkEncryptionManager,
) {
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val serverUrl = stringPreferencesKey("server_url")
        val encryptedToken = stringPreferencesKey("token_encrypted_v1")
        val consumerId = stringPreferencesKey("consumer_id")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val chargingOnly = booleanPreferencesKey("charging_only")
        val lastAttemptAt = longPreferencesKey("last_attempt_at")
        val lastSuccessAt = longPreferencesKey("last_success_at")
        val lastResult = stringPreferencesKey("last_result")
        val pendingCount = androidx.datastore.preferences.core.intPreferencesKey("pending_count")
    }

    val config: Flow<MuseAcquisitionConfig> = context.museAcquisitionDataStore.data.map(::decode)

    suspend fun current(): MuseAcquisitionConfig {
        val prefs = context.museAcquisitionDataStore.data.first()
        if (prefs[Keys.consumerId].isNullOrBlank()) {
            val generated = "stash-${UUID.randomUUID()}"
            val updated = context.museAcquisitionDataStore.edit {
                if (it[Keys.consumerId].isNullOrBlank()) it[Keys.consumerId] = generated
            }
            return decode(updated)
        }
        return decode(prefs)
    }

    suspend fun saveConnection(serverUrl: String, replacementToken: String) {
        val normalized = requireNotNull(MuseAcquisitionEndpoint.normalize(serverUrl)) {
            "Use a private HTTPS Muse inbox URL without credentials, query parameters, or a fragment."
        }
        val existing = current().token
        val token = replacementToken.trim().ifBlank { existing }
        require(token.length >= MIN_TOKEN_LENGTH) { "The Muse inbox token must contain at least 32 characters." }
        context.museAcquisitionDataStore.edit {
            it[Keys.serverUrl] = normalized
            it[Keys.encryptedToken] = encryptToken(token)
            it[Keys.lastResult] = RESULT_CONFIGURATION_SAVED
        }
    }

    suspend fun clearConnection() {
        context.museAcquisitionDataStore.edit {
            it[Keys.enabled] = false
            it.remove(Keys.serverUrl)
            it.remove(Keys.encryptedToken)
            it[Keys.pendingCount] = 0
            it[Keys.lastResult] = RESULT_CONFIGURATION_CLEARED
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            val current = current()
            require(MuseAcquisitionEndpoint.normalize(current.serverUrl) != null && current.tokenConfigured) {
                "Configure the private Muse inbox first."
            }
        }
        context.museAcquisitionDataStore.edit { it[Keys.enabled] = enabled }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.museAcquisitionDataStore.edit { it[Keys.wifiOnly] = enabled }
    }

    suspend fun setChargingOnly(enabled: Boolean) {
        context.museAcquisitionDataStore.edit { it[Keys.chargingOnly] = enabled }
    }

    suspend fun recordAttempt(pendingCount: Int, now: Long = System.currentTimeMillis()) {
        context.museAcquisitionDataStore.edit {
            it[Keys.lastAttemptAt] = now
            it[Keys.pendingCount] = pendingCount.coerceIn(0, MAX_PENDING_COUNT)
            it[Keys.lastResult] = RESULT_POLL_COMPLETE
        }
    }

    suspend fun recordResult(
        result: String,
        successful: Boolean,
        pendingCount: Int? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        context.museAcquisitionDataStore.edit {
            it[Keys.lastAttemptAt] = now
            it[Keys.lastResult] = result.take(MAX_RESULT_LENGTH)
            pendingCount?.let { count -> it[Keys.pendingCount] = count.coerceIn(0, MAX_PENDING_COUNT) }
            if (successful) it[Keys.lastSuccessAt] = now
        }
    }

    private fun decode(prefs: Preferences): MuseAcquisitionConfig {
        val encrypted = prefs[Keys.encryptedToken]
        val decryptedToken = encrypted?.takeIf(String::isNotBlank)?.let(::decryptToken)
        return MuseAcquisitionConfig(
            enabled = prefs[Keys.enabled] ?: false,
            serverUrl = prefs[Keys.serverUrl].orEmpty(),
            token = decryptedToken.orEmpty(),
            consumerId = prefs[Keys.consumerId].orEmpty(),
            wifiOnly = prefs[Keys.wifiOnly] ?: true,
            chargingOnly = prefs[Keys.chargingOnly] ?: true,
            lastAttemptAt = prefs[Keys.lastAttemptAt] ?: 0L,
            lastSuccessAt = prefs[Keys.lastSuccessAt] ?: 0L,
            lastResult = prefs[Keys.lastResult].orEmpty(),
            pendingCount = prefs[Keys.pendingCount] ?: 0,
            tokenDecryptionFailed = !encrypted.isNullOrBlank() && decryptedToken == null,
        )
    }

    private fun encryptToken(token: String): String = Base64.encodeToString(
        encryption.encrypt(token.toByteArray(Charsets.UTF_8)),
        Base64.NO_WRAP,
    )

    private fun decryptToken(value: String): String? = runCatching {
        encryption.decrypt(Base64.decode(value, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val RESULT_CONFIGURATION_SAVED = "configuration_saved"
        const val RESULT_CONFIGURATION_CLEARED = "configuration_cleared"
        const val RESULT_POLL_COMPLETE = "poll_complete"
        const val RESULT_TRACK_DOWNLOADED = "track_downloaded"
        const val RESULT_NO_CONFIDENT_MATCH = "no_confident_match"
        const val RESULT_WAITING_FOR_LOSSLESS = "waiting_for_lossless"
        const val RESULT_DOWNLOAD_FAILED = "download_failed"
        const val RESULT_CONNECTION_FAILED = "connection_failed"
        private const val MIN_TOKEN_LENGTH = 32
        private const val MAX_RESULT_LENGTH = 80
        private const val MAX_PENDING_COUNT = 50
    }
}
