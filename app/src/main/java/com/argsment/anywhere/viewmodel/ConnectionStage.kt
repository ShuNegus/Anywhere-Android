package com.argsment.anywhere.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import com.argsment.anywhere.R
import com.argsment.anywhere.turn.BypassReason
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.vpn.turn.TurnAutoDecision
import com.argsment.anywhere.vpn.turn.TurnPhase
import com.argsment.anywhere.vpn.turn.TurnPoolSummary

/*
 * Connection stages for the home-screen graph. Port of iOS `Anywhere/Models/ConnectionStage.swift`
 * (spec §11.2).
 *
 *   network check → VPN connect → restrictions check
 *     ├─ no bypass needed → connected                                  (direct trunk)
 *     └─ bypass           → TURN tunnel → captcha (if TURN asks) → connected (bypass branch)
 */

/** A graph node. [order] is the position along the route; all reached/current logic rests on it. */
enum class ConnectionGraphNode(val order: Int, @StringRes val titleRes: Int) {
    NETWORK_CHECK(1, R.string.graph_node_network_check),
    VPN_CONNECT(2, R.string.graph_node_vpn_connect),
    WHITELIST_CHECK(3, R.string.graph_node_whitelist_check),
    TURN_TUNNEL(4, R.string.graph_node_turn_tunnel),
    CAPTCHA(5, R.string.graph_node_captcha),
    CONNECTED(6, R.string.graph_node_connected);

    val isOnBypassBranch: Boolean get() = this == TURN_TUNNEL || this == CAPTCHA

    /**
     * The node's caption given the bypass pool. The tunnel already works while the core
     * is still adding peers (the next credential set is fetched only once the relay has
     * filled the current one, and may ask for a captcha) — that is background work, not
     * a stage, so it shows on "Connected" instead of rolling the graph back.
     */
    fun title(pool: TurnPoolSummary?): NodeTitle {
        if (this != CONNECTED || pool == null) return NodeTitle.Plain(this)
        if (pool.needsCaptchaForMorePeers) return NodeTitle.CaptchaForMorePeers
        if (pool.isAddingPeers) return NodeTitle.AddingPeers(pool.sessions, pool.target)
        return NodeTitle.Plain(this)
    }

    fun title(context: Context): String = context.getString(titleRes)
}

/** A node caption, resolved to text with [text]. */
sealed class NodeTitle {
    data class Plain(val node: ConnectionGraphNode) : NodeTitle()
    /** "Connected · Peers X/N". */
    data class AddingPeers(val sessions: Int, val target: Int) : NodeTitle()
    /** "Connected · Captcha for More Peers". */
    data object CaptchaForMorePeers : NodeTitle()

    fun text(context: Context): String = when (this) {
        is Plain -> context.getString(node.titleRes)
        is AddingPeers -> context.getString(R.string.graph_node_connected_adding_peers, sessions, target)
        CaptchaForMorePeers -> context.getString(R.string.graph_node_connected_captcha_for_peers)
    }
}

enum class ConnectionNodeState {
    OFF,
    DONE,
    CURRENT,
    /** The route went through the node, but the step was not needed (captcha). */
    SKIPPED,
    FAILED,
    /** Reached the wrong way: connected directly although the backup route was needed. */
    HOLLOW,
}

/**
 * Why the connection stalled. Deliberately without payloads (iOS: stable `Hashable`
 * comparison for animations).
 */
