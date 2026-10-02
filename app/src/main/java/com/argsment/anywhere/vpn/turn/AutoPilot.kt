package com.argsment.anywhere.vpn.turn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.argsment.anywhere.turn.TurnEnvironment
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.vpn.util.AnywhereLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val logger = AnywhereLogger("TurnAutoPilot")

/** Runs auto-pilot work after a delay. Injected so the timing can be faked in tests. */
fun interface AutoPilotScheduler {
    /** Runs [task] after [delayMs]; the returned function cancels it (also mid-run). */
    fun schedule(delayMs: Long, task: suspend () -> Unit): () -> Unit
}

/**
 * The platform-free half of the auto-pilot: the decision logic of iOS `TurnAutoPilot`
 * (spec §7.5), driven by fingerprints and probe outcomes. [AndroidTurnAutoPilot] feeds
 * it from a `NetworkCallback`.
 *
 * Switching to the relay is expensive and, once done, sticky for the session, so the bar
 * for it is deliberately high: the network must have actually changed, and a `BLOCKED`
 * verdict on a network that was working has to repeat [BLOCKED_CONFIRMATIONS] times,
 * [CONFIRMATION_DELAY_MS] apart, before it counts. A first `BLOCKED` that contradicts the
 * app's pre-flight is re-checked once. "Nobody answered" is a bad link, not censorship,
 * and never changes a settled route.
 */
