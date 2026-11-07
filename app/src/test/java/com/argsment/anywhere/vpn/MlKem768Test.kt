package com.argsment.anywhere.vpn

import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class MlKem768Test {

    @After fun tearDown() { MlKem768.forcedBackend = null }

    /**
     * ML-KEM-768 block of mlkem-native v2.0.0
     * examples/monolithic_build/expected_test_vectors.h (pk/ct as SHA-256).
     */
    private object Kat {
        val d = hex("934d60b35624d740b30a7f227af2ae7c678e4e04e13c5f509eade2b79aea77e2")
        val z = hex("3e2a2ea6c9c476fc4937b013c993a793d6c0ab9960695ba838f649da539ca3d0")
        val m = hex("934d60b35624d740b30a7f227af2ae7c678e4e04e13c5f509eade2b79aea77e2")
        const val PK_SHA256 = "c45a699a9efcb1a799578ce95f24b063b0b9ddc0879afdb3967fd9e1e3e8c247"
        const val CT_SHA256 = "0b99b2af81971943e4ef6e6f17f42be4f3caa9fea18da0f63df1d43639a74743"
        val ss = hex("0b1b32be26247cbcbe0916f8b0b729699c32a96d51efa4a4cd5b289239c8207e")
    }

    @Test fun knownAnswerKeyGenEncapsDecaps() {
        val kp = MlKem768.keyPairFromSeed(Kat.d + Kat.z)
        assertEquals(MlKem768.ENCAPSULATION_KEY_SIZE, kp.encapsulationKey.size)
        assertEquals(Kat.PK_SHA256, sha256(kp.encapsulationKey))

        val pub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, kp.encapsulationKey)
        val enc = MLKEMGenerator.internalGenerateEncapsulated(pub, Kat.m)
        assertEquals(Kat.CT_SHA256, sha256(enc.encapsulation))
        assertArrayEquals(Kat.ss, enc.secret)
        assertArrayEquals(Kat.ss, kp.decapsulate(enc.encapsulation))
    }

    @Test fun jvmUsesBouncyCastleBackend() {
        // No Android 15 platform provider on the JVM test runtime.
        assertEquals(MlKem768.Backend.BOUNCY_CASTLE, MlKem768.backend)
        assertEquals(MlKem768.Backend.BOUNCY_CASTLE, MlKem768.generateKeyPair().backend)
    }

    @Test fun bouncyCastleRoundTripAndSizes() {
        MlKem768.forcedBackend = MlKem768.Backend.BOUNCY_CASTLE
        val kp = MlKem768.generateKeyPair()
        assertEquals(1184, kp.encapsulationKey.size)
        val (ct, ss) = MlKem768.encapsulate(kp.encapsulationKey)
        assertEquals(1088, ct.size)
        assertEquals(32, ss.size)
        assertArrayEquals(ss, kp.decapsulate(ct))
        assertFalse(MlKem768.generateKeyPair().encapsulationKey.contentEquals(kp.encapsulationKey))
    }

    @Test fun tamperedCiphertextGivesDifferentSecret() {
        val kp = MlKem768.generateKeyPair()
        val (ct, ss) = MlKem768.encapsulate(kp.encapsulationKey)
        ct[0] = (ct[0].toInt() xor 1).toByte()
        // ML-KEM rejects implicitly: a pseudo-random secret, never the real one.
        assertFalse(ss.contentEquals(kp.decapsulate(ct)))
    }

    @Test fun wrongCiphertextLengthThrows() {
        val kp = MlKem768.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) { kp.decapsulate(ByteArray(100)) }
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

