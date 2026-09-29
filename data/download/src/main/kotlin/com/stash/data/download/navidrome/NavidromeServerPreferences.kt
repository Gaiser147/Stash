package com.stash.data.download.navidrome

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.auth.crypto.TinkEncryptionManager
import com.stash.data.download.export.NavidromeEndpoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.navidromeServerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "navidrome_server_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The user's Navidrome account, used to talk to the server's Subsonic API
 * directly (reporting plays today; streaming later). Separate from
 * [com.stash.data.download.export.NavidromeExportConfig], which is the
 * stash-ingest upload endpoint + bearer token and a different service.
 */
data class NavidromeServerConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val scrobbleEnabled: Boolean,
    val passwordDecryptionFailed: Boolean = false,
) {
    val configured: Boolean get() =
        NavidromeEndpoint.normalize(serverUrl) != null && username.isNotBlank() && password.isNotBlank()
}

/**
 * Stores the Navidrome account. The password is encrypted with the same
 * Tink AES-256-GCM / Android Keystore mechanism as the ingest token, and is
 * never handed back to the UI. The Subsonic token scheme
 * (`t = md5(password + salt)`) needs the plain password on the device;
 * Navidrome 0.63 offers no API keys.
 */
@Singleton
class NavidromeServerPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryption: TinkEncryptionManager,
) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val username = stringPreferencesKey("username")
        val encryptedPassword = stringPreferencesKey("password_encrypted_v1")
        val scrobbleEnabled = booleanPreferencesKey("scrobble_enabled")
    }

    val config: Flow<NavidromeServerConfig> = context.navidromeServerDataStore.data.map(::decode)

    suspend fun current(): NavidromeServerConfig = decode(context.navidromeServerDataStore.data.first())

    /** A blank [replacementPassword] keeps the stored one (the UI never reads it back). */
    suspend fun saveConnection(serverUrl: String, username: String, replacementPassword: String) {
        val normalized = requireNotNull(NavidromeEndpoint.normalize(serverUrl)) {
            "Use an HTTPS server URL without credentials, query parameters, or a fragment."
        }
        val user = username.trim()
        require(user.isNotBlank()) { "A Navidrome username is required." }
        val password = replacementPassword.ifBlank { current().password }
        require(password.isNotBlank()) { "A Navidrome password is required." }
        val encrypted = Base64.encodeToString(
            encryption.encrypt(password.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        context.navidromeServerDataStore.edit {
            it[Keys.serverUrl] = normalized
            it[Keys.username] = user
            it[Keys.encryptedPassword] = encrypted
        }
    }

    suspend fun clearConnection() {
        context.navidromeServerDataStore.edit {
            it.remove(Keys.serverUrl)
            it.remove(Keys.username)
            it.remove(Keys.encryptedPassword)
        }
    }

    suspend fun setScrobbleEnabled(enabled: Boolean) {
        context.navidromeServerDataStore.edit { it[Keys.scrobbleEnabled] = enabled }
    }

    private fun decode(prefs: Preferences): NavidromeServerConfig {
        val encrypted = prefs[Keys.encryptedPassword]
        val password = encrypted?.takeIf(String::isNotBlank)?.let { value ->
            runCatching {
                encryption.decrypt(Base64.decode(value, Base64.NO_WRAP)).toString(Charsets.UTF_8)
            }.getOrNull()
        }
        return NavidromeServerConfig(
            serverUrl = prefs[Keys.serverUrl].orEmpty(),
            username = prefs[Keys.username].orEmpty(),
            password = password.orEmpty(),
            scrobbleEnabled = prefs[Keys.scrobbleEnabled] ?: true,
            passwordDecryptionFailed = !encrypted.isNullOrBlank() && password == null,
        )
    }
}
