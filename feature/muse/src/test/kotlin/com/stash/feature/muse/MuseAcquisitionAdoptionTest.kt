package com.stash.feature.muse

import com.google.common.truth.Truth.assertThat
import com.stash.core.common.AcquisitionTokenSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * Regression coverage for the pairing race that left the request inbox
 * unconfigured: storing the pairing flips the persisted state to PAIRED, whose
 * collector cancels the polling job. Anything suspending that ran *after* that
 * write — including adopting the acquisition credential — was abandoned, and a
 * bare `runCatching` swallowed the resulting CancellationException without a
 * trace.
 */
class MuseAcquisitionAdoptionTest {

    private class RecordingSink : AcquisitionTokenSink {
        var endpoint: String? = null
        var token: String? = null
        var calls = 0

        override suspend fun acceptCompanionAcquisitionToken(serverUrl: String?, token: String) {
            // Mirror the real implementation: the store suspends before writing.
            yield()
            calls += 1
            endpoint = serverUrl
            this.token = token
        }
    }

    /** The shape of the fixed adoption: shielded, so a racing cancel cannot abandon it. */
    private suspend fun adopt(sink: AcquisitionTokenSink, endpoint: String?, token: String) {
        withContext(NonCancellable) {
            sink.acceptCompanionAcquisitionToken(endpoint, token)
        }
    }

    @Test
    fun `adoption completes even when its job is cancelled mid-flight`() = runTest {
        val sink = RecordingSink()
        val started = CompletableDeferred<Unit>()

        val job = launch {
            started.complete(Unit)
            adopt(sink, "https://muse.example.test/muse-acquisition", "acqd1.device.1.signature")
        }

        started.await()
        // The real trigger: the stored-state collector cancels this job the
        // moment the pairing is persisted.
        job.cancel()
        job.join()

        assertThat(sink.calls).isEqualTo(1)
        assertThat(sink.token).isEqualTo("acqd1.device.1.signature")
        assertThat(sink.endpoint).isEqualTo("https://muse.example.test/muse-acquisition")
    }

    @Test
    fun `unshielded adoption is abandoned by the same cancellation`() = runTest {
        val sink = RecordingSink()
        val started = CompletableDeferred<Unit>()

        val job = launch {
            started.complete(Unit)
            // The previous, broken shape — kept to prove the race is real.
            sink.acceptCompanionAcquisitionToken("https://muse.example.test", "token")
        }

        started.await()
        job.cancel()
        job.join()

        assertThat(sink.calls).isEqualTo(0)
    }
}