enum class ConnectionFailure(val node: ConnectionGraphNode, @StringRes val messageRes: Int) {
    /** The pre-flight found no network; the tunnel was not even started. */
    NO_NETWORK(ConnectionGraphNode.NETWORK_CHECK, R.string.vpn_error_no_internet),
    /** The VPN service did not bring the tunnel up. */
    VPN_START_FAILED(ConnectionGraphNode.VPN_CONNECT, R.string.graph_error_vpn_start),
    /** The tunnel came up, but the in-tunnel probe reached nothing. */
    NETWORK_LOST(ConnectionGraphNode.WHITELIST_CHECK, R.string.graph_error_network_lost),
    /** The TURN tunnel did not open in time. */
    TURN_TIMEOUT(ConnectionGraphNode.TURN_TUNNEL, R.string.graph_error_turn_timeout),
    /** The captcha was not solved in time. */
    CAPTCHA_TIMEOUT(ConnectionGraphNode.CAPTCHA, R.string.graph_error_captcha_timeout);

    fun message(context: Context): String = context.getString(messageRes)
}

/** The route traffic takes (or will take). */
enum class ConnectionRoute { DIRECT, TURN, UNDECIDED }

sealed class ConnectionStage {
    data object Idle : ConnectionStage()
    data object NetworkCheck : ConnectionStage()
    data object VpnConnect : ConnectionStage()
    data object WhitelistCheck : ConnectionStage()
    data object TurnTunnel : ConnectionStage()
    data object Captcha : ConnectionStage()
    /** Through the bypass; `captchaSolved == false` — TURN asked for no captcha, the node is skipped. */
    data class ConnectedViaTurn(val captchaSolved: Boolean) : ConnectionStage()
    /** No bypass needed. */
    data object ConnectedDirect : ConnectionStage()
    /**
     * The backup route was needed but could not be used ([reason]): the tunnel runs
     * directly, and blocked sites may not open.
     */
    data class ConnectedWithoutBypass(val reason: BypassReason) : ConnectionStage()
    data class Failed(val failure: ConnectionFailure) : ConnectionStage()

    val isConnected: Boolean
        get() = this is ConnectedViaTurn || this == ConnectedDirect || this is ConnectedWithoutBypass

    /** Connected, but the status must warn (red). */
    val isDegraded: Boolean get() = this is ConnectedWithoutBypass
    val isFailed: Boolean get() = this is Failed

    /** A transition is running — the power button shows progress. */
    val isBusy: Boolean get() = this != Idle && !isConnected && !isFailed

    val usesBypassBranch: Boolean
        get() = when (this) {
            TurnTunnel, Captcha, is ConnectedViaTurn -> true
            is Failed -> failure.node.isOnBypassBranch
            else -> false
        }

    /** The node we stand on; `null` when idle. */
    val currentNode: ConnectionGraphNode?
        get() = when (this) {
            Idle -> null
            NetworkCheck -> ConnectionGraphNode.NETWORK_CHECK
            VpnConnect -> ConnectionGraphNode.VPN_CONNECT
            WhitelistCheck -> ConnectionGraphNode.WHITELIST_CHECK
            TurnTunnel -> ConnectionGraphNode.TURN_TUNNEL
            Captcha -> ConnectionGraphNode.CAPTCHA
            is ConnectedViaTurn, ConnectedDirect, is ConnectedWithoutBypass -> ConnectionGraphNode.CONNECTED
            is Failed -> failure.node
        }

    /** Green: current or already passed. A direct connection jumps over the bypass branch. */
    fun isReached(node: ConnectionGraphNode): Boolean {
        if (this == ConnectedDirect) return !node.isOnBypassBranch
        if (this is ConnectedWithoutBypass) {
            // Up to the restrictions check; the tunnel step too when only the captcha failed.
            return node.order <= ConnectionGraphNode.WHITELIST_CHECK.order ||
                (node == ConnectionGraphNode.TURN_TUNNEL && reason == BypassReason.CAPTCHA_FAILED)
        }
        val current = currentNode ?: return false
        return node.order <= current.order
    }

    fun state(of: ConnectionGraphNode): ConnectionNodeState {
        if (this is Failed && of == failure.node) return ConnectionNodeState.FAILED
        if (this is ConnectedWithoutBypass) return withoutBypassState(of, reason)
        if (this is ConnectedViaTurn && of == ConnectionGraphNode.CAPTCHA && !captchaSolved) {
            return ConnectionNodeState.SKIPPED
        }
        if (currentNode == of) return ConnectionNodeState.CURRENT
        return if (isReached(of)) ConnectionNodeState.DONE else ConnectionNodeState.OFF
    }

