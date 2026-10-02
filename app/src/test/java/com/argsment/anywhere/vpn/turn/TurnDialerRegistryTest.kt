package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.turn.FakeSharedPreferences
import com.argsment.anywhere.turn.TurnDefaults
import com.argsment.anywhere.turn.TurnMetadata
import com.argsment.anywhere.turn.TurnMetadataStore
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.turn.TurnServerInfo
import com.argsment.anywhere.turn.TurnSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Registry behaviour, incl. the iOS "speedtest dies after 3-4 s" invariant. */
class TurnDialerRegistryTest {

    private class FakeDialer(
        override val host: String,
        val peers: Int,
        val manualCaptcha: Boolean,
        val defaults: TurnDefaults?,
    ) : TurnDialerHandle {
        var closed = false
        var ready = true
        var streams = 0
        override val sessionCount get() = if (closed) 0 else peers
        override val phase get() = if (closed) 0 else TurnPhase.READY.rawValue
        override val credentialSets = 1
        override val credentialSetsPassed = 1
        override val captchaHits = 0
        override val coreStatus: TurnCoreStatus? = null
        override val openStreamCount get() = streams
        override suspend fun waitReady(timeoutMs: Long?) { if (!ready) throw TurnError.NotReady() }
        override fun openStream(): TurnStream {
            streams++
            return TurnStream(object : TurnStreamIo {
                override fun read(max: Int): ByteArray = throw Exception("EOF")
                override fun write(data: ByteArray) {}
                override fun close() {}
            }, host)
        }
        override fun noteStreamClosed() { streams-- }
        override fun close() { closed = true }
    }

    private val prefs = FakeSharedPreferences()
    private val settings = TurnSettings(prefs).apply { turnMode = TurnMode.ON }
    private val store = TurnMetadataStore(null)
    private val created = mutableListOf<FakeDialer>()
    private var available = 0L
    private var autoDecision: TurnAutoDecision = TurnAutoDecision.UNDECIDED
    private val goLimits = mutableListOf<Pair<Long, Int>>()

    private val registry = TurnDialerRegistry(
        settingsProvider = { settings },
        storeProvider = { store },
        factory = TurnDialerFactory { server, _, defaults, peers, manualCaptcha ->
            FakeDialer(server.host, peers, manualCaptcha, defaults).also { created += it }
        },
        memory = TurnMemory(availableBytes = { available }, configureGo = { l, g -> goLimits += l to g }),
        autoDecision = { autoDecision },
        coreLog = {},
    )

    private fun subscribe(vkLink: String? = "https://vk.example.invalid/call/join/A", vararg hosts: String) {
        store.setMetadata(
            TurnMetadata(
                vkLink = vkLink,
                defaults = TurnDefaults(readyTimeout = 12),
                servers = hosts.associateWith { TurnServerInfo(it, true, "192.0.2.1:56000", "00ff") },
            ),
            UUID.randomUUID()
        )
    }

    @Test
    fun oneDialerPerHost() {
        subscribe(hosts = arrayOf("a.example.invalid", "b.example.invalid"))
        val a1 = registry.dialer("a.example.invalid")
        val a2 = registry.dialer("A.example.invalid")
        val b = registry.dialer("b.example.invalid")
        assertSame(a1, a2)
        assertNotNull(b)
        assertEquals(2, created.size)
        assertEquals(12, created.first().defaults?.readyTimeout)
        assertEquals(listOf("a.example.invalid", "b.example.invalid"), registry.statistics().map { it.host })
    }

    @Test
    fun goMemoryIsConfiguredAfterEachNewDialer() {
        subscribe(hosts = arrayOf("a.example.invalid"))
        registry.dialer("a.example.invalid")
        registry.dialer("a.example.invalid")
        assertEquals(listOf(TurnMemoryPolicy.ANDROID_GO_LIMIT to 50), goLimits)
    }

