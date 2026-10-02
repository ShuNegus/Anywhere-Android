package com.argsment.anywhere.turn

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs in the VPN service for the life of the tunnel (iOS: `TurnCaptchaNotifier` in the
 * Network Extension, spec §9.4). The in-app monitor only runs while the UI is alive;
 * this one keeps watching the captcha port while the app is in the background — which is
 * exactly when a captcha strands the relay — and posts a notification on the
 * false → true edge if the app is not in the foreground. The true → false edge (solved
 * or abandoned) removes it.
 */
object TurnCaptchaNotifier {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        TurnCaptchaNotifications.ensureChannel(app)
        job = scope.launch { loop(app) }
    }

    /** Stops polling and removes a pending/delivered captcha notification. */
    @Synchronized
    fun stop(context: Context) {
        job?.cancel()
        job = null
        TurnCaptchaNotifications.cancel(context.applicationContext)
    }

    private suspend fun loop(context: Context) {
        val trigger = TurnCaptchaEdgeTrigger()
        while (currentCoroutineContext().isActive) {
            val settings = TurnEnvironment.settings
            val applies = settings?.captchaWatchApplies() == true
            val reachable = applies && TurnCaptcha.probe()
            val foreground = settings?.appIsForeground == true
            when (trigger.step(applies, reachable, foreground)) {
                TurnCaptchaEdgeTrigger.Action.POST -> TurnCaptchaNotifications.post(context)
                TurnCaptchaEdgeTrigger.Action.CLEAR -> TurnCaptchaNotifications.cancel(context)
                TurnCaptchaEdgeTrigger.Action.NONE -> Unit
            }
            delay(TurnCaptcha.POLL_INTERVAL_MS)
        }
    }
}
