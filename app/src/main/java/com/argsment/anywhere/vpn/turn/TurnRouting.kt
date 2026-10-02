package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.data.model.OutboundProtocol
import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.turn.TurnEnvironment
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.vpn.protocol.ProxyConnection
import com.argsment.anywhere.vpn.util.AnywhereLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val logger = AnywhereLogger("TurnRouting")

/**
 * The decision "does this outbound connection ride TURN" and the tunnel lifecycle hooks
 * the VPN service calls. Mirrors iOS `TCPConnection.turnTunnelIfEligible()` and the TURN
 * parts of `TunnelStack+Lifecycle`.
 */
object TurnRouting {

    /** Byte-stream protocols that can be stacked on a TURN stream (iOS: same set). */
    private val ELIGIBLE_PROTOCOLS = setOf(
        OutboundProtocol.VLESS,
        OutboundProtocol.TROJAN,
        OutboundProtocol.SHADOWSOCKS,
    )

    /**
     * Whether the VLESS mux (used only for UDP flows on Android: Vision + Mux/XUDP) also
     * rides TURN. Off for parity with iOS, where UDP never uses TURN (spec §5.1/§14 #15).
     * The plumbing in `MuxClient` is in place; flipping this is the whole change.
     */
    const val ROUTE_MUX_OVER_TURN = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var warmUpJob: Job? = null

    /**
     * A TURN tunnel for a TCP flow whose (first) outbound hop is [hop], or `null` for a
     * plain dial. For a chain, pass the entry hop: TURN then carries the whole chain
     * (iOS skips chained configurations altogether; on Android the chain's first hop
     * takes the TURN stream as its `tunnel`).
     */
    suspend fun tunnelFor(hop: ProxyConfiguration): ProxyConnection? {
        val settings = TurnEnvironment.settings ?: return null
        if (!settings.turnActiveForSession) return null
        // In auto mode the first flows of a session can outrun the probe; waiting here
        // keeps them from dialing direct out of a censored network.
        if (settings.effectiveTurnMode == TurnMode.AUTO) TurnAutoState.waitForDecision(settings)
        val registry = TurnDialerRegistry.shared
        if (!registry.isActive()) return null
        if (hop.outboundProtocol !in ELIGIBLE_PROTOCOLS) return null
        val tunnel = registry.tunnel(hop.serverAddress)
        if (tunnel == null) {
            logger.debug("[TURN] no relay for ${hop.serverAddress}, dialing directly")
        } else {
            logger.debug("[TURN] routing ${hop.name} through relay ${hop.serverAddress}")
        }
        return tunnel
    }

    /** Same as [tunnelFor] for the mux connection, gated by [ROUTE_MUX_OVER_TURN]. */
    suspend fun tunnelForMux(configuration: ProxyConfiguration): ProxyConnection? =
        if (ROUTE_MUX_OVER_TURN) tunnelFor(configuration) else null

    /** The host whose relay a configuration's TCP flows use. */
    fun relayHop(configuration: ProxyConfiguration): ProxyConfiguration =
        configuration.chain?.firstOrNull() ?: configuration

    /**
     * Starts the pool for the selected server as soon as the tunnel is up, so the VK
     * login/captcha run before the first flow and the UI has something to show. Uses
     * the same key as the on-demand path, so it is the same pool (spec §5.4).
     */
    fun warmUp(configuration: ProxyConfiguration) {
        warmUpJob?.cancel()
        warmUpJob = scope.launch {
            val settings = TurnEnvironment.settings ?: return@launch
            if (!settings.turnActiveForSession) return@launch
            if (settings.effectiveTurnMode == TurnMode.AUTO) TurnAutoState.waitForDecision(settings)
            val hop = relayHop(configuration)
            if (hop.outboundProtocol !in ELIGIBLE_PROTOCOLS) return@launch
            TurnDialerRegistry.shared.dialer(hop.serverAddress)
        }
    }

    /** Tunnel stop / configuration switch: no new session may inherit a stale pool. */
    fun reset() {
        warmUpJob?.cancel()
        warmUpJob = null
        TurnDialerRegistry.shared.reset()
    }
}
