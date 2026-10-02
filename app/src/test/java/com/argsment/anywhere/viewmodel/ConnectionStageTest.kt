package com.argsment.anywhere.viewmodel

import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.vpn.turn.TurnAutoDecision
import com.argsment.anywhere.vpn.turn.TurnCoreStatus
import com.argsment.anywhere.vpn.turn.TurnFetchState
import com.argsment.anywhere.vpn.turn.TurnPhase
import com.argsment.anywhere.vpn.turn.TurnPoolSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of iOS `ConnectionStageTests`: the graph is only as honest as this resolver, and its
 * signals come from three places (tunnel status, auto-mode probe, Go core phase).
 */
class ConnectionStageTest {

    private fun resolve(
        status: VpnStatus = VpnStatus.CONNECTED,
        isPreflighting: Boolean = false,
        turnMode: TurnMode? = TurnMode.AUTO,
        autoDecision: TurnAutoDecision? = null,
        turnPhase: TurnPhase? = null,
        turnUsable: Boolean? = null,
        captchaPending: Boolean = false,
        captchaSeen: Boolean = false,
        failure: ConnectionFailure? = null,
    ) = ConnectionStage.resolve(status, isPreflighting, turnMode, autoDecision, turnPhase, turnUsable, captchaPending, captchaSeen, failure)

    // MARK: - Trunk

    @Test
    fun idleWhenTheTunnelIsDown() {
        assertEquals(ConnectionStage.Idle, resolve(status = VpnStatus.DISCONNECTED))
        assertEquals(ConnectionStage.Idle, resolve(status = VpnStatus.DISCONNECTING))
    }

    @Test
    fun preflightIsTheNetworkCheck() {
        assertEquals(ConnectionStage.NetworkCheck, resolve(status = VpnStatus.DISCONNECTED, isPreflighting = true))
    }

    @Test
    fun connectingIsTheVpnNode() {
        assertEquals(ConnectionStage.VpnConnect, resolve(status = VpnStatus.CONNECTING))
        assertEquals(ConnectionStage.VpnConnect, resolve(status = VpnStatus.REASSERTING))
    }

    // MARK: - Auto mode

    @Test
    fun undecidedProbeSitsOnTheWhitelistCheck() {
        assertEquals(ConnectionStage.WhitelistCheck, resolve(autoDecision = TurnAutoDecision.UNDECIDED))
        assertEquals(ConnectionStage.WhitelistCheck, resolve(autoDecision = null))
    }

    @Test
    fun directVerdictSkipsTheBranch() {
        assertEquals(ConnectionStage.ConnectedDirect, resolve(autoDecision = TurnAutoDecision.DIRECT))
    }

    @Test
    fun turnVerdictWalksTheBranch() {
        val turn = TurnAutoDecision.TURN
        assertEquals(ConnectionStage.TurnTunnel, resolve(autoDecision = turn, turnPhase = null))
        assertEquals(ConnectionStage.TurnTunnel, resolve(autoDecision = turn, turnPhase = TurnPhase.VK_ACCESS))
        assertEquals(ConnectionStage.TurnTunnel, resolve(autoDecision = turn, turnPhase = TurnPhase.TUNNEL_SETUP))
        assertEquals(ConnectionStage.Captcha, resolve(autoDecision = turn, turnPhase = TurnPhase.CAPTCHA_WAIT))
        assertEquals(ConnectionStage.Captcha, resolve(autoDecision = turn, turnPhase = TurnPhase.CAPTCHA_AUTO))
    }

    @Test
    fun aPendingCaptchaOutranksThePhase() {
        assertEquals(ConnectionStage.Captcha,
            resolve(autoDecision = TurnAutoDecision.TURN, turnPhase = TurnPhase.TUNNEL_SETUP, captchaPending = true))
    }

    @Test
    fun readyIsConnectedViaTurn() {
        assertEquals(ConnectionStage.ConnectedViaTurn(true),
            resolve(autoDecision = TurnAutoDecision.TURN, turnPhase = TurnPhase.READY, captchaSeen = true))
        assertEquals(ConnectionStage.ConnectedViaTurn(false),
            resolve(autoDecision = TurnAutoDecision.TURN, turnPhase = TurnPhase.READY, captchaSeen = false))
    }

    @Test
    fun anUnusedCaptchaNodeIsSkipped() {
        val stage = ConnectionStage.ConnectedViaTurn(captchaSolved = false)
        assertEquals(ConnectionNodeState.SKIPPED, stage.state(ConnectionGraphNode.CAPTCHA))
        assertEquals(ConnectionNodeState.DONE, stage.state(ConnectionGraphNode.TURN_TUNNEL))
        assertEquals(ConnectionNodeState.DONE, ConnectionStage.ConnectedViaTurn(true).state(ConnectionGraphNode.CAPTCHA))
    }

