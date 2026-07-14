package com.stash.data.download.acquisition

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.security.MessageDigest
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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MuseAcquisitionClientTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
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
        server.enqueue(MockResponse().setResponseCode(404))
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

        val requests = List(6) { requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        requests.forEach { request ->
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer dedicated-muse-test-token-1234567890")
        }
        assertThat(requests[0].path).isEqualTo("/acquisition/v2/capabilities")
        assertThat(requests[2].path).isEqualTo("/acquisition/v1/jobs?limit=3")
        assertThat(JSONObject(requests[3].body.readUtf8()).getString("consumerId"))
            .isEqualTo("stash-test-device")
        val completedBody = requests[5].body.readUtf8()
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
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"contract":"muse-acquisition/v1","capabilities":["list","claim","lease","report"],"maxBatchSize":50}""",
            ),
        )

        assertThat(client.checkConnection()).isEqualTo(MuseAcquisitionConnectionCheck.Incompatible)
        assertThat(requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
            .isEqualTo("/acquisition/v2/capabilities")
        assertThat(server.takeRequest(250, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `definitively absent v2 endpoint falls back to verified v1 only`() = runTest {
        server.enqueue(MockResponse().setResponseCode(410))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"contract":"muse-acquisition/v1","capabilities":["list","claim","lease","report"],"maxBatchSize":50}""",
            ),
        )

        assertThat(client.negotiateContract()).isEqualTo(MuseAcquisitionContract.V1)
        assertThat(requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
            .isEqualTo("/acquisition/v2/capabilities")
        assertThat(requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
            .isEqualTo("/acquisition/v1/capabilities")
    }

    @Test
    fun `v2 negotiation claims exactly one assigned structured job`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"contract":"muse-acquisition/v2","capabilities":["assignment_claim","phase","heartbeat","upload_ticket","ingest_receipt"]}""",
            ),
        )
        assertThat(client.negotiateContract()).isEqualTo(MuseAcquisitionContract.V2)

        server.enqueue(MockResponse().setResponseCode(200).setBody(v2ClaimJson()))
        val claim = client.claimAssigned()

        assertThat(claim).isNotNull()
        assertThat(claim!!.job.artist).isEqualTo("Massive Attack")
        assertThat(claim.job.title).isEqualTo("Teardrop")
        assertThat(claim.job.origin).isEqualTo(MuseAcquisitionOrigin.MANUAL_QUALITY_UPGRADE)
        assertThat(claim.job.quality).isEqualTo(MuseAcquisitionQuality.HI_RES)
        val claimRequest = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).let {
            requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        }
        assertThat(claimRequest.path).isEqualTo("/acquisition/v2/work/claim")
        val requestJson = JSONObject(claimRequest.body.readUtf8())
        assertThat(requestJson.getJSONObject("deviceCapabilities").getInt("maxConcurrentJobs")).isEqualTo(1)
        assertThat(requestJson.toString()).doesNotContain("spotify")
        assertThat(requestJson.toString()).doesNotContain("discord")
    }

    @Test
    fun `v2 no assignment maps HTTP 204 to null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        assertThat(client.claimAssigned()).isNull()
    }

    @Test
    fun `direct upload uses ticket authority not Muse bearer and verifies receipt hash`() = runTest {
        val file = temporaryFolder.newFile("track.flac").apply { writeText("audio bytes") }
        val sha = "a".repeat(64)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ingestReceiptId":"receipt-12345678","sha256":"$sha"}""",
            ),
        )
        val ticket = MuseUploadTicket(
            id = "ticket-12345678",
            expiresAt = "2026-07-14T10:30:00.000Z",
            target = MuseUploadTarget.Direct(
                uploadUrl = server.url("/ingest/upload").toString(),
                method = "PUT",
                headers = mapOf("X-Upload-Ticket" to "opaque-ticket"),
            ),
        )

        val receipt = client.uploadArtifact(ticket, file, sha)

        assertThat(receipt.id).isEqualTo("receipt-12345678")
        val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.getHeader("Authorization")).isNull()
        assertThat(request.getHeader("X-Upload-Ticket")).isEqualTo("opaque-ticket")
        assertThat(request.body.readUtf8()).isEqualTo("audio bytes")
    }

    @Test
    fun `resumable upload follows stash ingest offset protocol and resumes durable bytes`() = runTest {
        val bytes = ByteArray(600_000) { index -> (index % 251).toByte() }
        val file = temporaryFolder.newFile("resumable.flac").apply { writeBytes(bytes) }
        val sha = bytes.sha256()
        val uploadId = "0123456789abcdef0123456789abcdef"
        val resumeOffset = 100_000
        val firstEnd = resumeOffset + 262_144

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "contract":"muse-acquisition/v2",
                  "ticket":{
                    "id":"ticket-resumable-123",
                    "expiresAt":"2026-07-14T10:30:00.000Z",
                    "resumable":{
                      "initUrl":"${server.url("/v2/uploads")}",
                      "headers":{"Authorization":"Bearer scoped-job-jws"},
                      "chunkSizeBytes":262144
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":true,"status":"uploading","uploadId":"$uploadId","uploadUrl":"/v2/uploads/$uploadId","uploadOffset":$resumeOffset,"uploadLength":${bytes.size},"expiresAt":1784025000}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Upload-Offset", resumeOffset)
                .addHeader("Upload-Length", bytes.size)
                .addHeader("Upload-Status", "uploading"),
        )
        server.enqueue(MockResponse().setResponseCode(204).addHeader("Upload-Offset", firstEnd))
        server.enqueue(MockResponse().setResponseCode(204).addHeader("Upload-Offset", bytes.size))
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"ok":true,"status":"accepted","accepted":true,"receipt":{"receiptId":"receipt-resumable-123","sourceSha256":"$sha"}}""",
            ),
        )

        val descriptor = MuseArtifactDescriptor(
            localTrackId = "opaque-local-id",
            sha256 = sha,
            sizeBytes = bytes.size.toLong(),
            media = MuseActualMedia("flac", 1_100, 96_000, 24, 240_000L),
        )
        val ticket = client.requestUploadTicket(JOB_ID, LEASE_TOKEN, descriptor)
        val receipt = client.uploadArtifact(ticket, file, sha)

        assertThat(receipt.id).isEqualTo("receipt-resumable-123")
        val requests = List(6) { requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        assertThat(requests[0].path).isEqualTo("/acquisition/v2/work/$JOB_ID/upload-ticket")
        assertThat(requests[0].getHeader("Authorization"))
            .isEqualTo("Bearer dedicated-muse-test-token-1234567890")
        assertThat(requests[1].method).isEqualTo("POST")
        assertThat(requests[1].path).isEqualTo("/v2/uploads")
        assertThat(requests[1].bodySize).isEqualTo(0L)
        assertThat(requests[1].getHeader("Authorization")).isEqualTo("Bearer scoped-job-jws")
        assertThat(requests[2].method).isEqualTo("HEAD")
        assertThat(requests[2].path).isEqualTo("/v2/uploads/$uploadId")

        val firstChunk = bytes.copyOfRange(resumeOffset, firstEnd)
        assertThat(requests[3].method).isEqualTo("PATCH")
        assertThat(requests[3].getHeader("Content-Type")).isEqualTo("application/offset+octet-stream")
        assertThat(requests[3].getHeader("Upload-Offset")).isEqualTo(resumeOffset.toString())
        assertThat(requests[3].getHeader("X-Chunk-Sha256")).isEqualTo(firstChunk.sha256())
        assertThat(requests[3].body.readByteArray()).isEqualTo(firstChunk)

        val finalChunk = bytes.copyOfRange(firstEnd, bytes.size)
        assertThat(requests[4].getHeader("Upload-Offset")).isEqualTo(firstEnd.toString())
        assertThat(requests[4].getHeader("X-Chunk-Sha256")).isEqualTo(finalChunk.sha256())
        assertThat(requests[4].body.readByteArray()).isEqualTo(finalChunk)
        assertThat(requests[5].method).isEqualTo("POST")
        assertThat(requests[5].path).isEqualTo("/v2/uploads/$uploadId/complete")
        assertThat(requests[5].bodySize).isEqualTo(0L)
        requests.drop(1).forEach { request ->
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer scoped-job-jws")
            assertThat(request.getHeader("Authorization"))
                .isNotEqualTo("Bearer dedicated-muse-test-token-1234567890")
        }
    }

    @Test
    fun `transient v2 capability failure never downgrades to v1`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val error = runCatching { client.negotiateContract() }.exceptionOrNull()

        assertThat(error).isInstanceOf(MuseAcquisitionRemoteException::class.java)
        assertThat((error as MuseAcquisitionRemoteException).retryable).isTrue()
        assertThat(requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
            .isEqualTo("/acquisition/v2/capabilities")
        assertThat(server.takeRequest(250, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `v2 ticket request and completion carry measured artifact and receipt`() = runTest {
        val descriptor = MuseArtifactDescriptor(
            localTrackId = "opaque-local-id",
            sha256 = "b".repeat(64),
            sizeBytes = 12_345L,
            media = MuseActualMedia("flac", 1_020, 96_000, 24, 240_000L),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "contract":"muse-acquisition/v2",
                  "ticket":{
                    "id":"ticket-12345678",
                    "expiresAt":"2026-07-14T10:30:00.000Z",
                    "uploadUrl":"${server.url("/ingest/one-shot")}",
                    "method":"PUT",
                    "headers":{"X-Upload-Ticket":"scoped"}
                  }
                }
                """.trimIndent(),
            ),
        )
        val ticket = client.requestUploadTicket(JOB_ID, LEASE_TOKEN, descriptor)
        assertThat(ticket.target).isInstanceOf(MuseUploadTarget.Direct::class.java)

        val ticketRequest = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertThat(ticketRequest.path).isEqualTo("/acquisition/v2/work/$JOB_ID/upload-ticket")
        val ticketBody = JSONObject(ticketRequest.body.readUtf8())
        assertThat(ticketBody.getJSONObject("artifact").getString("sha256")).isEqualTo(descriptor.sha256)
        assertThat(ticketBody.getJSONObject("artifact").getJSONObject("media").getInt("bitsPerSample"))
            .isEqualTo(24)

        server.enqueue(MockResponse().setResponseCode(204))
        client.completeV2(
            JOB_ID,
            LEASE_TOKEN,
            MuseIngestReceipt("receipt-12345678", descriptor.sha256),
            descriptor,
        )
        val completeRequest = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        val completeBody = JSONObject(completeRequest.body.readUtf8())
        assertThat(completeRequest.path).isEqualTo("/acquisition/v2/work/$JOB_ID/complete")
        assertThat(completeBody.getString("ingestReceiptId")).isEqualTo("receipt-12345678")
        assertThat(completeBody.toString()).doesNotContain("spotify")
        assertThat(completeBody.toString()).doesNotContain("discord")
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

    private fun v2ClaimJson() = """
        {
          "contract":"muse-acquisition/v2",
          "job":{
            "id":"$JOB_ID",
            "origin":"manual_quality_upgrade",
            "status":"leased",
            "query":"Massive Attack Teardrop",
            "quality":"hi_res",
            "attempt":1,
            "approvedAt":"2026-07-14T10:00:00.000Z",
            "expiresAt":"2026-07-21T10:00:00.000Z",
            "track":{"artist":"Massive Attack","title":"Teardrop","durationMs":240000}
          },
          "lease":{"token":"$LEASE_TOKEN","expiresAt":"2026-07-14T10:30:00.000Z"}
        }
    """.trimIndent()

    private companion object {
        const val JOB_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val LEASE_TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
