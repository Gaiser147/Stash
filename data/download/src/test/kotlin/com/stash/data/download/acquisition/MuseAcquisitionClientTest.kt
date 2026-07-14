package com.stash.data.download.acquisition

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MuseAcquisitionClientTest {
    private val prefs: MuseAcquisitionPreferences = mockk()
    private lateinit var server: MockWebServer
    private lateinit var client: MuseAcquisitionClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        coEvery { prefs.current() } returns MuseAcquisitionConfig(
            enabled = true,
            serverUrl = server.url("/acquisition").toString().trimEnd('/'),
            token = "dedicated-muse-test-token-1234567890",
            consumerId = "stash-test-device",
            wifiOnly = true,
            chargingOnly = true,
            lastAttemptAt = 0,
            lastSuccessAt = 0,
            lastResult = "",
            pendingCount = 0,
        )
        client = MuseAcquisitionClient(
            prefs,
            OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `client verifies contract then lists claims and reports without spotify fields`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"contract":"muse-acquisition/v1","capabilities":["list","claim","lease","report"],"maxBatchSize":50}""",
            ),
        )
        assertThat(client.checkConnection()).isEqualTo(MuseAcquisitionConnectionCheck.Verified)

        server.enqueue(MockResponse().setResponseCode(200).setBody(jobListJson()))
        val jobs = client.listApproved(3)
        assertThat(jobs).hasSize(1)
        assertThat(jobs.single().query).isEqualTo("Massive Attack Teardrop")

        server.enqueue(MockResponse().setResponseCode(200).setBody(claimJson()))
        val claim = client.claim(JOB_ID)
        assertThat(claim.leaseToken).isEqualTo(LEASE_TOKEN)

        server.enqueue(MockResponse().setResponseCode(200).setBody(reportJson("downloading")))
        client.reportDownloading(JOB_ID, LEASE_TOKEN)
        server.enqueue(MockResponse().setResponseCode(200).setBody(reportJson("completed")))
        client.reportCompleted(JOB_ID, LEASE_TOKEN, "youtube-video-id")

        val requests = List(5) { requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        requests.forEach { request ->
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer dedicated-muse-test-token-1234567890")
        }
        assertThat(requests[1].path).isEqualTo("/acquisition/v1/jobs?limit=3")
        assertThat(JSONObject(requests[2].body.readUtf8()).getString("consumerId"))
            .isEqualTo("stash-test-device")
        val completedBody = requests[4].body.readUtf8()
        assertThat(completedBody).contains("youtube-video-id")
        assertThat(completedBody).doesNotContain("spotify")
        assertThat(completedBody).doesNotContain("discord")
    }

    @Test
    fun `client classifies rejected token and retryable server failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(client.checkConnection()).isEqualTo(MuseAcquisitionConnectionCheck.AuthenticationFailed)

        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"temporarily_unavailable"}"""))
        val error = runCatching { client.listApproved() }.exceptionOrNull()
        assertThat(error).isInstanceOf(MuseAcquisitionRemoteException::class.java)
        assertThat((error as MuseAcquisitionRemoteException).retryable).isTrue()
        assertThat(error.code).isEqualTo("temporarily_unavailable")
    }

    @Test
    fun `connection check rejects an incomplete capability contract`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"contract":"muse-acquisition/v1","capabilities":["list","claim"],"maxBatchSize":50}""",
            ),
        )

        assertThat(client.checkConnection()).isEqualTo(MuseAcquisitionConnectionCheck.Incompatible)
    }

    private fun jobJson(status: String) = """
        {
          "id":"$JOB_ID",
          "contract":"muse-acquisition/v1",
          "origin":"manual_discord_search",
          "query":"Massive Attack Teardrop",
          "status":"$status",
          "attempt":1,
          "approvedAt":"2026-07-14T10:00:00.000Z",
          "expiresAt":"2026-07-21T10:00:00.000Z"
        }
    """.trimIndent()

    private fun jobListJson() = """{"contract":"muse-acquisition/v1","jobs":[${jobJson("approved")}]}"""
    private fun claimJson() = """
        {
          "contract":"muse-acquisition/v1",
          "job":${jobJson("claimed")},
          "leaseToken":"$LEASE_TOKEN",
          "leaseExpiresAt":"2026-07-14T10:30:00.000Z"
        }
    """.trimIndent()
    private fun reportJson(status: String) =
        """{"contract":"muse-acquisition/v1","job":${jobJson(status)}}"""

    private companion object {
        const val JOB_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val LEASE_TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
    }
}
