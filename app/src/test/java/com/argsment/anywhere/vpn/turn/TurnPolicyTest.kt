package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.turn.TurnDefaults
import com.argsment.anywhere.turn.TurnServerInfo
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of iOS TurnDialerConfigTests / TurnPoolFingerprintTests / TurnMemoryPolicyTests. */
class TurnPolicyTest {

    private val vkLink = "https://example.invalid/call/join/AAAAAAAAAAAAAAAA"
    private val server = TurnServerInfo("alpha.example.invalid", true, "203.0.113.10:56000", "00112233445566778899aabbccddeeff")
    private val mib = TurnMemoryPolicy.MIB

    @Test
    fun memoryPressureDoesNotChangeFingerprint() {
        val roomy = TurnMemoryPolicy.peers(10, 64 * mib, false)
        val tight = TurnMemoryPolicy.peers(10, 8 * mib, false)
        assertEquals(10, roomy.first)
        assertEquals(TurnMemoryPolicy.LOW_MEMORY_PEER_CAP, tight.first)
        assertEquals(
            TurnPoolFingerprint.make(vkLink, 10, false),
            TurnPoolFingerprint.make(vkLink, 10, false)
        )
    }

    @Test
    fun userSettingsChangeFingerprint() {
        val base = TurnPoolFingerprint.make(vkLink, 10, false)
        assertNotEquals(base, TurnPoolFingerprint.make(vkLink, 4, false))
        assertNotEquals(base, TurnPoolFingerprint.make("https://example.invalid/call/join/B", 10, false))
        assertNotEquals(base, TurnPoolFingerprint.make(vkLink, 10, true))
    }

    @Test
    fun goLimitIsClampedToTheExtensionBudget() {
        assertEquals(16 * mib, TurnMemoryPolicy.goLimit(25 * mib))
        assertEquals(21 * mib, TurnMemoryPolicy.goLimit(35 * mib))
        assertEquals(28 * mib, TurnMemoryPolicy.goLimit(60 * mib))
        assertEquals(TurnMemoryPolicy.MINIMUM_GO_LIMIT, TurnMemoryPolicy.goLimit(4 * mib))
    }

    @Test
    fun peerCapHasHysteresis() {
        var state = TurnMemoryPolicy.peers(10, 40 * mib, false)
        assertEquals(10 to false, state)
        state = TurnMemoryPolicy.peers(10, 12 * mib, state.second)
        assertEquals(4 to true, state)
        state = TurnMemoryPolicy.peers(10, 24 * mib, state.second) // between thresholds
        assertEquals(4 to true, state)
        state = TurnMemoryPolicy.peers(10, 40 * mib, state.second)
        assertEquals(10 to false, state)
    }

    @Test
    fun unknownBudgetLeavesTheRequestAlone() {
        assertEquals(10, TurnMemoryPolicy.peers(10, 0, false).first)
        assertEquals(10, TurnMemory(configureGo = { _, _ -> }).effectivePeers(10))
    }

    @Test
    fun requestBelowTheCapIsNeverRaised() {
        assertEquals(2, TurnMemoryPolicy.peers(2, 8 * mib, false).first)
    }

    @Test
    fun leavesCredentialSetsToTheCore() {
        for (peers in listOf(1, 4, 10, 30, 50)) {
            for (suggestion in listOf(null, 2, 64)) {
                val config = TurnDialerConfig.make(server, vkLink, TurnDefaults(streamsPerCred = suggestion), peers, false)
                assertEquals(peers, (config["num_streams"] as JsonPrimitive).int)
                assertNull(config["streams_per_cred"])
            }
        }
        // Out-of-range peers are clamped the same way as the setting.
        assertEquals(10, (TurnDialerConfig.make(server, vkLink, null, 0, false)["num_streams"] as JsonPrimitive).int)
        assertEquals(50, (TurnDialerConfig.make(server, vkLink, null, 99, false)["num_streams"] as JsonPrimitive).int)
    }

    @Test
    fun carriesWrapAndSolverSettings() {
        val config = TurnDialerConfig.make(server, vkLink, TurnDefaults(wrapMode = true, captchaSolver = "v2", streamsPerCred = 2), 10, true)
        assertTrue((config["wrap_mode"] as JsonPrimitive).boolean)
        assertEquals(server.wrapKeyHex, (config["wrap_key_hex"] as JsonPrimitive).content)
        assertEquals("v2", (config["captcha_solver"] as JsonPrimitive).content)
        assertTrue((config["vless_mode"] as JsonPrimitive).boolean)
        assertTrue((config["manual_captcha"] as JsonPrimitive).boolean)
        assertEquals("203.0.113.10:56000", (config["peer_addr"] as JsonPrimitive).content)
        assertEquals(vkLink, (config["vk_link"] as JsonPrimitive).content)
    }

    @Test
    fun wrapModeDefaultsToKeyPresence() {
        assertTrue(TurnDialerConfig.make(server, vkLink, null, 10, false).containsKey("wrap_key_hex"))
        val keyless = server.copy(wrapKeyHex = "")
        assertFalse(TurnDialerConfig.make(keyless, vkLink, null, 10, false).containsKey("wrap_mode"))
        assertFalse(TurnDialerConfig.make(server, vkLink, TurnDefaults(wrapMode = false), 10, false).containsKey("wrap_mode"))
        assertFalse(TurnDialerConfig.make(server, vkLink, TurnDefaults(captchaSolver = ""), 10, false).containsKey("captcha_solver"))
    }
}