class TurnAutoPilotCore(
    private val isEngaged: () -> Boolean,
    private val probe: suspend () -> ProbeOutcome,
    private val decision: () -> TurnAutoDecision,
    private val setDecision: (TurnAutoDecision) -> Unit,
    private val resetDecision: () -> Unit,
    /** The app's pre-flight verdict if it is at most `maxAgeMs` old. */
    private val recentPreflight: (maxAgeMs: Long) -> ConnectivityVerdict?,
    private val scheduler: AutoPilotScheduler,
    private val log: (String) -> Unit = { logger.info(it) },
) : TurnAutoPilot {

    companion object {
        /** Path updates arrive in bursts while an interface settles. */
        const val NETWORK_CHANGE_DEBOUNCE_MS = 1_500L
        /** Consecutive `BLOCKED` verdicts it takes to abandon a working direct path. */
        const val BLOCKED_CONFIRMATIONS = 3
        /** Gap between those confirmations. */
        const val CONFIRMATION_DELAY_MS = 12_000L
        /** How old the app's pre-flight may be and still describe this network. */
        const val PREFLIGHT_MAX_AGE_MS = 30_000L
        /** Gap before the one re-check of a first probe contradicting the pre-flight. */
        const val CROSS_CHECK_DELAY_MS = 2_500L
    }

    private val lock = Any()
    // All guarded by [lock].
    private var generation = 0L
    private var lastFingerprint: NetworkFingerprint? = null
    private var blockedStreak = 0
    private var fingerprintChanged = false
    private var didCrossCheckPreflight = false
    private var running = false
    private val tasks = mutableListOf<TaskHandle>()

    private class TaskHandle {
        @Volatile var cancel: () -> Unit = {}
        @Volatile var finished = false
    }
    @Volatile
    private var onSwitchToTurn: (() -> Unit)? = null

    /** Probes immediately. No-op unless the feature is on and the mode is auto. */
    override fun start(onSwitchToTurn: () -> Unit) {
        if (!isEngaged()) return
        this.onSwitchToTurn = onSwitchToTurn
        synchronized(lock) { running = true }
        schedule(0)
    }

    override fun stop() {
        val cancels = synchronized(lock) {
            running = false
            generation++
            lastFingerprint = null
            blockedStreak = 0
            fingerprintChanged = false
            didCrossCheckPreflight = false
            tasks.toList().also { tasks.clear() }
        }
        cancels.forEach { it.cancel() }
        onSwitchToTurn = null
        resetDecision()
    }

    /** iOS `wake()`: no path in hand, so only re-probe when nothing is settled. */
    override fun noteWake() = noteNetworkChange(null)

    /**
     * A path event arrived. Re-probe only when the network behind it is genuinely a
     * different one — or when there is no decision worth keeping. [fingerprint] is
     * `null` when the caller has no path in hand (waking from sleep).
     */
    fun noteNetworkChange(fingerprint: NetworkFingerprint?) {
        if (!isEngaged()) return
        if (synchronized(lock) { !running }) return
        val current = decision()
        // TURN is terminal for the session: the pools are up and re-deciding would only
        // tear down working streams.
        if (current == TurnAutoDecision.TURN) return
        val isDecided = current != TurnAutoDecision.UNDECIDED && current != TurnAutoDecision.OFFLINE

        if (fingerprint == null) {
            if (isDecided) {
                log("[TURN auto] wake, decision=${current.rawValue} already settled — not re-probing")
                return
            }
            schedule(NETWORK_CHANGE_DEBOUNCE_MS)
            return
        }

        val changed = synchronized(lock) {
            if (lastFingerprint == fingerprint) {
                false
            } else {
                lastFingerprint = fingerprint
                blockedStreak = 0
                fingerprintChanged = true
                true
            }
        }
        if (!changed && isDecided) {
            log("[TURN auto] path event on the same network ($fingerprint), decision=${current.rawValue} — not re-probing")
            return
        }
        schedule(NETWORK_CHANGE_DEBOUNCE_MS)
    }

    /**
     * Bumps the generation so a burst of events leaves one survivor: an earlier pending
     * task wakes up, sees it is stale and does nothing. A probe already in flight is not
     * cut short (its answer is still about this network); [stop] cancels everything.
     */
    private fun schedule(delayMs: Long) {
        val gen = synchronized(lock) {
            if (!running) return
            ++generation
        }
        // The handle is filled in after `schedule` returns, but the task may already be
        // running on another thread by then — hence a holder rather than a lateinit.
        val handle = TaskHandle()
        handle.cancel = scheduler.schedule(delayMs) {
            try {
                if (isCurrent(gen)) probe()
            } finally {
                synchronized(lock) {
                    handle.finished = true
                    tasks.remove(handle)
                }
            }
        }
        synchronized(lock) { if (running && !handle.finished) tasks.add(handle) }
    }

    private fun isCurrent(gen: Long): Boolean = synchronized(lock) { running && generation == gen }

    private fun shouldCrossCheckAgainstPreflight(): Boolean {
        if (recentPreflight(PREFLIGHT_MAX_AGE_MS) != ConnectivityVerdict.OPEN) return false
        return synchronized(lock) {
            if (didCrossCheckPreflight) false else {
                didCrossCheckPreflight = true
                true
            }
        }
    }

    private suspend fun probe() {
        if (!isEngaged()) return
        val previous = decision()
        val changed = synchronized(lock) { fingerprintChanged.also { fingerprintChanged = false } }
        val (verdict, results) = probe.invoke()
        if (!isEngaged() || synchronized(lock) { !running }) return

        when (verdict) {
            ConnectivityVerdict.OPEN -> {
                synchronized(lock) { blockedStreak = 0 }
                setDecision(TurnAutoDecision.DIRECT)
                logDecision(verdict, results, changed, null, "direct")
            }

            ConnectivityVerdict.BLOCKED -> {
                // Only a *switch* rebuilds the outbound state; the first probe of a session
                // has no live direct connections to tear down, so it decides on the spot —
                // flows wait at most three seconds for it.
                if (previous != TurnAutoDecision.DIRECT) {
                    if (shouldCrossCheckAgainstPreflight()) {
                        logDecision(verdict, results, changed, null,
                            "undecided(contradicts pre-flight, re-checking in 2.5s)")
                        schedule(CROSS_CHECK_DELAY_MS)
                        return
                    }
                    setDecision(TurnAutoDecision.TURN)
                    logDecision(verdict, results, changed, null, "turn(first probe)")
                    return
                }
                val streak = synchronized(lock) { ++blockedStreak }
                if (streak < BLOCKED_CONFIRMATIONS) {
                    logDecision(verdict, results, changed, streak, "direct(kept)")
                    schedule(CONFIRMATION_DELAY_MS)
                    return
                }
                setDecision(TurnAutoDecision.TURN)
                logDecision(verdict, results, changed, streak, "turn(switch, moving flows onto the relay)")
                onSwitchToTurn?.invoke()
            }

            ConnectivityVerdict.OFFLINE -> {
                // Not even the domestic hosts answered: a saturated or dropping network,
                // not a verdict about censorship — a working route stays.
                if (previous != TurnAutoDecision.DIRECT && previous != TurnAutoDecision.TURN) {
                    // No route to keep: release the flows rather than hold them hostage.
                    setDecision(TurnAutoDecision.OFFLINE)
                    logDecision(verdict, results, changed, null, "offline")
                    return
                }
                logDecision(verdict, results, changed, null, "${previous.rawValue}(kept, indeterminate)")
            }
        }
    }

    /**
     * One line per decision with the evidence behind it, at info level so it survives into
     * release builds and the in-app log viewer. Same text as iOS.
     */
    private fun logDecision(
        verdict: ConnectivityVerdict,
        results: List<HostResult>,
        changed: Boolean,
        streak: Int?,
        decision: String,
    ) {
        val hosts = results.joinToString(", ") { "${it.host} ${if (it.reachable) "✓" else "✗"}${it.elapsedMs}ms" }
        val streakText = streak?.let { " streak=$it/$BLOCKED_CONFIRMATIONS" } ?: ""
        log("[TURN auto] verdict=${verdict.rawValue} hosts=[$hosts] fingerprintChanged=$changed$streakText decision=$decision")
    }
}