    // MARK: - Pinned modes

    @Test
    fun pinnedModesIgnoreTheProbe() {
        assertEquals(ConnectionStage.TurnTunnel, resolve(turnMode = TurnMode.ON, autoDecision = null, turnPhase = null))
        assertEquals(ConnectionStage.ConnectedDirect, resolve(turnMode = TurnMode.OFF, autoDecision = null))
        // The feature switched off entirely.
        assertEquals(ConnectionStage.ConnectedDirect, resolve(turnMode = null, autoDecision = null))
    }

    // MARK: - Failures

    @Test
    fun offlineWhileConnectedIsAFailure() {
        assertEquals(ConnectionStage.Failed(ConnectionFailure.NETWORK_LOST), resolve(autoDecision = TurnAutoDecision.OFFLINE))
        assertEquals(ConnectionStage.VpnConnect, resolve(status = VpnStatus.CONNECTING, autoDecision = TurnAutoDecision.OFFLINE))
    }

    @Test
    fun aRecordedFailureOutranksTheStatus() {
        assertEquals(ConnectionStage.Failed(ConnectionFailure.TURN_TIMEOUT),
            resolve(autoDecision = TurnAutoDecision.DIRECT, failure = ConnectionFailure.TURN_TIMEOUT))
        assertEquals(ConnectionStage.Failed(ConnectionFailure.VPN_START_FAILED),
            resolve(status = VpnStatus.DISCONNECTED, failure = ConnectionFailure.VPN_START_FAILED))
    }

    @Test
    fun theFailedNodeIsTheOneThatStalled() {
        val stage = ConnectionStage.Failed(ConnectionFailure.TURN_TIMEOUT)
        assertEquals(ConnectionGraphNode.TURN_TUNNEL, stage.currentNode)
        assertEquals(ConnectionNodeState.FAILED, stage.state(ConnectionGraphNode.TURN_TUNNEL))
        assertEquals(ConnectionNodeState.DONE, stage.state(ConnectionGraphNode.VPN_CONNECT))
        assertEquals(ConnectionNodeState.OFF, stage.state(ConnectionGraphNode.CONNECTED))
        assertTrue(!stage.isBusy && !stage.isConnected && stage.isFailed)
        assertTrue(stage.usesBypassBranch)
        // Every failure maps to its node and has a message.
        assertEquals(
            listOf(ConnectionGraphNode.NETWORK_CHECK, ConnectionGraphNode.VPN_CONNECT, ConnectionGraphNode.WHITELIST_CHECK,
                ConnectionGraphNode.TURN_TUNNEL, ConnectionGraphNode.CAPTCHA),
            ConnectionFailure.entries.map { it.node },
        )
        ConnectionFailure.entries.forEach { assertNotEquals(0, it.messageRes) }
    }

    // MARK: - A pool that already works

    @Test
    fun aUsablePoolStaysConnectedThroughACaptcha() {
        assertEquals(ConnectionStage.ConnectedViaTurn(true),
            resolve(autoDecision = TurnAutoDecision.TURN, turnPhase = TurnPhase.CAPTCHA_WAIT, turnUsable = true,
                captchaPending = true, captchaSeen = true))
        assertEquals(ConnectionStage.ConnectedViaTurn(false),
            resolve(autoDecision = TurnAutoDecision.TURN, turnPhase = TurnPhase.TUNNEL_SETUP, turnUsable = true))
    }

    @Test
    fun anUnusablePoolWalksTheBranch() {
        assertEquals(ConnectionStage.Captcha,
            resolve(autoDecision = TurnAutoDecision.TURN, turnPhase = TurnPhase.CAPTCHA_WAIT, turnUsable = false))
        assertEquals(ConnectionStage.Captcha,
            resolve(autoDecision = TurnAutoDecision.TURN, turnPhase = TurnPhase.VK_ACCESS, turnUsable = false, captchaPending = true))
        assertEquals(ConnectionStage.TurnTunnel,
            resolve(autoDecision = TurnAutoDecision.TURN, turnPhase = TurnPhase.READY, turnUsable = false))
    }

