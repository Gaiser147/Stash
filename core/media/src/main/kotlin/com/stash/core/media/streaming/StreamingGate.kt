package com.stash.core.media.streaming

import com.stash.core.data.prefs.StreamingPreference
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * One answer to "may a song that isn't downloaded play right now?", shared by
 * the in-app player and the Android Auto browse tree so both build the same
 * queues: online mode on, a network up, and cellular allowed if that's what
 * the phone is on.
 */
class StreamingGate @Inject constructor(
    private val streamingPreference: StreamingPreference,
    private val connectivity: ConnectivityMonitor,
) {
    suspend fun canStreamNow(): Boolean =
        streamingPreference.current() && connectivity.isConnected() &&
            (!connectivity.isCellular() || streamingPreference.streamOnCellular.first())
}
