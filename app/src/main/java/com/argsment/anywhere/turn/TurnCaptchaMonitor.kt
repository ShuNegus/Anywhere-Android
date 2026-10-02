package com.argsment.anywhere.turn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the core's loopback captcha server while TURN is in use and drives the in-app
 * captcha sheet. Mirrors iOS `TurnCaptchaMonitor` (spec §9.3).
 *
 * Polling does not depend on the captcha mode: the server only appears when manual
 * solving is actually needed — the user chose it, or the automatic solver gave up.
 *
 * Activation (`MainActivity` → `TurnCaptchaHost`): `turnActiveForSession &&
 * effectiveTurnMode != off && status == connected`. The home screen reads
 * [captchaWaiting] for its graph and calls [requestShow] for a "solve" affordance; the
 * sheet itself is hosted once at the activity root, over every tab.
 */
object TurnCaptchaMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var job: Job? = null

    private val state = MutableStateFlow(TurnCaptchaSheetState())

    /** The full state; [showCaptcha]/[captchaWaiting] are views of it. */
    val sheetState: StateFlow<TurnCaptchaSheetState> = state.asStateFlow()

    /** The sheet should be up. */
    val showCaptcha: Flow<Boolean> = state.map { it.showCaptcha }.distinctUntilChanged()

    /** A captcha is waiting, whether or not the sheet is up (for the connection graph). */
    val captchaWaiting: Flow<Boolean> = state.map { it.captchaWaiting }.distinctUntilChanged()

    @Synchronized
    fun setActive(active: Boolean) {
        if (active) {
            if (job?.isActive == true) return
            job = scope.launch { loop() }
        } else {
            // Only an active monitor resets: on a cold start from the notification,
            // requestShow() lands before the VPN status is known and must survive the
            // initial "inactive".
            val running = job ?: return
            running.cancel()
            job = null
            state.value = TurnCaptchaSheetState()
        }
    }

    /** The user closed the sheet: keep it closed until a new captcha appears. */
    fun userDismissed() = state.update { it.userDismissed() }

    /** "Solve Captcha" in TURN settings, or a tap on the captcha notification. */
    fun requestShow() = state.update { it.requestShow() }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val applies = TurnEnvironment.settings?.captchaWatchApplies() == true
            val reachable = applies && TurnCaptcha.probe()
            state.update { it.onTick(applies, reachable) }
            delay(TurnCaptcha.POLL_INTERVAL_MS)
        }
    }
}
