package com.stash.data.download.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs only when CI provides the pinned private stash-ingest TLS fixture.
 * Unlike the MockWebServer contract tests, this exercises the production
 * Kotlin client against the real Python request, validation, state, and media
 * paths in one process chain.
 */
@RunWith(RobolectricTestRunner::class)
class NavidromeIngestExternalContractTest {
    @Test
    fun `real TLS server accepts capabilities audio playlist and summary`() {
        val endpoint = System.getenv("STASH_INGEST_E2E_URL")
        val token = System.getenv("STASH_INGEST_E2E_TOKEN")
        val certificatePath = System.getenv("STASH_INGEST_E2E_CERT")
        val audioPath = System.getenv("STASH_INGEST_E2E_AUDIO")
        assumeTrue(
            "Pinned stash-ingest fixture is available only in canonical CI",
            listOf(endpoint, token, certificatePath, audioPath).all { !it.isNullOrBlank() },
        )

        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = mockk<NavidromeExportPreferences>()
            coEvery { prefs.current() } returns NavidromeExportConfig(
                enabled = true,
                serverUrl = requireNotNull(endpoint),
                token = requireNotNull(token),
                wifiOnly = true,
                chargingOnly = true,
                lastAttemptAt = 0,
                lastSuccessAt = 0,
                lastResult = "",
            )
            val certificate = File(requireNotNull(certificatePath)).inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            val certificates = HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate)
                .build()
            val httpClient = OkHttpClient.Builder()
                .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
                .build()
            val client = NavidromeIngestClient(context, prefs, httpClient)
            val audio = File(requireNotNull(audioPath))

            assertThat(client.checkConnection()).isEqualTo(NavidromeConnectionCheck.Verified)
            repeat(2) {
                assertThat(
                    client.uploadFile(
                        filePath = audio.absolutePath,
                        relativePath = "e2e/artist/album/track.flac",
                        metadata = NavidromeTrackMetadata(
                            title = "E2E Track",
                            artist = "E2E Artist",
                            album = "E2E Album",
                            albumArtist = "E2E Artist",
                        ),
                    ),
                ).isEqualTo(NavidromeUploadOutcome.Success)
            }
            assertThat(
                client.uploadPlaylist(
                    "e2e.m3u8",
                    "#EXTM3U\nStash/e2e/artist/album/track.flac\n".toByteArray(),
                ),
            ).isEqualTo(NavidromeUploadOutcome.Success)
            assertThat(
                client.syncComplete(
                    NavidromeSyncSummary(
                        mode = "contract-e2e",
                        tracksUploaded = 1,
                        tracksSkipped = 1,
                        trackFailures = 0,
                        coversUploaded = 0,
                        coversSkipped = 0,
                        coverFailures = 0,
                        playlistsUploaded = 1,
                        playlistFailures = 0,
                    ),
                ),
            ).isEqualTo(NavidromeUploadOutcome.Success)
        }
    }
}
