package com.stash.feature.settings.navidrome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.data.download.navidrome.NavidromeServerPreferences
import com.stash.data.download.navidrome.SubsonicClient
import com.stash.data.download.navidrome.SubsonicException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NavidromeAccountUiState(
    val serverUrl: String = "",
    val username: String = "",
    val passwordConfigured: Boolean = false,
    val passwordError: Boolean = false,
    val scrobbleEnabled: Boolean = true,
    val pendingScrobbles: Int = 0,
    val checking: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val configured: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && passwordConfigured
}

/**
 * Settings for the user's Navidrome account (Subsonic API): connect, test,
 * and toggle reporting plays to the server. Kept apart from the large
 * SettingsViewModel; it only touches the Navidrome account.
 */
@HiltViewModel
class NavidromeAccountViewModel @Inject constructor(
    private val preferences: NavidromeServerPreferences,
    private val client: SubsonicClient,
    listeningEventDao: ListeningEventDao,
) : ViewModel() {

    private val transient = MutableStateFlow(NavidromeAccountUiState())

    val uiState: StateFlow<NavidromeAccountUiState> = combine(
        preferences.config,
        listeningEventDao.pendingNavidromeScrobbleCount(),
        transient,
    ) { config, pending, t ->
        t.copy(
            serverUrl = config.serverUrl,
            username = config.username,
            passwordConfigured = config.password.isNotBlank(),
            passwordError = config.passwordDecryptionFailed,
            scrobbleEnabled = config.scrobbleEnabled,
            pendingScrobbles = pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavidromeAccountUiState())

    fun save(url: String, username: String, password: String) {
        viewModelScope.launch {
            runCatching { preferences.saveConnection(url, username, password) }
                .onSuccess { test() }
                .onFailure { show(it.message ?: "Couldn't save the account", error = true) }
        }
    }

    fun test() {
        viewModelScope.launch {
            transient.update { it.copy(checking = true, message = null) }
            val config = preferences.current()
            val result = runCatching { client.ping(config) }
            transient.update { it.copy(checking = false) }
            result
                .onSuccess { info ->
                    val songs = info.songCount?.let { " · $it songs" }.orEmpty()
                    show("Connected to Navidrome ${info.serverVersion.substringBefore(' ')}$songs")
                }
                .onFailure { e ->
                    show(
                        when {
                            e is SubsonicException && e.code == 40 -> "Wrong username or password"
                            e is SubsonicException -> "Navidrome refused: ${e.message}"
                            else -> "Server not reachable — check the URL and your connection"
                        },
                        error = true,
                    )
                }
        }
    }

    fun setScrobbleEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setScrobbleEnabled(enabled) }
    }

    fun clear() {
        viewModelScope.launch {
            preferences.clearConnection()
            show("Navidrome account removed")
        }
    }

    fun clearMessage() = transient.update { it.copy(message = null) }

    private fun show(message: String, error: Boolean = false) =
        transient.update { it.copy(message = message, messageIsError = error) }
}
