package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.vpn.turn.NetworkFingerprint.InterfaceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-pilot's decision rules (spec §7.5, §14 #4) with a fake scheduler and probe:
 * re-probe only on a real network change, three confirmations 12 s apart before leaving a
 * working direct path, one pre-flight cross-check, "nobody answered" never changes a
 * route, TURN is sticky.
 */
class TurnAutoPilotCoreTest {

    private class FakeScheduler : AutoPilotScheduler {
        class Entry(val delayMs: Long, val task: suspend () -> Unit) {
            var cancelled = false
        }

        val queue = ArrayDeque<Entry>()

        override fun schedule(delayMs: Long, task: suspend () -> Unit): () -> Unit {
            val entry = Entry(delayMs, task)
            queue.addLast(entry)
            return { entry.cancelled = true }
        }

        /** Runs the oldest scheduled task; returns its delay. */
        fun runNext(): Long {
            val entry = queue.removeFirst()
            if (!entry.cancelled) runBlocking { entry.task() }
            return entry.delayMs
        }
    }

    private val scheduler = FakeScheduler()
    private var engaged = true
    private var decision = TurnAutoDecision.UNDECIDED
    private var preflight: ConnectivityVerdict? = null
    private val verdicts = ArrayDeque<ConnectivityVerdict>()
    private var probes = 0
    private var switches = 0
    private val logs = mutableListOf<String>()

    private val pilot = TurnAutoPilotCore(
        isEngaged = { engaged },
        probe = {
            probes++
            val v = verdicts.removeFirst()
            ProbeOutcome(v, listOf(HostResult("google.com", v == ConnectivityVerdict.OPEN, 120), HostResult("vk.ru", v != ConnectivityVerdict.OFFLINE, 40)))
        },
        decision = { decision },
        // Same stickiness as TurnAutoState.setDecision.
        setDecision = { if (decision != TurnAutoDecision.TURN) decision = it },
        resetDecision = { decision = TurnAutoDecision.UNDECIDED },
        recentPreflight = { maxAge -> assertEquals(30_000L, maxAge); preflight },
        scheduler = scheduler,
        log = { logs += it },
    )

    private fun fp(ssid: String) = NetworkFingerprint.make(InterfaceKind.WIFI, "wlan0", ssid, listOf("192.168.1.1"))

    private fun startOn(network: String, first: ConnectivityVerdict) {
        verdicts += first
        pilot.start { switches++ }
        assertEquals(0L, scheduler.runNext()) // probes right away
        // The callback's first report of the current network.
        pilot.noteNetworkChange(fp(network))
    }

    @Test
    fun openNetworkGoesDirect() {
        startOn("Home", ConnectivityVerdict.OPEN)
        assertEquals(TurnAutoDecision.DIRECT, decision)
        assertTrue(logs.last().startsWith("[TURN auto] verdict=open hosts=[google.com ✓120ms, vk.ru ✓40ms] fingerprintChanged=false decision=direct"))
    }

    @Test
    fun aFirstBlockedProbeDecidesTurnWithoutMovingFlows() {
        startOn("Cafe", ConnectivityVerdict.BLOCKED)
        assertEquals(TurnAutoDecision.TURN, decision)
        assertEquals(0, switches) // nothing live to tear down
        assertTrue(logs.last().endsWith("decision=turn(first probe)"))
    }

    @Test
    fun aFirstBlockedContradictingThePreflightIsRecheckedOnce() {
        preflight = ConnectivityVerdict.OPEN
        verdicts += ConnectivityVerdict.BLOCKED
        pilot.start { switches++ }
        scheduler.runNext()
        assertEquals(TurnAutoDecision.UNDECIDED, decision)
        assertTrue(logs.last().endsWith("decision=undecided(contradicts pre-flight, re-checking in 2.5s)"))
        // The re-check decides for itself, even though the pre-flight still says open.
        verdicts += ConnectivityVerdict.BLOCKED
        assertEquals(2_500L, scheduler.runNext())
        assertEquals(TurnAutoDecision.TURN, decision)
        assertEquals(2, probes)
    }

    @Test
    fun aWorkingDirectPathNeedsThreeConfirmations12sApart() {
        startOn("Home", ConnectivityVerdict.OPEN)
        scheduler.queue.clear() // the first report's debounced probe is irrelevant here
        verdicts += listOf(ConnectivityVerdict.BLOCKED, ConnectivityVerdict.BLOCKED, ConnectivityVerdict.BLOCKED)
        pilot.noteNetworkChange(fp("Cafe"))
        assertEquals(1_500L, scheduler.runNext())
        assertEquals(TurnAutoDecision.DIRECT, decision)
        assertTrue(logs.last().contains("fingerprintChanged=true streak=1/3 decision=direct(kept)"))
        assertEquals(12_000L, scheduler.runNext())
        assertEquals(TurnAutoDecision.DIRECT, decision)
        assertTrue(logs.last().contains("fingerprintChanged=false streak=2/3 decision=direct(kept)"))
        assertEquals(12_000L, scheduler.runNext())
        assertEquals(TurnAutoDecision.TURN, decision)
        assertTrue(logs.last().endsWith("streak=3/3 decision=turn(switch, moving flows onto the relay)"))
        assertEquals(1, switches)
        assertTrue(scheduler.queue.isEmpty())
    }

