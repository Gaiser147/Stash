package com.stash.feature.muse

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class MuseProofCanonicalizerTest {
    @Test
    fun `pairing request digest exactly matches six line server canonical form`() {
        val digest = MusePairingCanonicalizer.requestDigest(
            publicKeySpki = "c3BraQ",
            publicKeyFingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            deviceName = "Pixel 9",
            appVersion = "0.9.75-community.1",
        )

        assertThat(digest).isEqualTo(
            "c2bc8e7d86f9df7f5b764635ee9bc559009d977f3fe4ec67806de2f20d4d16e2",
        )
    }

    @Test
    fun `pairing proof preserves challenge expiry bytes and DER base64url metadata`() {
        val message = MusePairingCanonicalizer.proofMessage(
            MusePairingChallengeResponse(
                contract = MUSE_COMPANION_CONTRACT,
                challengeId = "11111111-1111-1111-1111-111111111111",
                challengeNonce = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                challengeExpiresAt = "2026-07-14T12:00:00.000Z",
                requestDigest = "a".repeat(64),
                publicKeyFingerprint = "b".repeat(64),
                signatureAlgorithm = "SHA256withECDSA",
                signatureEncoding = "ASN.1-DER/base64url",
            ),
        ).decodeToString()

        assertThat(message).isEqualTo(
            listOf(
                "muse-companion-pairing-proof/v1",
                "11111111-1111-1111-1111-111111111111",
                "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                "2026-07-14T12:00:00.000Z",
                "a".repeat(64),
                "b".repeat(64),
            ).joinToString("\n"),
        )
    }

    @Test
    fun `proof bytes exactly match Muse server canonical form`() {
        val body = "{\"action\":\"pause\"}".encodeToByteArray()

        val message = MuseProofCanonicalizer.message(
            method = "post",
            pathAndQuery = "/companion/v1/guilds/123/player?view=full%20state",
            timestampSeconds = 1_721_000_000,
            nonce = "abcdefghijklmnop",
            body = body,
            accessOrRefreshToken = "access-token",
        ).decodeToString()

        assertThat(message).isEqualTo(
            listOf(
                "muse-companion-proof/v1",
                "POST",
                "/companion/v1/guilds/123/player?view=full%20state",
                "1721000000",
                "abcdefghijklmnop",
                "0c4351cc87a128e40b4ecbcaea98d80f8d53909c398cde86c3e38540f82ee7dd",
                "3f16bed7089f4653e5ef21bfd2824d7f3aaaecc7a598e7e89c580e1606a9cc52",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `path keeps exact encoded query`() {
        val url = "https://example.test/a%20b?x=one%2Ftwo&x=three".toHttpUrl()

        assertThat(MuseProofCanonicalizer.pathAndQuery(url))
            .isEqualTo("/a%20b?x=one%2Ftwo&x=three")
    }

    @Test
    fun `nonce is base64url and server bounded`() {
        repeat(20) {
            assertThat(MuseProofCanonicalizer.nonce()).matches("^[A-Za-z0-9_-]{16,96}$")
        }
    }
}
