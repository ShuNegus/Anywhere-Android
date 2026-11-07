package com.argsment.anywhere.vpn.protocol.reality

import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.data.model.RealityConfiguration
import com.argsment.anywhere.vpn.protocol.vless.RealityError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.coroutines.coroutineContext

class RealityRejectionGateTest {

    private var now = 1_000_000L
    private val gate = RealityRejectionGate { now }

    private val reality = RealityConfiguration(
        serverName = "www.example.com",
        publicKey = ByteArray(32) { it.toByte() },
        shortId = byteArrayOf(0x0a, 0x0b),
    )
    private val key = RealityRejectionGate.Key("a.example.invalid:443", reality)

    @Test fun blockDurationsDoubleUpToSixtySeconds() {
        assertEquals(0L, RealityRejectionGate.blockDurationMs(0))
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L),
            (1..8).map(RealityRejectionGate::blockDurationMs)
        )
        assertEquals(60_000L, RealityRejectionGate.blockDurationMs(100))
    }

    @Test fun rejectionBlocksThenExpires() {
        assertNull(gate.refusal(key))
        assertTrue(gate.recordRejection(key))
        assertTrue(gate.refusal(key) is RealityError.RejectedRecently)
        assertEquals(2_000L, gate.remainingBlockMs(key))
        now += 1_999
        assertNotNull(gate.refusal(key))
        now += 1
        assertNull(gate.refusal(key))
    }

    @Test fun consecutiveRejectionsBackOffExponentially() {
        gate.recordRejection(key)
        now += 2_000
        gate.recordRejection(key)
        assertEquals(4_000L, gate.remainingBlockMs(key))
        now += 4_000
        gate.recordRejection(key)
        assertEquals(8_000L, gate.remainingBlockMs(key))
    }

    @Test fun rejectionsDuringBlockDoNotLengthenIt() {
        assertTrue(gate.recordRejection(key))
        now += 500
        repeat(20) { assertFalse(gate.recordRejection(key)) }
        assertEquals(1_500L, gate.remainingBlockMs(key))
        now += 1_500
        assertTrue(gate.recordRejection(key))
        assertEquals(4_000L, gate.remainingBlockMs(key)) // second failure, not 21st
    }

    @Test fun successResets() {
        gate.recordRejection(key)
        now += 2_000
        gate.recordRejection(key)
        assertTrue(gate.recordSuccess(key))
        assertFalse(gate.recordSuccess(key))
        assertNull(gate.refusal(key))
        gate.recordRejection(key)
        assertEquals(2_000L, gate.remainingBlockMs(key))
    }

    @Test fun keysAreIndependentAndIncludeEndpoint() {
        val other = RealityRejectionGate.Key("b.example.invalid:443", reality)
        assertNotEquals(key, other)
        gate.recordRejection(key)
        assertNull(gate.refusal(other))
        val otherShortId = RealityRejectionGate.Key("a.example.invalid:443", reality.copy(shortId = byteArrayOf(1)))
        assertNull(gate.refusal(otherShortId))
        assertEquals(key, RealityRejectionGate.Key("a.example.invalid:443", reality.copy(publicKey = reality.publicKey.copyOf())))
    }

    @Test fun bypassContextIsNeverRefused() = runBlocking {
        gate.recordRejection(key)
        assertNotNull(gate.refusal(key, coroutineContext))
        withContext(RealityRejectionGate.Bypass) {
            assertNull(gate.refusal(key, coroutineContext))
        }
    }

    @Test fun configurationKeysCoverChainHops() {
        val hop = config("hop.example.invalid", reality)
        val plain = config("plain.example.invalid", null)
        val main = config("main.example.invalid", reality).copy(chain = listOf(hop, plain))
        assertEquals(
            listOf("main.example.invalid:443", "hop.example.invalid:443"),
            main.realityRejectionKeys.map { it.endpoint }
        )
        assertNull(plain.realityRejectionKey)
    }

    @Test fun noticeIsFreshForNinetySecondsAndClearedOnAccept() {
        RealityRejectionNotice.clear()
        RealityRejectionNotice.noteRejected(key, nowMs = 10_000)
        assertTrue(RealityRejectionNotice.isRecentlyRejected(listOf(key), nowMs = 10_000 + 90_000))
        assertFalse(RealityRejectionNotice.isRecentlyRejected(listOf(key), nowMs = 10_000 + 90_001))
        assertFalse(RealityRejectionNotice.isRecentlyRejected(listOf(key), nowMs = 9_000))
        val other = RealityRejectionGate.Key("b.example.invalid:443", reality)
        assertFalse(RealityRejectionNotice.isRecentlyRejected(listOf(other), nowMs = 10_000))
        RealityRejectionNotice.noteAccepted(other)
        assertNotNull(RealityRejectionNotice.last.value)
        RealityRejectionNotice.noteAccepted(key)
        assertNull(RealityRejectionNotice.last.value)
    }

    @Test fun rejectionErrorsAreRecognised() {
        assertTrue(RealityError.AuthenticationFailed().isRealityRejection)
        assertTrue(RealityError.RejectedRecently().isRealityRejection)
        assertFalse(RealityError.HandshakeFailed("x").isRealityRejection)
    }

    private fun config(host: String, reality: RealityConfiguration?) = ProxyConfiguration(
        name = host, serverAddress = host, serverPort = 443u, uuid = UUID.randomUUID(),
        encryption = "none", security = if (reality != null) "reality" else "none", reality = reality
    )
}
