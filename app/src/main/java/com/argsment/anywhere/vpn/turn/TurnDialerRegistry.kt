package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.turn.TurnEnvironment
import com.argsment.anywhere.turn.TurnMetadataStore
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.turn.TurnSettings
import com.argsment.anywhere.vpn.protocol.ProxyConnection
import com.argsment.anywhere.vpn.util.AnywhereLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import app.anywhere.turn.anywhere.Anywhere
import java.util.concurrent.atomic.AtomicReference

private val logger = AnywhereLogger("TurnDialerRegistry")

/**
 * One dialer per relay host, shared by every flow that goes through that host. Mirrors
 * iOS `TurnDialerRegistry` (spec §5.3).
 *
 * Dialers are expensive (a pool of TURN sessions, a VK login, possibly a captcha) and
 * cheap to reuse, so they are created lazily and live as long as the tunnel session.
 * [reset] tears them all down — on tunnel stop and on configuration switch.
 *
 * @param autoDecision the auto-pilot's current decision ([TurnAutoState.decision]).
 */
class TurnDialerRegistry internal constructor(
    private val settingsProvider: () -> TurnSettings?,
    private val storeProvider: () -> TurnMetadataStore?,
    private val factory: TurnDialerFactory,
    private val memory: TurnMemory,
    private val autoDecision: () -> TurnAutoDecision,
    private val coreLog: (String) -> Unit,
) {

    private val lock = Any()
    /** Guarded by [lock]. */
    private val dialers = LinkedHashMap<String, TurnDialerHandle>()
    /** The user settings the live dialers were built from; guarded by [lock]. */
    private var fingerprint = ""

    /**
     * Whether TURN should be used at all right now. In `auto` this follows the
     * auto-pilot's verdict, so flows go direct until a probe says the network is
     * censored.
     */
    fun isActive(): Boolean {
        val settings = settingsProvider() ?: return false
        if (!settings.turnActiveForSession || effectiveVKLink().isEmpty()) return false
        return when (settings.effectiveTurnMode) {
            TurnMode.OFF -> false
            TurnMode.ON -> true
            // Only an explicit verdict activates the relay; until the probe lands (or when
            // it says direct/offline) flows dial directly.
            TurnMode.AUTO -> autoDecision() == TurnAutoDecision.TURN
        }
    }

    /** The user's own link wins, otherwise the one the subscription shipped. */
    fun effectiveVKLink(): String {
        val manual = settingsProvider()?.vkLink?.trim().orEmpty()
        if (manual.isNotEmpty()) return manual
        return storeProvider()?.subscriptionVKLink()?.trim().orEmpty()
    }

    /**
     * The dialer for [host], created on first use. `null` when TURN is off,
     * unconfigured, or the subscription lists no usable relay for that host.
     */
    fun dialer(host: String): TurnDialerHandle? {
        if (!isActive()) return null
        val settings = settingsProvider() ?: return null
        val store = storeProvider() ?: return null
        val server = store.server(host)?.takeIf { it.isUsable } ?: return null

        val vkLink = effectiveVKLink()
        val requestedPeers = settings.peers
        val manualCaptcha = settings.captchaManual
        // Only what the *user* configured goes in here — never the memory-derived cap.
        val newFingerprint = TurnPoolFingerprint.make(vkLink, requestedPeers, manualCaptcha)

        val stale: List<TurnDialerHandle> = synchronized(lock) {
            if (fingerprint == newFingerprint || dialers.isEmpty()) {
                fingerprint = newFingerprint
                emptyList()
            } else {
                val old = dialers.values.toList()
                dialers.clear()
                fingerprint = newFingerprint
                old
            }
        }
        if (stale.isNotEmpty()) {
            logger.info("TURN settings changed: closing ${stale.size} live dialer(s), pools will be rebuilt")
            coreLog("[registry] settings changed, closing ${stale.size} dialer(s)")
            stale.forEach { it.close() }
        }

        synchronized(lock) { dialers[server.host] }?.let { return it }

        // Trimmed to what the budget can carry — applied to this new pool only.
        val peers = memory.effectivePeers(requestedPeers)
        val defaults = store.defaults(server.host)

        // Created under the lock: a Go dialer starts its VK login (and possibly a captcha)
        // the moment it exists, so two racing flows must never both build one.
        // Construction is cheap — `peer_addr` is an IP, no DNS happens under the lock.
        return synchronized(lock) {
            dialers[server.host] ?: try {
                factory.create(server, vkLink, defaults, peers, manualCaptcha).also {
                    dialers[server.host] = it
                    // After, not before: the core's first NewDialer resets Go memory to its
                    // own defaults (see TurnMemory.applyBudget).
                    memory.applyBudget()
                    coreLog("[registry] new dialer for ${server.host}: peers=$peers (requested $requestedPeers), manualCaptcha=$manualCaptcha")
                }
            } catch (e: Exception) {
                logger.debug("TURN dialer unavailable for $host: ${e.message}")
                null
            } catch (e: LinkageError) {
                logger.warning("TURN core not loadable: ${e.message}")
                null
            }
        }
    }

    /**
     * A TURN-backed tunnel to [host], or `null` to fall back to a direct dial — silently
     * for the flow, with an info log line (TURN is on because the direct path is
     * expected to be blocked, so a fallback deserves a trace).
     */
    suspend fun tunnel(host: String): ProxyConnection? {
        val dialer = dialer(host) ?: return null
        return try {
            dialer.waitReady()
            val stream = openStream(dialer, host)
            TurnProxyConnection(stream) { dialer.noteStreamClosed() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info("TURN tunnel to $host unavailable (${e.message}); falling back to a direct dial")
            null
        }
    }

    /**
     * Opens a stream off the caller's thread. Go's `Dial` is blocking (smux writes the SYN
     * frame and can wait on a congested session for up to 30 s), and the caller is an
     * lwIP connection whose dispatcher is the single lwIP executor — blocking it would
     * freeze every flow of the stack. A stream that opens while the caller is being
     * cancelled is closed here rather than leaked (withContext discards the result).
     */
    private suspend fun openStream(dialer: TurnDialerHandle, host: String): TurnStream {
        val opened = AtomicReference<TurnStream?>(null)
        try {
            return withContext(TurnDispatchers.blocking) {
                val stream = try {
                    dialer.openStream()
                } catch (e: TurnError) {
                    // A session may be mid-reconnect; Go round-robins, so one retry usually
                    // lands on a healthy one.
                    logger.debug("TURN stream to $host failed (${e.message}), retrying once")
                    dialer.openStream()
                }
                opened.set(stream)
                stream
            }
        } catch (e: CancellationException) {
            opened.getAndSet(null)?.let {
                it.close()
                dialer.noteStreamClosed()
            }
            throw e
        }
    }

    /** Live counts per relay host, sorted by host. */
    fun statistics(): List<TurnHostStatistics> =
        synchronized(lock) { dialers.values.toList() }
            .map {
                TurnHostStatistics(
                    host = it.host,
                    sessions = it.sessionCount,
                    streams = it.openStreamCount,
                    phase = it.phase,
                    credentialSets = it.credentialSets,
                    credentialSetsPassed = it.credentialSetsPassed,
                    captchaHits = it.captchaHits,
                    core = it.coreStatus,
                )
            }
            .sortedBy { it.host }

    /** Tears down every pool. Call on tunnel stop or configuration switch. */
    fun reset() {
        val all = synchronized(lock) {
            val list = dialers.values.toList()
            dialers.clear()
            fingerprint = ""
            list
        }
        if (all.isEmpty()) return
        logger.info("TURN reset: closing ${all.size} dialer(s) (tunnel stop or reconfiguration)")
        coreLog("[registry] reset, closing ${all.size} dialer(s)")
        all.forEach { it.close() }
    }

    companion object {
        val shared: TurnDialerRegistry by lazy {
            TurnDialerRegistry(
                settingsProvider = { TurnEnvironment.settings },
                storeProvider = { TurnEnvironment.metadataStore },
                factory = GoTurnDialer.factory,
                memory = TurnMemory(
                    configureGo = { limit, gc -> Anywhere.configureMemory(limit, gc.toLong()) },
                    log = { logger.info(it) },
                ),
                autoDecision = { TurnAutoState.decision },
                coreLog = { TurnCoreLogFile.append(it) },
            )
        }
    }
}
