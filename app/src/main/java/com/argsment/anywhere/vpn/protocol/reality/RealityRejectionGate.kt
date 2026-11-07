package com.argsment.anywhere.vpn.protocol.reality

import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.data.model.RealityConfiguration
import com.argsment.anywhere.vpn.protocol.vless.RealityError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Circuit breaker for REALITY servers that reject our ClientHello.
 *
 * A REALITY server that does not accept the client (Xray >= 26.9.9 without an
 * X25519MLKEM768 key share, a rotated key, a wrong shortId...) sends no error: it
 * splices the connection to the donor site, and we only notice when the donor's
 * genuine certificate fails our check ([RealityError.AuthenticationFailed]). Every app
 * flow would otherwise open its own doomed connection — through TURN that piles up
 * hundreds of stuck streams on one node. After a rejection the server is blocked for
 * 2, 4, 8, 16, 32, then 60 s; a successful handshake resets it.
 *
 * Latency probes bypass the gate (run them with [Bypass] in the coroutine context) so
 * the user can always re-test a server by hand; their outcome still updates the state.
 */
class RealityRejectionGate(
    /** Monotonic milliseconds. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    /**
     * Server identity: endpoint (`host:port` as configured, never a resolved IP, so the
     * direct and the over-tunnel path agree) plus the REALITY parameters.
     */
    data class Key(
        val endpoint: String,
        val serverName: String,
        val publicKey: String,
        val shortId: String
    ) {
        constructor(endpoint: String, reality: RealityConfiguration) : this(
            endpoint = endpoint,
            serverName = reality.serverName,
            publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(reality.publicKey),
            shortId = reality.shortId.joinToString("") { "%02x".format(it) }
        )
    }

    /** Marks a coroutine (and its children) as a latency probe: never refused. */
    object Bypass : AbstractCoroutineContextElement(BypassKey)
    private object BypassKey : CoroutineContext.Key<Bypass>

    private class Entry(var failures: Int, var blockedUntilMs: Long)

    private val entries = HashMap<Key, Entry>()

    /** Milliseconds left on the block for [key], or null when connecting is allowed. */
    fun remainingBlockMs(key: Key): Long? {
        val now = nowMs()
        synchronized(entries) {
            val entry = entries[key] ?: return null
            return if (entry.blockedUntilMs > now) entry.blockedUntilMs - now else null
        }
    }

    /** The error to fail with instead of dialing, or null to go ahead. */
    fun refusal(key: Key, context: CoroutineContext? = null): RealityError? {
        if (context?.get(BypassKey) != null) return null
        return if (remainingBlockMs(key) != null) RealityError.RejectedRecently() else null
    }

    /**
     * Records a rejection. Rejections that land while the key is already blocked come
     * from attempts that started before the block and do not lengthen it — otherwise a
     * burst of parallel flows would jump straight to the 60 s cap.
     * @return true when this opened a new block window.
     */
    fun recordRejection(key: Key): Boolean {
        val now = nowMs()
        synchronized(entries) {
            val entry = entries.getOrPut(key) { Entry(0, 0) }
            if (entry.blockedUntilMs > now) return false
            entry.failures += 1
            entry.blockedUntilMs = now + blockDurationMs(entry.failures)
            return true
        }
    }

    /**
     * Records a successful handshake and lifts any block.
     * @return true when the key had been rejected before.
     */
    fun recordSuccess(key: Key): Boolean = synchronized(entries) { entries.remove(key) != null }

    fun reset() = synchronized(entries) { entries.clear() }

    companion object {
        val shared = RealityRejectionGate()

        const val MAX_BLOCK_MS = 60_000L

        /** 2 · 2^(n-1) seconds, capped at 60: 2, 4, 8, 16, 32, 60, 60... */
        fun blockDurationMs(failures: Int): Long {
            if (failures <= 0) return 0
            val exponent = minOf(failures - 1, 6)
            return minOf(MAX_BLOCK_MS, 2_000L * (1L shl exponent))
        }
    }
}

/** Gate key for this configuration's own REALITY layer, null when it has none. */
val ProxyConfiguration.realityRejectionKey: RealityRejectionGate.Key?
    get() {
        val reality = reality ?: return null
        return RealityRejectionGate.Key("$serverAddress:$serverPort", reality)
    }

/** Keys of this configuration and every hop of its chain. */
val ProxyConfiguration.realityRejectionKeys: List<RealityRejectionGate.Key>
    get() = (listOf(this) + (chain ?: emptyList())).mapNotNull { it.realityRejectionKey }

/** The server rejected us, now or moments ago. */
val Throwable.isRealityRejection: Boolean
    get() = this is RealityError.AuthenticationFailed || this is RealityError.RejectedRecently

/**
 * Last rejection, for the home screen hint ("update the app") — the service and the UI
 * share the process, so a state flow is enough.
 */
object RealityRejectionNotice {
    /** How long a recorded rejection stays relevant for the hint (a 60 s block plus slack). */
    const val FRESHNESS_MS = 90_000L

    data class Record(val key: RealityRejectionGate.Key, val atMs: Long)

    private val _last = MutableStateFlow<Record?>(null)
    val last: StateFlow<Record?> = _last.asStateFlow()

    fun noteRejected(key: RealityRejectionGate.Key, nowMs: Long = System.currentTimeMillis()) {
        _last.value = Record(key, nowMs)
    }

    fun noteAccepted(key: RealityRejectionGate.Key) {
        if (_last.value?.key == key) _last.value = null
    }

    fun clear() {
        _last.value = null
    }

    /** Whether one of [keys] was rejected within [FRESHNESS_MS] of [nowMs]. */
    fun isRecentlyRejected(
        keys: List<RealityRejectionGate.Key>,
        record: Record? = _last.value,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        record ?: return false
        val age = nowMs - record.atMs
        if (age < 0 || age > FRESHNESS_MS) return false
        return keys.contains(record.key)
    }
}
