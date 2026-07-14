package com.stash.feature.muse

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class MusePublicJwk(
    val kty: String = "EC",
    val crv: String = "P-256",
    val x: String,
    val y: String,
)

internal data class MuseDevicePublicIdentity(
    val publicKeySpki: String,
    val fingerprint: String,
    val publicJwk: MusePublicJwk,
)

/**
 * Owns the Companion signing key. The private half is generated inside
 * Android Keystore and is never serialized, backed up or returned to callers.
 */
@Singleton
internal class MuseDeviceIdentity @Inject constructor() {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    fun publicIdentity(): MuseDevicePublicIdentity {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: generateEntry()
        val publicKey = entry.certificate.publicKey as? ECPublicKey
            ?: error("Muse Companion identity is not a P-256 EC key")
        val spkiBytes = publicKey.encoded
        return MuseDevicePublicIdentity(
            publicKeySpki = spkiBytes.base64Url(),
            fingerprint = spkiBytes.sha256Hex(),
            publicJwk = MusePublicJwk(
                x = publicKey.w.affineX.toUnsignedFixed(32).base64Url(),
                y = publicKey.w.affineY.toUnsignedFixed(32).base64Url(),
            ),
        )
    }

    fun sign(message: ByteArray): String {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("Muse Companion identity is missing")
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(entry.privateKey)
            update(message)
            sign().base64Url()
        }
    }

    @Synchronized
    fun deleteLocalIdentity() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun generateEntry(): KeyStore.PrivateKeyEntry {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKeyPair()
        return keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private fun java.math.BigInteger.toUnsignedFixed(size: Int): ByteArray {
        val encoded = toByteArray()
        val unsigned = if (encoded.size > size && encoded.first() == 0.toByte()) {
            encoded.copyOfRange(1, encoded.size)
        } else {
            encoded
        }
        require(unsigned.size <= size) { "P-256 coordinate is too large" }
        return ByteArray(size).also { destination ->
            unsigned.copyInto(destination, destinationOffset = size - unsigned.size)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "stash_muse_companion_p256_v1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

internal object MuseProofCanonicalizer {
    private val secureRandom = SecureRandom()

    fun nonce(): String = ByteArray(24).also(secureRandom::nextBytes).base64Url()

    fun pathAndQuery(url: okhttp3.HttpUrl): String = buildString {
        append(url.encodedPath)
        url.encodedQuery?.let {
            append('?')
            append(it)
        }
    }

    fun message(
        method: String,
        pathAndQuery: String,
        timestampSeconds: Long,
        nonce: String,
        body: ByteArray,
        accessOrRefreshToken: String,
    ): ByteArray = listOf(
        "muse-companion-proof/v1",
        method.trim().uppercase(),
        pathAndQuery,
        timestampSeconds.toString(),
        nonce,
        body.sha256Hex(),
        accessOrRefreshToken.toByteArray(StandardCharsets.UTF_8).sha256Hex(),
    ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

internal object MusePairingCanonicalizer {
    fun requestDigest(
        publicKeySpki: String,
        publicKeyFingerprint: String,
        deviceName: String,
        appVersion: String,
    ): String = listOf(
        "muse-companion-pairing-request/v1",
        publicKeySpki,
        publicKeyFingerprint,
        deviceName,
        appVersion,
        "android",
    ).joinToString("\n").toByteArray(StandardCharsets.UTF_8).sha256Hex()

    fun proofMessage(challenge: MusePairingChallengeResponse): ByteArray = listOf(
        "muse-companion-pairing-proof/v1",
        challenge.challengeId,
        challenge.challengeNonce,
        challenge.challengeExpiresAt,
        challenge.requestDigest,
        challenge.publicKeyFingerprint,
    ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

internal fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
