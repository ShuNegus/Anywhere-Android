package com.argsment.anywhere.vpn.turn

import android.content.ContextWrapper
import com.argsment.anywhere.turn.FakeSharedPreferences
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.turn.TurnSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnAutoStateTest {

    private class FakePilot : TurnAutoPilot {
        var started = 0
        var stopped = 0
        override fun start(onSwitchToTurn: () -> Unit) {
            started++
            TurnAutoState.setDecision(TurnAutoDecision.DIRECT)
        }
        override fun stop() { stopped++ }
    }

    private val pilots = mutableListOf<FakePilot>()
    private val settings = TurnSettings(FakeSharedPreferences())

    @After
    fun tearDown() {
        TurnAutoState.stopPilot()
        TurnAutoState.autoPilotFactory = null
    }

    /**
     * Switching to auto in the TURN screen while connected: no pilot was started with the
     * session, so without a lazy start every flow would wait out the full timeout.
     */
    @Test
    fun autoChosenMidSessionStartsThePilotInsteadOfStallingFlows() = runBlocking {
        TurnAutoState.autoPilotFactory = { FakePilot().also { pilots += it } }
        settings.turnMode = TurnMode.ON
        TurnAutoState.startPilot(ContextWrapper(null), settings) {}
        assertEquals(0, pilots.size)

        settings.turnMode = TurnMode.AUTO
        val started = System.nanoTime()
        TurnAutoState.waitForDecision(settings, timeoutMs = 3_000)
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(1, pilots.size)
        assertEquals(TurnAutoDecision.DIRECT, TurnAutoState.decision)
        assertTrue("waited $waitedMs ms", waitedMs < 1_000)

        // Later flows reuse the running pilot.
        TurnAutoState.waitForDecision(settings)
        assertEquals(1, pilots.single().started)

        TurnAutoState.stopPilot()
        assertEquals(1, pilots.single().stopped)
        assertEquals(TurnAutoDecision.UNDECIDED, TurnAutoState.decision)
    }

    /** Outside a tunnel session nothing is started and nothing waits. */
    @Test
    fun noSessionNoPilotNoWait() = runBlocking {
        TurnAutoState.autoPilotFactory = { FakePilot().also { pilots += it } }
        settings.turnMode = TurnMode.AUTO
        val started = System.nanoTime()
        TurnAutoState.waitForDecision(settings, timeoutMs = 3_000)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        assertTrue(pilots.isEmpty())
    }
}
