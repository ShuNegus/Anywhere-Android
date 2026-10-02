package com.argsment.anywhere.viewmodel

import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.vpn.turn.TurnAutoDecision
import com.argsment.anywhere.vpn.turn.TurnFetchState
import com.argsment.anywhere.vpn.turn.TurnPhase
import com.argsment.anywhere.vpn.turn.TurnPoolSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `evaluateTurnProgress` (spec §6.3) under a fake clock. */
class TurnProgressWatchdogTest {

    private var clock = 0L
    private val watchdog = TurnProgressWatchdog { clock }

    private fun pool(
        fetch: TurnFetchState,
        usable: Boolean = false,
        sets: Int = 0,
        captchas: Int = 0,
    ) = TurnPoolSummary(usable, sessions = if (usable) 10 else 0, target = 10, fetch = fetch, captchas = captchas, setsObtained = sets)

    private fun eval(
        pool: TurnPoolSummary?,
        phase: TurnPhase? = null,
        mode: TurnMode? = TurnMode.ON,
        decision: TurnAutoDecision? = null,
    ) = watchdog.evaluate(phase, decision, pool, mode)

    private fun at(seconds: Long, block: () -> TurnProgressWatchdog.Outcome): TurnProgressWatchdog.Outcome {
        clock = seconds * 1000
        return block()
    }

    @Test
    fun theTunnelTimesOutAfter45sWithoutProgress() {
        assertNull(at(0) { eval(pool(TurnFetchState.FETCHING)) }.failure)
        assertNull(at(45) { eval(pool(TurnFetchState.FETCHING)) }.failure)
        assertEquals(ConnectionFailure.TURN_TIMEOUT, at(46) { eval(pool(TurnFetchState.FETCHING)) }.failure)
    }

    /** The budget is per credential set: a finished login restarts the clock. */
    @Test
    fun aNewCredentialSetRestartsTheClock() {
        at(0) { eval(pool(TurnFetchState.FETCHING, sets = 0)) }
        at(40) { eval(pool(TurnFetchState.FETCHING, sets = 1)) }
        assertNull(at(80) { eval(pool(TurnFetchState.FETCHING, sets = 1)) }.failure)
        assertEquals(ConnectionFailure.TURN_TIMEOUT, at(86) { eval(pool(TurnFetchState.FETCHING, sets = 1)) }.failure)
    }

    @Test
    fun theAutoSolverGets90s() {
        at(0) { eval(pool(TurnFetchState.FETCHING)) }
        // Entering the captcha restarts the clock.
        at(30) { eval(pool(TurnFetchState.CAPTCHA_AUTO)) }
        assertNull(at(110) { eval(pool(TurnFetchState.CAPTCHA_AUTO)) }.failure)
        assertEquals(ConnectionFailure.CAPTCHA_TIMEOUT, at(121) { eval(pool(TurnFetchState.CAPTCHA_AUTO)) }.failure)
        assertTrue(watchdog.captchaSeenInSession)
    }

    /** A captcha restarts the clock once per set, so a solver failing in a loop still times out. */
    @Test
    fun aLoopingSolverStillTimesOut() {
        at(0) { eval(pool(TurnFetchState.CAPTCHA_AUTO, sets = 0)) }
        at(30) { eval(pool(TurnFetchState.FETCHING, sets = 0)) }
        at(31) { eval(pool(TurnFetchState.CAPTCHA_AUTO, sets = 0)) }
        assertEquals(ConnectionFailure.CAPTCHA_TIMEOUT, at(91) { eval(pool(TurnFetchState.CAPTCHA_AUTO, sets = 0)) }.failure)
    }

    @Test
    fun aManualCaptchaHasNoDeadline() {
        at(0) { eval(pool(TurnFetchState.CAPTCHA_WAIT)) }
        assertNull(at(3_600) { eval(pool(TurnFetchState.CAPTCHA_WAIT)) }.failure)
        // Phase fallback when the core reports no pool.
        assertNull(at(7_200) { eval(null, phase = TurnPhase.CAPTCHA_WAIT) }.failure)
    }

    @Test
    fun aWorkingTunnelHasNoDeadlineAndClearsTimeouts() {
        at(0) { eval(pool(TurnFetchState.FETCHING)) }
        val outcome = at(600) { eval(pool(TurnFetchState.CAPTCHA_WAIT, usable = true)) }
        assertNull(outcome.failure)
        assertTrue(outcome.clearTimeoutFailure)
        // Phase fallback: READY without a pool also counts as working.
        assertTrue(at(1_200) { eval(null, phase = TurnPhase.READY) }.clearTimeoutFailure)
        // The clock starts afresh once it stops working.
        assertNull(at(1_201) { eval(pool(TurnFetchState.FETCHING)) }.failure)
        assertNull(at(1_240) { eval(pool(TurnFetchState.FETCHING)) }.failure)
    }

    @Test
    fun directRoutesHaveNoDeadline() {
        at(0) { eval(pool(TurnFetchState.FETCHING), mode = TurnMode.AUTO, decision = TurnAutoDecision.DIRECT) }
        assertNull(at(500) { eval(pool(TurnFetchState.FETCHING), mode = TurnMode.AUTO, decision = TurnAutoDecision.UNDECIDED) }.failure)
        assertNull(at(900) { eval(pool(TurnFetchState.FETCHING), mode = null) }.failure)
        assertNull(at(1_000) { eval(pool(TurnFetchState.FETCHING), mode = TurnMode.OFF) }.failure)
    }

    @Test
    fun offlineAlertsOnce() {
        val decision = TurnAutoDecision.OFFLINE
        assertTrue(eval(null, mode = TurnMode.AUTO, decision = decision).alertOffline)
        assertFalse(eval(null, mode = TurnMode.AUTO, decision = decision).alertOffline)
        watchdog.reset()
        assertTrue(eval(null, mode = TurnMode.AUTO, decision = decision).alertOffline)
    }

    /** The core counts captchas: one solved between two polls is still latched. */
    @Test
    fun captchaCounterLatches() {
        eval(pool(TurnFetchState.FETCHING, captchas = 1))
        assertTrue(watchdog.captchaSeenInSession)
        watchdog.reset()
        assertFalse(watchdog.captchaSeenInSession)
        watchdog.noteCaptchaSeen()
        assertTrue(watchdog.captchaSeenInSession)
    }
}
