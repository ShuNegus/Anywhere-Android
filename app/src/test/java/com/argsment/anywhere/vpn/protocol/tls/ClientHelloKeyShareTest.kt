package com.argsment.anywhere.vpn.protocol.tls

import com.argsment.anywhere.data.model.TlsFingerprint
import com.argsment.anywhere.vpn.MlKem768
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Xray REALITY >= 26.9.9 refuses ClientHellos without a valid X25519MLKEM768 share, or
 * with duplicated / out-of-order shares. Check what the PQ fingerprints put on the wire.
 */
class ClientHelloKeyShareTest {

    private val pqFingerprints = listOf(
        TlsFingerprint.CHROME_133, TlsFingerprint.FIREFOX_148, TlsFingerprint.SAFARI_26
    )

    @Test fun pqFingerprintsOfferHybridShareBeforeX25519() {
        val x25519 = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val mlkem = MlKem768.generateKeyPair()
        for (fp in pqFingerprints) {
            repeat(8) {
                val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val hello = TlsClientHelloBuilder.buildRawClientHello(
                    fingerprint = fp, random = random, sessionId = ByteArray(32),
                    serverName = "example.com", publicKey = x25519,
                    mlkemEncapsulationKey = mlkem.encapsulationKey
                )
                val exts = extensions(hello)
                val shares = keyShares(exts.getValue(0x0033))
                val groups = supportedGroups(exts.getValue(0x000A))
                val shareGroups = shares.map { it.first }

                assertEquals("$fp: no duplicate shares", shareGroups.size, shareGroups.toSet().size)
                val nonGrease = shareGroups.filterNot(::isGrease)
                assertEquals("$fp: hybrid first, then X25519", listOf(0x11EC, 0x001D), nonGrease.take(2))
                if (shareGroups.any(::isGrease)) {
                    // Chrome/Safari: GREASE, X25519MLKEM768, X25519.
                    assertTrue("$fp: GREASE share leads", isGrease(shareGroups.first()))
                    assertEquals(listOf(0x11EC, 0x001D), shareGroups.subList(1, 3))
                } else {
                    // Firefox: X25519MLKEM768, X25519, P-256.
                    assertEquals(listOf(0x11EC, 0x001D, 0x0017), shareGroups)
                }
                // Shares follow supported_groups order.
                assertEquals("$fp: share order matches groups", shareGroups, groups.filter { it in shareGroups })

                val hybrid = shares.first { it.first == 0x11EC }.second
                assertEquals(1184 + 32, hybrid.size)
                assertArrayEquals(mlkem.encapsulationKey, hybrid.copyOfRange(0, 1184))
                assertArrayEquals(x25519, hybrid.copyOfRange(1184, 1216))
                assertArrayEquals(x25519, shares.first { it.first == 0x001D }.second)
            }
        }
    }

    @Test fun plainTlsOmitsPqShare() {
        val hello = TlsClientHelloBuilder.buildRawClientHello(
            fingerprint = TlsFingerprint.CHROME_133, random = ByteArray(32), sessionId = ByteArray(32),
            serverName = "example.com", publicKey = ByteArray(32), omitPQKeyShares = true,
            mlkemEncapsulationKey = MlKem768.generateKeyPair().encapsulationKey
        )
        val groups = keyShares(extensions(hello).getValue(0x0033)).map { it.first }
        assertFalse(0x11EC in groups)
        assertTrue(0x001D in groups)
    }

    private fun isGrease(v: Int) = (v and 0x0F0F) == 0x0A0A && (v shr 8) == (v and 0xFF)

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** ClientHello handshake message (type 1, no record header) → extension type → body. */
    private fun extensions(hello: ByteArray): Map<Int, ByteArray> {
        assertEquals(1, hello[0].toInt())
        var o = 4 + 2 + 32
        o += 1 + (hello[o].toInt() and 0xFF)
        o += 2 + u16(hello, o)
        o += 1 + (hello[o].toInt() and 0xFF)
        val end = o + 2 + u16(hello, o)
        o += 2
        val out = LinkedHashMap<Int, ByteArray>()
        while (o + 4 <= end) {
            val type = u16(hello, o)
            val len = u16(hello, o + 2)
            assertFalse("duplicate extension $type", out.containsKey(type) && !isGrease(type))
            out[type] = hello.copyOfRange(o + 4, o + 4 + len)
            o += 4 + len
        }
        assertEquals(end, o)
        return out
    }

    private fun keyShares(body: ByteArray): List<Pair<Int, ByteArray>> {
        val end = 2 + u16(body, 0)
        assertEquals(body.size, end)
        var o = 2
        val out = mutableListOf<Pair<Int, ByteArray>>()
        while (o < end) {
            val len = u16(body, o + 2)
            out += u16(body, o) to body.copyOfRange(o + 4, o + 4 + len)
            o += 4 + len
        }
        return out
    }

    private fun supportedGroups(body: ByteArray): List<Int> =
        (0 until u16(body, 0) / 2).map { u16(body, 2 + it * 2) }
}
