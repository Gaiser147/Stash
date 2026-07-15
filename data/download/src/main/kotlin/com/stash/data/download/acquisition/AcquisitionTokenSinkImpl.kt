package com.stash.data.download.acquisition

import com.stash.core.common.AcquisitionTokenSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a Companion-delivered acquisition token into the encrypted
 * acquisition preferences so the WorkManager jobs pick it up. See
 * [AcquisitionTokenSink].
 */
@Singleton
class AcquisitionTokenSinkImpl @Inject constructor(
    private val preferences: MuseAcquisitionPreferences,
) : AcquisitionTokenSink {
    override suspend fun acceptCompanionAcquisitionToken(serverUrl: String?, token: String) {
        // Fall back to the endpoint the user already configured when Muse did
        // not supply one (older server, or endpoint managed manually). Without
        // any endpoint we cannot persist a usable connection, so skip silently
        // and let the user configure the URL in Settings.
        val endpoint = serverUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: preferences.current().serverUrl.takeIf { it.isNotEmpty() }
            ?: return
        preferences.saveFromCompanion(endpoint, token)
    }
}
