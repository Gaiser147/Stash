package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.autoplay.MixRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.mixRequestDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mix_request_history",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The last few mix wishes, newest first, as quick picks in the wish sheet. Stays on the device. */
@Singleton
class MixRequestHistory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("recent")

    val recent: Flow<List<MixRequest>> = context.mixRequestDataStore.data.map { prefs ->
        prefs[key].orEmpty().split("\n").filter { it.isNotBlank() }.map(MixRequest::decode)
    }

    suspend fun remember(request: MixRequest) {
        if (request.isEmpty) return
        context.mixRequestDataStore.edit { prefs ->
            val line = request.encode()
            val old = prefs[key].orEmpty().split("\n").filter { it.isNotBlank() && it != line }
            prefs[key] = (listOf(line) + old).take(MAX).joinToString("\n")
        }
    }

    companion object {
        const val MAX = 5
    }
}
