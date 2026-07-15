package com.stash.core.common

/**
 * Bridge that lets the Muse companion feature hand a device-bound acquisition
 * token — delivered in the pairing poll response — to the acquisition
 * preferences store, without `:feature:muse` depending on `:data:download`.
 *
 * The implementation lives in `:data:download` (where the encrypted store is)
 * and is provided via Hilt.
 */
interface AcquisitionTokenSink {
    /**
     * Persist an acquisition endpoint + device token delivered over Companion
     * pairing. A blank [serverUrl] means Muse did not supply an endpoint; the
     * implementation should then keep any endpoint the user configured
     * manually. Scheduling is not auto-enabled.
     */
    suspend fun acceptCompanionAcquisitionToken(serverUrl: String?, token: String)
}
