package com.argsment.anywhere.viewmodel

import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.vpn.turn.TurnAutoDecision
import com.argsment.anywhere.vpn.turn.TurnFetchState
import com.argsment.anywhere.vpn.turn.TurnPhase
import com.argsment.anywhere.vpn.turn.TurnPoolSummary

/**
 * Latches the captcha and watches the bypass for a stall — iOS
 * `VPNViewModel.evaluateTurnProgress()` (spec §6.3), lifted out of the view model so it
 * can be tested with a fake clock.
 *
 * The core has no failure phase of its own: a handshake that keeps failing simply loops
 * `vkAccess → tunnelSetup → vkAccess`, and the phase can even move backwards. A deadline
 * is the only way to call it. Both deadlines are a budget for *one* step (one credential
 * set / one captcha): the clock restarts when a set is obtained or a new captcha comes up
 * (at most once per set, so an auto-solver failing in a loop still times out). Waiting
 * on the user for a manual captcha has no deadline, and nothing has a deadline once the
 * tunnel carries traffic.
 *
 * @param now monotonic milliseconds.
 */
class TurnProgressWatchdog(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {

    companion object {
        /** The core's own ready timeout is 30 s; this leaves room for one retry. */
        const val TURN_READY_DEADLINE_MS = 45_000L
        /** The auto solver is slower than the pool and gets its own, longer budget. */
        const val CAPTCHA_AUTO_DEADLINE_MS = 90_000L
    }

    /** What the view model must do after an evaluation. */
    data class Outcome(
        /** Record this failure (red node + alert), unless it is already the current one. */
        val failure: ConnectionFailure? = null,
        /** The tunnel works: clear a recorded TURN/captcha timeout. */
        val clearTimeoutFailure: Boolean = false,
        /** Show the "network lost" alert once (auto decision is offline). */
        val alertOffline: Boolean = false,
    )

    /** The captcha really came up during this session (graph shows the step as taken). */
    var captchaSeenInSession = false
        private set

    private var turnRouteSince: Long? = null
    private var lastTurnSetsObtained = -1
    private var lastTurnPhaseWasCaptcha = false
    private var lastCaptchaRestartSets: Int? = null
    private var didAlertOffline = false

    fun noteCaptchaSeen() {
        captchaSeenInSession = true
    }

    /** iOS `stopTurnPhaseWatch` / the start of `connectVPN`. */
    fun reset() {
        captchaSeenInSession = false
        turnRouteSince = null
        lastTurnSetsObtained = -1
        lastTurnPhaseWasCaptcha = false
        lastCaptchaRestartSets = null
        didAlertOffline = false
    }

    /**
     * One poll.
     *
     * @param turnMode the session's effective mode, `null` when TURN is not active for it.
     */
    fun evaluate(
        phase: TurnPhase?,
        autoDecision: TurnAutoDecision?,
        pool: TurnPoolSummary?,
        turnMode: TurnMode?,
    ): Outcome {
        // The pool's fetch state is the precise signal; the phase is the fallback.
        val fetch = pool?.fetch
        val inCaptcha = fetch?.isCaptcha ?: (phase == TurnPhase.CAPTCHA_AUTO || phase == TurnPhase.CAPTCHA_WAIT)
        val captchaWaitsForUser = fetch?.let { it == TurnFetchState.CAPTCHA_WAIT } ?: (phase == TurnPhase.CAPTCHA_WAIT)
        if (inCaptcha) captchaSeenInSession = true
        // The core counts captchas, so one that solved itself between two polls is still seen.
        if ((pool?.captchas ?: 0) > 0) captchaSeenInSession = true

        var stepAdvanced = false
        pool?.setsObtained?.let { obtained ->
            if (obtained > lastTurnSetsObtained) stepAdvanced = true
            lastTurnSetsObtained = obtained
        }
        if (inCaptcha && !lastTurnPhaseWasCaptcha) {
            val sets = pool?.setsObtained
            if (sets == null || sets != lastCaptchaRestartSets) {
                stepAdvanced = true
                lastCaptchaRestartSets = sets
            }
        }
        lastTurnPhaseWasCaptcha = inCaptcha

        if (autoDecision == TurnAutoDecision.OFFLINE) {
            if (didAlertOffline) return Outcome()
            didAlertOffline = true
            return Outcome(alertOffline = true)
        }

        if (ConnectionStage.route(turnMode, autoDecision) != ConnectionRoute.TURN) {
            turnRouteSince = null
            return Outcome()
        }

        // The tunnel carries traffic: whatever the core still does — topping the pool up,
        // a captcha for more peers, a backoff — has no deadline, it retries on its own.
        if (pool?.usable ?: (phase == TurnPhase.READY)) {
            turnRouteSince = null
            return Outcome(clearTimeoutFailure = true)
        }

        val t = now()
        val since = if (stepAdvanced) t else (turnRouteSince ?: t)
        turnRouteSince = since

        // Waiting on the user, not on the tunnel — the clock does not apply.
        if (captchaWaitsForUser) return Outcome()
        return if (inCaptcha) {
            if (t - since > CAPTCHA_AUTO_DEADLINE_MS) Outcome(failure = ConnectionFailure.CAPTCHA_TIMEOUT) else Outcome()
        } else {
            if (t - since > TURN_READY_DEADLINE_MS) Outcome(failure = ConnectionFailure.TURN_TIMEOUT) else Outcome()
        }
    }
}
