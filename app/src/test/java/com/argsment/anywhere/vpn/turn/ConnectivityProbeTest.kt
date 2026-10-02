package com.argsment.anywhere.vpn.turn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-mode decision hinges on this table, so it is pinned here (port of iOS
 * `ConnectivityProbeTests`). The handshakes themselves need a real network.
 */
class ConnectivityProbeTest {

    @Test
    fun openInternetWins() {
        assertEquals(ConnectivityVerdict.OPEN, ConnectivityProbe.verdict(homeReachable = true, openReachable = true))
        // The open host answering is enough on its own.
        assertEquals(ConnectivityVerdict.OPEN, ConnectivityProbe.verdict(homeReachable = false, openReachable = true))
    }

    @Test
    fun domesticOnlyMeansCensored() {
        assertEquals(ConnectivityVerdict.BLOCKED, ConnectivityProbe.verdict(homeReachable = true, openReachable = false))
    }

    /** Nothing answered: no network rather than a censored one, so the VPN must not start. */
    @Test
    fun nothingReachableIsOffline() {
        assertEquals(ConnectivityVerdict.OFFLINE, ConnectivityProbe.verdict(homeReachable = false, openReachable = false))
    }

    /** One flaky handshake must not be able to call an ordinary network censored. */
    @Test
    fun severalOpenHostsAreProbed() {
        val open = ConnectivityProbe.OPEN_HOSTS
        assertTrue(open.size >= 3)
        assertEquals(open.size, open.toSet().size)
        assertTrue(open.toSet().intersect(ConnectivityProbe.HOME_HOSTS.toSet()).isEmpty())
        assertEquals(listOf("vk.ru", "ya.ru"), ConnectivityProbe.HOME_HOSTS)
        assertEquals(listOf("google.com", "cloudflare.com", "gstatic.com"), open)
        assertEquals(443, ConnectivityProbe.PROBE_PORT)
    }

    @Test
    fun backgroundProbeIsMorePatientThanPreflight() {
        assertEquals(2_000L, ProbeProfile.PREFLIGHT.timeoutMs)
        assertEquals(5_000L, ProbeProfile.BACKGROUND.timeoutMs)
        assertTrue(ProbeProfile.BACKGROUND.timeoutMs > ProbeProfile.PREFLIGHT.timeoutMs)
    }

    @Test
    fun allHostsAreProbedWithTheProfileTimeoutAndReportedInOrder() = runBlocking {
        val asked = mutableListOf<Pair<String, Long>>()
        val outcome = ConnectivityProbe.classifyDetailed(ProbeProfile.BACKGROUND) { host, timeout ->
            synchronized(asked) { asked += host to timeout }
            host == "cloudflare.com"
        }
        assertEquals(ConnectivityVerdict.OPEN, outcome.verdict) // one open host is enough
        assertEquals(ConnectivityProbe.OPEN_HOSTS + ConnectivityProbe.HOME_HOSTS, outcome.results.map { it.host })
        assertEquals(5, asked.size)
        assertTrue(asked.all { it.second == 5_000L })
    }

    @Test
    fun aFailingProbeCountsAsUnreachable() = runBlocking {
        val outcome = ConnectivityProbe.classifyDetailed(ProbeProfile.PREFLIGHT) { host, _ ->
            if (host in ConnectivityProbe.OPEN_HOSTS) throw java.io.IOException("reset") else true
        }
        assertEquals(ConnectivityVerdict.BLOCKED, outcome.verdict)
        assertTrue(outcome.results.filter { it.host in ConnectivityProbe.OPEN_HOSTS }.none { it.reachable })
    }

    @Test
    fun verdictRawValuesMatchTheStoredPreflight() {
        assertEquals("offline", ConnectivityVerdict.OFFLINE.rawValue)
        assertEquals("blocked", ConnectivityVerdict.BLOCKED.rawValue)
        assertEquals("open", ConnectivityVerdict.OPEN.rawValue)
        assertEquals(ConnectivityVerdict.OPEN, ConnectivityVerdict.fromRaw("open"))
        assertEquals(null, ConnectivityVerdict.fromRaw("nope"))
    }
}
