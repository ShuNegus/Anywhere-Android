package com.argsment.anywhere.vpn

import android.os.Build
import androidx.annotation.RequiresApi
import com.argsment.anywhere.vpn.util.AnywhereLogger
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import java.security.SecureRandom

/**
 * ML-KEM-768 (FIPS 203) for the X25519MLKEM768 TLS key share.
 *
 * Two backends:
 * - [Backend.SYSTEM]: the platform JCA `ML-KEM-768` (Android 15+ when a provider is
 *   actually present — many vendor builds ship without one).
 * - [Backend.BOUNCY_CASTLE]: BouncyCastle's lightweight `crypto` ML-KEM API, used on
 *   every other device. Not registered as a JCA provider, so it never shadows the
 *   platform one.
 *
 * The platform backend is probed once with a full keygen/encapsulate/decapsulate round
 * trip; anything short of a working round trip selects BouncyCastle.
 */
object MlKem768 {

    const val ENCAPSULATION_KEY_SIZE = 1184
    const val CIPHERTEXT_SIZE = 1088
    const val SHARED_SECRET_SIZE = 32

    private const val JCA_NAME = "ML-KEM-768"

    enum class Backend { SYSTEM, BOUNCY_CASTLE }

    /** One ephemeral key pair; [decapsulate] may be called for the server's share. */
    interface KeyPair {
        val backend: Backend
        /** Raw 1184-byte encapsulation key as sent on the wire. */
        val encapsulationKey: ByteArray
        /** Returns the 32-byte shared secret. Throws on a malformed ciphertext. */
        fun decapsulate(ciphertext: ByteArray): ByteArray
    }

    private val logger = AnywhereLogger("MLKEM")
    private val random = SecureRandom()

    /** Tests only: forces a backend instead of the probed one. */
    @Volatile
    internal var forcedBackend: Backend? = null

    /** The backend new key pairs use on this device (probed once, logged once). */
    val backend: Backend
        get() = forcedBackend ?: probedBackend

    private val probedBackend: Backend by lazy {
        val chosen = if (systemRoundTripWorks()) Backend.SYSTEM else Backend.BOUNCY_CASTLE
        logger.info("ML-KEM-768 backend: $chosen")
        chosen
    }

    fun generateKeyPair(): KeyPair = when (backend) {
        Backend.SYSTEM -> if (Build.VERSION.SDK_INT >= 35) {
            try {
                SystemKeyPair.generate()
            } catch (e: Exception) {
                logger.warning("System ML-KEM keygen failed, using BouncyCastle: ${e.message}")
                BouncyCastleKeyPair.generate(random)
            }
        } else {
            BouncyCastleKeyPair.generate(random)
        }
        Backend.BOUNCY_CASTLE -> BouncyCastleKeyPair.generate(random)
    }

    /**
     * Deterministic key pair from the 64-byte FIPS 203 seed `d || z`
     * (BouncyCastle backend). Used for known-answer tests.
     */
    internal fun keyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == 64) { "ML-KEM seed must be 64 bytes" }
        val priv = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, seed)
        return BouncyCastleKeyPair(priv, priv.publicKeyParameters.encoded)
    }

    /**
     * Encapsulates to a raw encapsulation key with BouncyCastle; returns
     * `ciphertext(1088) to sharedSecret(32)`. The server side of the exchange —
     * used by tests to verify the client half.
     */
    internal fun encapsulate(
        encapsulationKey: ByteArray,
        random: SecureRandom = this.random
    ): Pair<ByteArray, ByteArray> {
        require(encapsulationKey.size == ENCAPSULATION_KEY_SIZE) { "bad encapsulation key size" }
        val pub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, encapsulationKey)
        val result = MLKEMGenerator(random).generateEncapsulated(pub)
        return result.encapsulation to result.secret
    }

    private fun systemRoundTripWorks(): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        return try {
            SystemKeyPair.selfTest()
        } catch (t: Throwable) {
            logger.debug("System ML-KEM-768 unavailable: ${t.message}")
            false
        }
    }

    private class BouncyCastleKeyPair(
        private val privateKey: MLKEMPrivateKeyParameters,
        override val encapsulationKey: ByteArray
    ) : KeyPair {
        override val backend get() = Backend.BOUNCY_CASTLE

        override fun decapsulate(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size == CIPHERTEXT_SIZE) { "bad ML-KEM ciphertext size ${ciphertext.size}" }
            return MLKEMExtractor(privateKey).extractSecret(ciphertext)
        }

        companion object {
            fun generate(random: SecureRandom): KeyPair {
                val gen = MLKEMKeyPairGenerator()
                gen.init(MLKEMKeyGenerationParameters(random, MLKEMParameters.ml_kem_768))
                val kp = gen.generateKeyPair()
                val pub = (kp.public as MLKEMPublicKeyParameters).encoded
                check(pub.size == ENCAPSULATION_KEY_SIZE)
                return BouncyCastleKeyPair(kp.private as MLKEMPrivateKeyParameters, pub)
            }
        }
    }

    @RequiresApi(35)
    private class SystemKeyPair(
        private val privateKey: java.security.PrivateKey,
        override val encapsulationKey: ByteArray
    ) : KeyPair {
        override val backend get() = Backend.SYSTEM

        override fun decapsulate(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size == CIPHERTEXT_SIZE) { "bad ML-KEM ciphertext size ${ciphertext.size}" }
            val kem = javax.crypto.KEM.getInstance(JCA_NAME)
            val secret = kem.newDecapsulator(privateKey).decapsulate(ciphertext).encoded
            check(secret.size == SHARED_SECRET_SIZE) { "unexpected ML-KEM secret size" }
            return secret
        }

        companion object {
            fun generate(): KeyPair {
                val kp = java.security.KeyPairGenerator.getInstance(JCA_NAME).generateKeyPair()
                // The raw encapsulation key is the tail of the DER SubjectPublicKeyInfo.
                val der = kp.public.encoded
                check(der.size >= ENCAPSULATION_KEY_SIZE) { "short ML-KEM public key" }
                return SystemKeyPair(kp.private, der.copyOfRange(der.size - ENCAPSULATION_KEY_SIZE, der.size))
            }

            /** Platform keygen + BouncyCastle encapsulation + platform decapsulation agree. */
            fun selfTest(): Boolean {
                val kp = generate()
                val (ct, ss) = encapsulate(kp.encapsulationKey)
                return kp.decapsulate(ct).contentEquals(ss)
            }
        }
    }
}