/**
 * The Android auto-pilot: [TurnAutoPilotCore] fed by a default-network callback (the
 * `NWPathMonitor` equivalent). The app is excluded from its own VPN, so the app's default
 * network is the underlying physical one; VPN networks are ignored regardless.
 *
 * Installed through [TurnAutoState.autoPilotFactory] by [install].
 */
class AndroidTurnAutoPilot(context: Context) : TurnAutoPilot {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val core = TurnAutoPilotCore(
        isEngaged = {
            val settings = TurnEnvironment.settings
            settings != null && settings.turnActiveForSession && settings.effectiveTurnMode == TurnMode.AUTO
        },
        probe = { ConnectivityProbe.classifyDetailed(app, ProbeProfile.BACKGROUND) },
        decision = { TurnAutoState.decision },
        setDecision = { TurnAutoState.setDecision(it) },
        resetDecision = { TurnAutoState.reset() },
        recentPreflight = { maxAge ->
            ConnectivityVerdict.fromRaw(TurnEnvironment.settings?.recentPreflightVerdictRaw(maxAge))
        },
        scheduler = { delayMs, task ->
            val job: Job = scope.launch {
                if (delayMs > 0) delay(delayMs)
                task()
            }
            val cancel: () -> Unit = { job.cancel() }
            cancel
        },
    )

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val callbackLock = Any()
    // Last view of the default network, touched only on the callback thread.
    private var network: Network? = null
    private var caps: NetworkCapabilities? = null
    private var linkProperties: LinkProperties? = null

    override fun start(onSwitchToTurn: () -> Unit) {
        core.start(onSwitchToTurn)
        registerCallback()
    }

    override fun stop() {
        unregisterCallback()
        core.stop()
        scope.cancel()
    }

    override fun noteWake() = core.noteWake()

    private fun registerCallback() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (this@AndroidTurnAutoPilot.network != network) {
                    this@AndroidTurnAutoPilot.network = network
                    caps = null
                    linkProperties = null
                }
                // Capabilities and link properties follow immediately; the fingerprint
                // is computed once both are known.
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (network != this@AndroidTurnAutoPilot.network) return
                caps = networkCapabilities
                report()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network != this@AndroidTurnAutoPilot.network) return
                linkProperties = lp
                report()
            }

            override fun onLost(network: Network) {
                // iOS only looks at satisfied paths; the next network reports itself.
                if (network == this@AndroidTurnAutoPilot.network) {
                    this@AndroidTurnAutoPilot.network = null
                    caps = null
                    linkProperties = null
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            synchronized(callbackLock) { callback = cb }
        } catch (e: Exception) {
            logger.warning("[TURN auto] cannot watch the network: ${e.message}")
        }
    }

    private fun unregisterCallback() {
        val cb = synchronized(callbackLock) { callback.also { callback = null } } ?: return
        try {
            app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    private fun report() {
        val c = caps ?: return
        val lp = linkProperties ?: return
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        @Suppress("DEPRECATION")
        val fingerprint = NetworkFingerprint.from(c, lp) {
            // Pre-Android 12 fallback; needs location permission to return a real name,
            // without it the SSID stays unknown and the gateway decides.
            runCatching { app.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
        }
        core.noteNetworkChange(fingerprint)
    }

    companion object {
        /** Installs this pilot as the one the VPN service starts (idempotent). */
        fun install() {
            if (TurnAutoState.autoPilotFactory == null) {
                TurnAutoState.autoPilotFactory = { AndroidTurnAutoPilot(it) }
            }
        }
    }
}