    @Test
    fun connectedTitleTellsAboutPeersBeingAdded() {
        fun pool(sessions: Int, target: Int, fetch: TurnFetchState, usable: Boolean = true) =
            TurnPoolSummary(usable, sessions, target, fetch, captchas = 1, setsObtained = 1)
        val plain = NodeTitle.Plain(ConnectionGraphNode.CONNECTED)
        val connected = ConnectionGraphNode.CONNECTED
        assertEquals(plain, connected.title(null))
        assertEquals(plain, connected.title(pool(40, 40, TurnFetchState.IDLE)))
        // Sessions reconnecting with nothing to fetch is not "adding peers".
        assertEquals(plain, connected.title(pool(38, 40, TurnFetchState.IDLE)))
        assertEquals(NodeTitle.AddingPeers(20, 40), connected.title(pool(20, 40, TurnFetchState.FETCHING)))
        assertEquals(NodeTitle.CaptchaForMorePeers, connected.title(pool(20, 40, TurnFetchState.CAPTCHA_WAIT)))
        // An unusable pool is a stage, not a caption.
        assertEquals(plain, connected.title(pool(0, 40, TurnFetchState.CAPTCHA_WAIT, usable = false)))
        // Only "Connected" gets a caption.
        for (node in ConnectionGraphNode.entries.filter { it != ConnectionGraphNode.CONNECTED }) {
            assertEquals(NodeTitle.Plain(node), node.title(pool(20, 40, TurnFetchState.CAPTCHA_WAIT)))
        }
    }

    @Test
    fun poolSummaryCombinesRelays() {
        fun status(usable: Boolean, sessions: Int, fetch: String, captchas: Int) = TurnCoreStatus.decode(
            """{"version":1,"usable":$usable,"sessions":$sessions,"target":20,"phase":4,
               "fetch":{"state":"$fetch","set_id":-1,"waiting":0},
               "sets_obtained":1,"sets_retired":0,"captchas":$captchas,"sets":[]}"""
        )!!
        assertEquals(null, TurnPoolSummary.combining(emptyList()))
        val pool = TurnPoolSummary.combining(listOf(
            status(false, 0, "fetching", 0),
            status(true, 12, "captcha_auto", 1),
        ))!!
        assertTrue(pool.usable && pool.sessions == 12 && pool.target == 40)
        assertEquals(TurnFetchState.CAPTCHA_AUTO, pool.fetch)
        assertTrue(pool.captchas == 1 && pool.setsObtained == 2)
    }

    // MARK: - Edges

    @Test
    fun trunkAndBranchEdges() {
        val w = ConnectionGraphNode.WHITELIST_CHECK
        val c = ConnectionGraphNode.CONNECTED
        assertTrue(ConnectionStage.ConnectedDirect.isEdgeTraversed(w, c))
        assertFalse(ConnectionStage.ConnectedViaTurn(true).isEdgeTraversed(w, c))
        assertFalse(ConnectionStage.TurnTunnel.isEdgeTraversed(w, c))

        val viaTurn = ConnectionStage.ConnectedViaTurn(false)
        assertTrue(viaTurn.isEdgeTraversed(w, ConnectionGraphNode.TURN_TUNNEL))
        assertTrue(viaTurn.isEdgeTraversed(ConnectionGraphNode.TURN_TUNNEL, ConnectionGraphNode.CAPTCHA))
        assertTrue(viaTurn.isEdgeTraversed(ConnectionGraphNode.CAPTCHA, c))

        assertFalse(ConnectionStage.ConnectedDirect.isEdgeTraversed(w, ConnectionGraphNode.TURN_TUNNEL))
        assertEquals(ConnectionNodeState.OFF, ConnectionStage.ConnectedDirect.state(ConnectionGraphNode.CAPTCHA))
    }

    @Test
    fun routeTable() {
        assertEquals(ConnectionRoute.DIRECT, ConnectionStage.route(null, TurnAutoDecision.TURN))
        assertEquals(ConnectionRoute.DIRECT, ConnectionStage.route(TurnMode.OFF, TurnAutoDecision.TURN))
        assertEquals(ConnectionRoute.TURN, ConnectionStage.route(TurnMode.ON, null))
        assertEquals(ConnectionRoute.TURN, ConnectionStage.route(TurnMode.AUTO, TurnAutoDecision.TURN))
        assertEquals(ConnectionRoute.DIRECT, ConnectionStage.route(TurnMode.AUTO, TurnAutoDecision.OFFLINE))
        assertEquals(ConnectionRoute.UNDECIDED, ConnectionStage.route(TurnMode.AUTO, null))
    }

    @Test
    fun busyAndStatusText() {
        assertFalse(ConnectionStage.Idle.isBusy)
        assertTrue(ConnectionStage.WhitelistCheck.isBusy)
        assertTrue(ConnectionStage.NetworkCheck.isBusy)
        assertEquals(com.argsment.anywhere.R.string.connected, ConnectionStage.ConnectedDirect.statusTextRes)
        assertEquals(com.argsment.anywhere.R.string.connecting, ConnectionStage.Captcha.statusTextRes)
        assertEquals(com.argsment.anywhere.R.string.disconnected, ConnectionStage.Failed(ConnectionFailure.NO_NETWORK).statusTextRes)
    }
}