    /**
     * An edge is green when both ends are — except the direct trunk "restrictions check →
     * connected", which greens only when no bypass was needed.
     */
    fun isEdgeTraversed(from: ConnectionGraphNode, to: ConnectionGraphNode): Boolean {
        if (from == ConnectionGraphNode.WHITELIST_CHECK && to == ConnectionGraphNode.CONNECTED) {
            return this == ConnectedDirect
        }
        return isReached(from) && isReached(to)
    }

    /** The text under the power button (upstream `connected`/`connecting`/`disconnected`). */
    @get:StringRes
    val statusTextRes: Int
        get() = when {
            this is ConnectedWithoutBypass -> R.string.connected_without_bypass
            isConnected -> R.string.connected
            isBusy -> R.string.connecting
            else -> R.string.disconnected
        }

    /**
     * The collapsed graph row's text (spec §11.4).
     *
     * @param serverName the selected server, named when it has no backup route.
     */
    fun collapsedText(context: Context, pool: TurnPoolSummary?, serverName: String? = null): String = when (this) {
        is ConnectedWithoutBypass ->
            if (reason == BypassReason.SERVER_NOT_COVERED && !serverName.isNullOrBlank()) {
                context.getString(R.string.bypass_reason_server_not_covered_named, serverName)
            } else {
                context.getString(reason.collapsedRes)
            }
        Idle -> context.getString(R.string.graph_collapsed_idle)
        is ConnectedViaTurn -> {
            val title = ConnectionGraphNode.CONNECTED.title(pool)
            if (title is NodeTitle.Plain) context.getString(R.string.graph_collapsed_via_turn) else title.text(context)
        }
        ConnectedDirect -> context.getString(R.string.graph_collapsed_direct)
        is Failed -> context.getString(R.string.graph_collapsed_failed, context.getString(failure.node.titleRes))
        else -> currentNode?.title(context).orEmpty()
    }

    /**
     * The graph label of [node] for this stage, or `null` for the node's usual title.
     * Only the "connected without the backup route" stage renames a node.
     */
    @StringRes
    fun nodeTitleOverride(node: ConnectionGraphNode): Int? =
        if (this is ConnectedWithoutBypass && node == ConnectionGraphNode.CONNECTED) R.string.graph_node_connected_direct else null

    /** The second, smaller line under [node]'s label, or `null`. */
    @StringRes
    fun nodeCaption(node: ConnectionGraphNode): Int? {
        if (this !is ConnectedWithoutBypass) return null
        return when (node) {
            ConnectionGraphNode.WHITELIST_CHECK -> R.string.bypass_node_needed
            ConnectionGraphNode.TURN_TUNNEL -> if (reason == BypassReason.CAPTCHA_FAILED) null else reason.nodeCaptionRes
            ConnectionGraphNode.CAPTCHA -> if (reason == BypassReason.CAPTCHA_FAILED) reason.nodeCaptionRes else null
            ConnectionGraphNode.CONNECTED -> R.string.bypass_node_sites_may_fail
            else -> null
        }
    }