    @Test
    fun noRelayOrNoLinkMeansNoDialer() {
        subscribe(vkLink = null, hosts = arrayOf("a.example.invalid"))
        assertFalse(registry.isActive())
        assertNull(registry.dialer("a.example.invalid"))
        settings.vkLink = "https://vk.example.invalid/manual"
        assertTrue(registry.isActive())
        assertNull(registry.dialer("unknown.example.invalid"))
        assertNotNull(registry.dialer("a.example.invalid"))
    }

    @Test
    fun manualLinkWinsOverSubscriptionLink() {
        subscribe(hosts = arrayOf("a.example.invalid"))
        assertEquals("https://vk.example.invalid/call/join/A", registry.effectiveVKLink())
        settings.vkLink = "  https://vk.example.invalid/manual "
        assertEquals("https://vk.example.invalid/manual", registry.effectiveVKLink())
    }

    @Test
    fun userSettingChangeRebuildsPools() {
        subscribe(hosts = arrayOf("a.example.invalid"))
        val first = registry.dialer("a.example.invalid") as FakeDialer
        settings.peers = 4
        val second = registry.dialer("a.example.invalid") as FakeDialer
        assertTrue(first.closed)
        assertEquals(4, second.peers)
        settings.captchaManual = true
        val third = registry.dialer("a.example.invalid") as FakeDialer
        assertTrue(second.closed)
        assertTrue(third.manualCaptcha)
    }

    /** Memory dipping under load must not touch live pools; only new pools are capped. */
    @Test
    fun memoryPressureDoesNotRebuildLivePools() {
        subscribe(hosts = arrayOf("a.example.invalid", "b.example.invalid"))
        available = 64 * TurnMemoryPolicy.MIB
        val a = registry.dialer("a.example.invalid") as FakeDialer
        assertEquals(10, a.peers)

        available = 8 * TurnMemoryPolicy.MIB
        assertSame(a, registry.dialer("a.example.invalid"))
        assertFalse(a.closed)
        val b = registry.dialer("b.example.invalid") as FakeDialer
        assertEquals(TurnMemoryPolicy.LOW_MEMORY_PEER_CAP, b.peers)
        assertFalse(a.closed)
    }

    @Test
    fun modeAndForcedSession() {
        subscribe(hosts = arrayOf("a.example.invalid"))
        settings.turnMode = TurnMode.OFF
        assertFalse(registry.isActive())
        settings.turnForced = true
        assertTrue(registry.isActive())
        settings.turnForced = false
        settings.featureEnabled = false
        settings.turnMode = TurnMode.ON
        assertFalse(registry.isActive())
    }

    @Test
    fun autoFollowsThePilotsDecision() {
        subscribe(hosts = arrayOf("a.example.invalid"))
        settings.turnMode = TurnMode.AUTO
        autoDecision = TurnAutoDecision.UNDECIDED
        assertFalse(registry.isActive())
        autoDecision = TurnAutoDecision.DIRECT
        assertFalse(registry.isActive())
        autoDecision = TurnAutoDecision.OFFLINE
        assertFalse(registry.isActive())
        autoDecision = TurnAutoDecision.TURN
        assertTrue(registry.isActive())
    }

    @Test
    fun resetClosesEverything() {
        subscribe(hosts = arrayOf("a.example.invalid"))
        val a = registry.dialer("a.example.invalid") as FakeDialer
        registry.reset()
        assertTrue(a.closed)
        assertTrue(registry.statistics().isEmpty())
        assertNotNull(registry.dialer("a.example.invalid"))
        assertEquals(2, created.size)
    }

    @Test
    fun tunnelCountsStreamsAndFallsBackWhenNotReady() = runBlocking {
        subscribe(hosts = arrayOf("a.example.invalid"))
        val tunnel = registry.tunnel("a.example.invalid")!!
        val dialer = created.single()
        assertEquals(1, dialer.openStreamCount)
        assertNull("EOF reads as end of stream", tunnel.receive())
        tunnel.cancel()
        tunnel.cancel()
        assertEquals(0, dialer.openStreamCount)

        dialer.ready = false
        assertNull(registry.tunnel("a.example.invalid"))
    }
}
