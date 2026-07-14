package com.stash.data.download.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.security.MessageDigest
import java.util.Base64
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
class NavidromeIngestClientTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs: NavidromeExportPreferences = mockk()
    private lateinit var server: MockWebServer
    private lateinit var client: NavidromeIngestClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        coEvery { prefs.current() } returns NavidromeExportConfig(
            enabled = true,
            serverUrl = server.url("/stash-ingest").toString().trimEnd('/'),
            token = "dedicated-test-token",
            wifiOnly = true,
            chargingOnly = true,
            lastAttemptAt = 0,
            lastSuccessAt = 0,
            lastResult = "",
        )
        val httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        client = NavidromeIngestClient(context, prefs, httpClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `audio upload sends authenticated versioned idempotent contract`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        val audio = File(context.cacheDir, "navidrome-contract-test.flac").apply {
            writeBytes("fixture-audio".toByteArray())
        }

        val outcome = client.uploadFile(
            filePath = audio.absolutePath,
            relativePath = "artist/album/track.flac",
            metadata = NavidromeTrackMetadata("Track", "Artist", "Album", "Album Artist"),
        )

        assertThat(outcome).isEqualTo(NavidromeUploadOutcome.Success)
        val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.path).isEqualTo("/stash-ingest/v1/files/artist/album/track.flac")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer dedicated-test-token")
        assertThat(request.getHeader("X-Stash-Contract")).isEqualTo("1")
        assertThat(request.getHeader("X-Stash-Size")).isEqualTo(audio.length().toString())
        assertThat(request.getHeader("X-Stash-Sha256")).isEqualTo(sha256(audio.readBytes()))
        assertThat(request.body.readByteArray()).isEqualTo(audio.readBytes())

        val metadata = JSONObject(
            String(Base64.getUrlDecoder().decode(request.getHeader("X-Stash-Metadata"))),
        )
        assertThat(metadata.getString("title")).isEqualTo("Track")
        assertThat(metadata.getString("artist")).isEqualTo("Artist")
        assertThat(metadata.getString("album")).isEqualTo("Album")
        assertThat(metadata.getString("album_artist")).isEqualTo("Album Artist")
    }

    @Test
    fun `playlist upload hashes body and classifies server responses`() = runTest {
        val playlist = "#EXTM3U\nStash/artist/album/track.flac\n".toByteArray()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(401))

        assertThat(client.uploadPlaylist("mix.m3u8", playlist))
            .isEqualTo(NavidromeUploadOutcome.RetryableFailure)
        val retryableRequest = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertThat(retryableRequest.path).isEqualTo("/stash-ingest/v1/playlists/mix.m3u8")
        assertThat(retryableRequest.getHeader("X-Stash-Size")).isEqualTo(playlist.size.toString())
        assertThat(retryableRequest.getHeader("X-Stash-Sha256")).isEqualTo(sha256(playlist))

        assertThat(client.uploadPlaylist("mix.m3u8", playlist))
            .isEqualTo(NavidromeUploadOutcome.PermanentFailure)
        requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