    companion object {

        /** Node states for [ConnectedWithoutBypass]. */
        internal fun withoutBypassState(node: ConnectionGraphNode, reason: BypassReason): ConnectionNodeState = when (node) {
            ConnectionGraphNode.NETWORK_CHECK, ConnectionGraphNode.VPN_CONNECT -> ConnectionNodeState.DONE
            // The probe found the network restricted: the backup route was needed.
            ConnectionGraphNode.WHITELIST_CHECK -> ConnectionNodeState.FAILED
            ConnectionGraphNode.TURN_TUNNEL -> when (reason) {
                BypassReason.SERVER_NOT_COVERED -> ConnectionNodeState.HOLLOW
                BypassReason.CAPTCHA_FAILED -> ConnectionNodeState.DONE
                else -> ConnectionNodeState.FAILED
            }
            ConnectionGraphNode.CAPTCHA ->
                if (reason == BypassReason.CAPTCHA_FAILED) ConnectionNodeState.FAILED else ConnectionNodeState.OFF
            ConnectionGraphNode.CONNECTED -> ConnectionNodeState.HOLLOW
        }

        /** The route is the setting's; in auto, the probe's. `turnMode == null`: feature off. */
        fun route(turnMode: TurnMode?, autoDecision: TurnAutoDecision?): ConnectionRoute = when (turnMode) {
            null, TurnMode.OFF -> ConnectionRoute.DIRECT
            TurnMode.ON -> ConnectionRoute.TURN
            TurnMode.AUTO -> when (autoDecision) {
                TurnAutoDecision.TURN -> ConnectionRoute.TURN
                // Offline: the bypass would not help, flows go direct.
                TurnAutoDecision.DIRECT, TurnAutoDecision.OFFLINE -> ConnectionRoute.DIRECT
                TurnAutoDecision.UNDECIDED, null -> ConnectionRoute.UNDECIDED
            }
        }

        /**
         * The single place where app state becomes a graph stage.
         *
         * @param turnMode `null` when the feature is off; `ON` for a forced session.
         * @param autoDecision the probe's decision in auto mode.
         * @param turnPhase from the Go core; `null` while there is no pool.
         * @param turnUsable the pool already carries traffic; `null` = not reported, the phase decides.
         * @param captchaPending the captcha monitor sees a captcha waiting.
         * @param captchaSeen a captcha really came up this session.
         */
        fun resolve(
            status: VpnStatus,
            isPreflighting: Boolean,
            turnMode: TurnMode?,
            autoDecision: TurnAutoDecision?,
            turnPhase: TurnPhase?,
            turnUsable: Boolean? = null,
            captchaPending: Boolean,
            captchaSeen: Boolean,
            failure: ConnectionFailure?,
            bypassIssue: BypassReason? = null,
        ): ConnectionStage {
            // A failure outlives the status that caused it.
            if (failure != null) return Failed(failure)

            // The tunnel stands but nothing is reachable behind it — not "connected".
            if (turnMode == TurnMode.AUTO && autoDecision == TurnAutoDecision.OFFLINE && status == VpnStatus.CONNECTED) {
                return Failed(ConnectionFailure.NETWORK_LOST)
            }

            // The pre-flight runs before the tunnel starts; the status is still disconnected.
            if (isPreflighting) return NetworkCheck

            when (status) {
                VpnStatus.DISCONNECTED, VpnStatus.DISCONNECTING -> return Idle
                VpnStatus.CONNECTING, VpnStatus.REASSERTING -> return VpnConnect
                VpnStatus.CONNECTED -> Unit
            }

            // The backup route was needed and cannot be used: connected directly.
            if (bypassIssue != null) return ConnectedWithoutBypass(bypassIssue)

            return when (route(turnMode, autoDecision)) {
                ConnectionRoute.DIRECT -> ConnectedDirect
                ConnectionRoute.UNDECIDED -> WhitelistCheck
                ConnectionRoute.TURN -> when {
                    // The tunnel already carries traffic: a captcha for more peers does
                    // not undo the stage — it shows as the "Connected" caption.
                    turnUsable == true -> ConnectedViaTurn(captchaSeen)
                    captchaPending -> Captcha
                    turnPhase == TurnPhase.CAPTCHA_AUTO || turnPhase == TurnPhase.CAPTCHA_WAIT -> Captcha
                    turnPhase == TurnPhase.READY && turnUsable == null -> ConnectedViaTurn(captchaSeen)
                    else -> TurnTunnel
                }
            }
        }
    }
}
