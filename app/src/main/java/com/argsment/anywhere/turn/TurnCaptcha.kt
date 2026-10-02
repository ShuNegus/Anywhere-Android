package com.argsment.anywhere.turn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The vk-turn manual captcha solver. When the user picked manual captchas — or the
 * automatic solver gave up and the core escalated — the Go core serves the VK captcha
 * page on a fixed loopback port until it has a token, then shuts the server down. The
 * app knows nothing about the captcha beyond "the port is listening". Mirrors iOS
 * `TurnCaptcha` (`Shared/Networking/Protocols/Turn/TurnCaptcha.swift`, spec §9.2).
 *
 * The page is plain HTTP on 127.0.0.1, which `res/xml/network_security_config.xml`
 * allows for loopback only.
 */
object TurnCaptcha {
    const val HOST = "127.0.0.1"
    const val PORT = 8765
    const val URL = "http://$HOST:$PORT/"

    /** iOS notification identifier; used as the Android notification tag. */
    const val NOTIFICATION_ID = "turn-captcha"

    const val POLL_INTERVAL_MS = 1_500L
    private const val PROBE_TIMEOUT_MS = 1_000

    /**
     * Pure TCP connect, no HTTP request, so the probe has no side effect on the
     * captcha proxy. True while the captcha server is up.
     */
    suspend fun probe(timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(HOST, PORT), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Whether a captcha may be waiting this session, i.e. whether the captcha watchers
 * (in-app monitor and the service-side notifier) should probe at all.
 *
 * Uses the *effective* mode: a session forced through TURN by holding the power button
 * runs the relay even when the stored mode is `off` or the master switch is off. (The
 * iOS app monitor looks at the stored mode and misses that case — spec §9.3/§14 #14.)
 */
fun TurnSettings.captchaWatchApplies(): Boolean =
    turnActiveForSession && effectiveTurnMode != TurnMode.OFF

/**
 * The service-side notifier's edge trigger (iOS `TurnCaptchaNotifier.loop`). Feed it one
 * tick per poll; it says what to do with the notification. Pure, so it is unit-tested.
 */
class TurnCaptchaEdgeTrigger {

    enum class Action { NONE, POST, CLEAR }

    /** Whether the server was up on the previous probed tick. */
    var wasReachable: Boolean = false
        private set

    /**
     * @param applies [captchaWatchApplies] for this tick; when false there is no probe.
     * @param reachable the probe result (ignored when [applies] is false).
     * @param appInForeground the app is on screen — its own sheet covers the captcha,
     *   so no notification (iOS shows no banner in the foreground either).
     */
    fun step(applies: Boolean, reachable: Boolean, appInForeground: Boolean): Action {
        if (!applies) {
            // TURN went inactive while a notification may still be up.
            val action = if (wasReachable) Action.CLEAR else Action.NONE
            wasReachable = false
            return action
        }
        val action = when {
            reachable && !wasReachable -> if (appInForeground) Action.NONE else Action.POST
            // Server gone: the captcha was solved or abandoned.
            !reachable && wasReachable -> Action.CLEAR
            else -> Action.NONE
        }
        wasReachable = reachable
        return action
    }

    fun reset() {
        wasReachable = false
    }
}

/**
 * The in-app captcha sheet state (iOS `TurnCaptchaMonitor`, spec §9.3), as a pure value
 * so the monitor's transitions are unit-tested.
 *
 * @property showCaptcha the sheet should be up.
 * @property captchaWaiting raw "a captcha is waiting", even when the sheet was closed —
 *   the connection graph needs it (a dismissed captcha is still unsolved).
 * @property suppressed the user closed the sheet; it stays closed until the server goes
 *   away and a fresh captcha appears.
 */
data class TurnCaptchaSheetState(
    val showCaptcha: Boolean = false,
    val captchaWaiting: Boolean = false,
    val suppressed: Boolean = false,
) {
    /** One poll. [applies] is [captchaWatchApplies]; [reachable] is ignored without it. */
    fun onTick(applies: Boolean, reachable: Boolean): TurnCaptchaSheetState = when {
        !applies -> copy(showCaptcha = false)
        reachable -> copy(captchaWaiting = true, showCaptcha = showCaptcha || !suppressed)
        else -> TurnCaptchaSheetState()
    }

    /** The user closed the sheet (Close button, back, outside tap). */
    fun userDismissed(): TurnCaptchaSheetState = copy(suppressed = true, showCaptcha = false)

    /** "Solve Captcha" button or a tap on the notification. */
    fun requestShow(): TurnCaptchaSheetState = copy(suppressed = false, showCaptcha = true)
}