    @Test
    fun anOpenVerdictResetsTheStreak() {
        startOn("Home", ConnectivityVerdict.OPEN)
        scheduler.queue.clear()
        verdicts += listOf(ConnectivityVerdict.BLOCKED, ConnectivityVerdict.OPEN)
        pilot.noteNetworkChange(fp("Cafe"))
        scheduler.runNext()
        scheduler.runNext() // open
        verdicts += listOf(ConnectivityVerdict.BLOCKED)
        pilot.noteNetworkChange(fp("Office"))
        scheduler.runNext()
        assertTrue(logs.last().contains("streak=1/3"))
    }

    @Test
    fun pathEventsOnTheSameNetworkDoNotReprobe() {
        startOn("Home", ConnectivityVerdict.OPEN)
        scheduler.queue.clear()
        val before = probes
        repeat(5) { pilot.noteNetworkChange(fp("Home")) }
        assertTrue(scheduler.queue.isEmpty())
        assertEquals(before, probes)
        assertTrue(logs.last().contains("path event on the same network (wifi/wlan0/ssid=Home), decision=direct — not re-probing"))
    }

    @Test
    fun aBurstOfEventsLeavesOneProbe() {
        startOn("Home", ConnectivityVerdict.OPEN)
        scheduler.queue.clear()
        verdicts += ConnectivityVerdict.OPEN
        pilot.noteNetworkChange(fp("A"))
        pilot.noteNetworkChange(fp("B"))
        pilot.noteNetworkChange(fp("C"))
        val before = probes
        while (scheduler.queue.isNotEmpty()) scheduler.runNext()
        assertEquals(before + 1, probes)
    }

    @Test
    fun nobodyAnsweringKeepsAWorkingRoute() {
        startOn("Home", ConnectivityVerdict.OPEN)
        scheduler.queue.clear()
        verdicts += ConnectivityVerdict.OFFLINE
        pilot.noteNetworkChange(fp("Cafe"))
        scheduler.runNext()
        assertEquals(TurnAutoDecision.DIRECT, decision)
        assertTrue(logs.last().endsWith("decision=direct(kept, indeterminate)"))
    }

    @Test
    fun nobodyAnsweringWithoutARouteReleasesTheFlows() {
        verdicts += ConnectivityVerdict.OFFLINE
        pilot.start { switches++ }
        scheduler.runNext()
        assertEquals(TurnAutoDecision.OFFLINE, decision)
        // Offline is not settled: the next event re-probes even on the same network.
        verdicts += ConnectivityVerdict.OPEN
        pilot.noteNetworkChange(fp("Home"))
        pilot.noteNetworkChange(fp("Home"))
        while (scheduler.queue.isNotEmpty()) scheduler.runNext()
        assertEquals(TurnAutoDecision.DIRECT, decision)
    }

    @Test
    fun turnIsTerminalForTheSession() {
        startOn("Cafe", ConnectivityVerdict.BLOCKED)
        scheduler.queue.clear()
        pilot.noteNetworkChange(fp("Home"))
        pilot.noteWake()
        assertTrue(scheduler.queue.isEmpty())
        assertEquals(TurnAutoDecision.TURN, decision)
    }

    @Test
    fun wakeReprobesOnlyWithoutASettledDecision() {
        startOn("Home", ConnectivityVerdict.OPEN)
        scheduler.queue.clear()
        pilot.noteWake()
        assertTrue(scheduler.queue.isEmpty())
        assertTrue(logs.last().contains("wake, decision=direct already settled — not re-probing"))

        pilot.stop()
        verdicts += ConnectivityVerdict.OFFLINE
        pilot.start { }
        scheduler.runNext()
        pilot.noteWake()
        assertEquals(1, scheduler.queue.size)
        assertEquals(1_500L, scheduler.queue.first().delayMs)
    }

    @Test
    fun stopCancelsAndResets() {
        startOn("Home", ConnectivityVerdict.OPEN)
        pilot.stop()
        assertEquals(TurnAutoDecision.UNDECIDED, decision)
        val before = probes
        while (scheduler.queue.isNotEmpty()) scheduler.runNext()
        assertEquals(before, probes)
        // Events after stop are ignored.
        pilot.noteNetworkChange(fp("Cafe"))
        assertTrue(scheduler.queue.isEmpty())
    }

    @Test
    fun notEngagedDoesNothing() {
        engaged = false
        pilot.start { switches++ }
        pilot.noteNetworkChange(fp("Home"))
        assertTrue(scheduler.queue.isEmpty())
        assertEquals(0, probes)
    }
}
