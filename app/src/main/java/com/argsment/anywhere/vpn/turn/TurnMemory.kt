package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.turn.TurnLimits
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The numbers behind the Go runtime's memory budget and the session cap. A pure port of
 * iOS `TurnMemoryPolicy` (`TurnMemory.swift`), kept free of global state for tests.
 */
object TurnMemoryPolicy {
    const val MIB: Long = 1L shl 20

    /** Fraction of the remaining budget the Go runtime may use as its soft limit. */
    const val GO_HEAP_SHARE = 0.6
    const val MINIMUM_GO_LIMIT: Long = 16 * MIB
    /** iOS ceiling: a packet-tunnel extension has ~50 MB for the whole process. */
    const val MAXIMUM_GO_LIMIT: Long = 28 * MIB
    /**
     * GC target handed to Go. Must be set explicitly: the core's own default (GOGC 20,
     * 24 MiB) made the collector run several times a second under load and stalled the
     * KCP/smux goroutines.
     */
    const val GO_GC_PERCENT = 50

    /** Below this much headroom, new pools run fewer sessions… */
    const val LOW_MEMORY_THRESHOLD: Long = 20 * MIB
    /** …and the cap lifts only once headroom recovers past this (hysteresis). */
    const val CAP_RELEASE_THRESHOLD: Long = 28 * MIB
    const val LOW_MEMORY_PEER_CAP = 4

    /**
     * Android has no per-process jetsam budget like an iOS Network Extension (the VPN
     * service shares the app's heap limits, and Go memory is native), so the Go soft
     * limit is a fixed, more generous value instead of `goLimit(available)`.
     */
    const val ANDROID_GO_LIMIT: Long = 64 * MIB

    /** Soft heap limit for Go, given the bytes the process may still allocate. */
    fun goLimit(available: Long): Long =
        minOf(MAXIMUM_GO_LIMIT, maxOf(MINIMUM_GO_LIMIT, (available * GO_HEAP_SHARE).toLong()))

    /**
     * Sessions a *newly created* pool should run, plus the cap state to carry forward.
     *
     * @param available remaining process budget, or 0 when unknown (always on Android).
     */
    fun peers(requested: Int, available: Long, capActive: Boolean): Pair<Int, Boolean> {
        val clamped = TurnLimits.clampPeers(requested)
        if (available <= 0) return clamped to capActive
        val engaged = if (capActive) available <= CAP_RELEASE_THRESHOLD else available < LOW_MEMORY_THRESHOLD
        return (if (engaged) minOf(clamped, LOW_MEMORY_PEER_CAP) else clamped) to engaged
    }
}

/**
 * Applies [TurnMemoryPolicy] to the live process. Mirrors iOS `TurnMemory`.
 *
 * The cap only ever applies to a pool being created; it deliberately does not feed the
 * registry fingerprint (see [TurnPoolFingerprint]).
 *
 * @param availableBytes remaining process budget; Android has no equivalent of
 *   `os_proc_available_memory`, so production passes 0 ("unknown") and the cap never
 *   engages. Injectable for tests.
 * @param configureGo `Anywhere.configureMemory(limit, gcPercent)`.
 */
class TurnMemory(
    private val availableBytes: () -> Long = { 0L },
    private val configureGo: (Long, Int) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val capEngaged = AtomicBoolean(false)

    /**
     * Hands the Go runtime its soft limit and GC percent.
     *
     * Must run *after* `newDialer`, not only before it: the core's first `NewDialer` call
     * applies its own defaults (24 MiB / GOGC 20) once via `sync.Once`, which silently
     * overrode a budget set beforehand (the iOS order — so on iOS the first pool of a
     * process actually ran on the core defaults).
     */
    fun applyBudget() {
        val available = availableBytes()
        val limit = if (available > 0) TurnMemoryPolicy.goLimit(available) else TurnMemoryPolicy.ANDROID_GO_LIMIT
        configureGo(limit, TurnMemoryPolicy.GO_GC_PERCENT)
    }

    /** Session count for a new pool, given the user's request and current headroom. */
    fun effectivePeers(requested: Int): Int {
        val available = availableBytes()
        val wasEngaged = capEngaged.get()
        val (peers, engaged) = TurnMemoryPolicy.peers(requested, available, wasEngaged)
        capEngaged.set(engaged)
        if (engaged) {
            log("TURN low memory (${available / TurnMemoryPolicy.MIB} MiB): capping peers $requested → $peers for the new pool (live pools untouched)")
        } else if (wasEngaged) {
            log("TURN memory recovered (${available / TurnMemoryPolicy.MIB} MiB): peer cap lifted, new pools run $peers")
        }
        return peers
    }
}
