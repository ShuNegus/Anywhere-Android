package com.argsment.anywhere.vpn.turn

import android.content.Context
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.turn.TurnSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The route auto mode has settled on (iOS `TurnAutoState.Decision`). */
enum class TurnAutoDecision(val rawValue: String) {
    /** No probe has finished yet — flows wait rather than guess. */
    UNDECIDED("undecided"),
    DIRECT("direct"),
    TURN("turn"),
    /** Nothing was reachable; treated as "no bypass" so flows are not held hostage. */
    OFFLINE("offline"),
}

/**
 * The auto-mode network watcher (iOS `TurnAutoPilot`, spec §7.5). **Phase B** provides
 * the implementation (connectivity probe + `NetworkCallback` fingerprinting) and plugs it
 * in through [TurnAutoState.autoPilotFactory]. It reports through
 * [TurnAutoState.setDecision].
 */
interface TurnAutoPilot {
    /**
     * Called when the tunnel starts, before the stack is up. [onSwitchToTurn] must be
     * invoked on a direct → turn switch so live direct flows are moved onto the relay
     * (`LwipStack.handleTurnRouteChanged`).
     */
    fun start(onSwitchToTurn: () -> Unit)

    /** Called when the tunnel stops. Must cancel all probes. */
    fun stop()
}

/**
 * The auto-mode decision shared by everything that honours it. Mirrors iOS
 * `TurnAutoState`, plus the hook through which Phase B installs the auto-pilot.
 *
 * Until an auto-pilot is installed, `auto` behaves like `on` (see
 * [TurnDialerRegistry.isActive]) and flows never wait for a decision.
 */
object TurnAutoState {

    /** Set by Phase B (e.g. from `Application.onCreate` or the service). */
    @Volatile
    var autoPilotFactory: ((Context) -> TurnAutoPilot)? = null

    val isAutoPilotInstalled: Boolean get() = autoPilotFactory != null

    @Volatile
    private var pilot: TurnAutoPilot? = null

    private data class State(val decision: TurnAutoDecision, val generation: Long)

    private val lock = Any()
    private val state = MutableStateFlow(State(TurnAutoDecision.UNDECIDED, 0))
    private val _decision = MutableStateFlow(TurnAutoDecision.UNDECIDED)

    /** Observable for the UI (spec §7.6). */
    val decisionFlow: StateFlow<TurnAutoDecision> = _decision.asStateFlow()

    val decision: TurnAutoDecision get() = state.value.decision

    /**
     * Records a verdict. `TURN` is sticky for the life of the tunnel session: flipping
     * back would strand every stream already on the relay.
     */
    fun setDecision(new: TurnAutoDecision) {
        synchronized(lock) {
            val current = state.value
            if (current.decision == TurnAutoDecision.TURN) return
            state.value = current.copy(decision = new)
            _decision.value = new
        }
    }

    /**
     * Suspends until a verdict exists, so the first flows of a session do not dial
     * direct out of a censored network before the probe lands. Returns at once when the
     * effective mode is not auto, no auto-pilot is installed, or a decision is in hand;
     * [timeoutMs] is the safety net for a probe that never lands.
     */
    suspend fun waitForDecision(settings: TurnSettings, timeoutMs: Long = 3_000) {
        if (!isAutoPilotInstalled) return
        if (settings.effectiveTurnMode != TurnMode.AUTO) return
        val start = state.value
        if (start.decision != TurnAutoDecision.UNDECIDED) return
        withTimeoutOrNull(timeoutMs) {
            state.first { it.decision != TurnAutoDecision.UNDECIDED || it.generation != start.generation }
        }
    }

    /** Back to square one for the next session; anyone waiting is released. */
    fun reset() {
        synchronized(lock) {
            val current = state.value
            state.value = State(TurnAutoDecision.UNDECIDED, current.generation + 1)
            _decision.value = TurnAutoDecision.UNDECIDED
        }
    }

    /** Service hook: starts the installed auto-pilot (no-op until Phase B installs one). */
    fun startPilot(context: Context, settings: TurnSettings, onSwitchToTurn: () -> Unit) {
        stopPilot()
        if (!settings.turnActiveForSession || settings.effectiveTurnMode != TurnMode.AUTO) return
        val factory = autoPilotFactory ?: return
        pilot = factory(context).also { it.start(onSwitchToTurn) }
    }

    /** Service hook: stops the auto-pilot and resets the decision. */
    fun stopPilot() {
        pilot?.stop()
        pilot = null
        reset()
    }
}
