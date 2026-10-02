package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.turn.TurnEnvironment
import com.argsment.anywhere.turn.TurnMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Publishes the TURN pools' state to the UI. The iOS app polls the extension once a
 * second over IPC (`fetchTurnStats`); here the service and the UI share a process, so
 * the service polls the registry and exposes a [StateFlow] (spec §6.1/§6.2, data side).
 *
 * Running while the VPN is up; [stop] resets to an empty snapshot. Phase B's view model
 * derives the connection graph, captcha latch and watchdogs from [snapshot].
 */
object TurnStatusMonitor {

    const val POLL_INTERVAL_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var job: Job? = null

    private val _snapshot = MutableStateFlow(TurnStatsSnapshot())
    val snapshot: StateFlow<TurnStatsSnapshot> = _snapshot.asStateFlow()

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                _snapshot.value = poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _snapshot.value = TurnStatsSnapshot()
    }

    /** One sample: what iOS returns for `TunnelMessage.fetchTurnStats`. */
    fun poll(): TurnStatsSnapshot {
        val settings = TurnEnvironment.settings
        val auto = settings != null && settings.turnActiveForSession &&
            settings.effectiveTurnMode == TurnMode.AUTO && TurnAutoState.isAutoPilotInstalled
        return TurnStatsSnapshot(
            hosts = TurnDialerRegistry.shared.statistics(),
            autoDecision = if (auto) TurnAutoState.decision.rawValue else null,
        )
    }
}
