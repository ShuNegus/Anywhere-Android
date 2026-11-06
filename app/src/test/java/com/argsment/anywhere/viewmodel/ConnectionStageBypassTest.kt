package com.argsment.anywhere.viewmodel

import com.argsment.anywhere.R
import com.argsment.anywhere.turn.BypassReason
import com.argsment.anywhere.turn.TurnMode
import com.argsment.anywhere.vpn.turn.TurnAutoDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Connected without the backup route": the stage, its graph and its status. */
class ConnectionStageBypassTest {

    private fun resolve(issue: BypassReason?, status: VpnStatus = VpnStatus.CONNECTED) = ConnectionStage.resolve(
        status = status,
        isPreflighting = false,
        turnMode = TurnMode.AUTO,
        autoDecision = TurnAutoDecision.TURN,
        turnPhase = null,
        captchaPending = false,
        captchaSeen = false,
        failure = null,
        bypassIssue = issue,
    )

    @Test
    fun anIssueWhileConnectedIsConnectedWithoutBypass() {
        val stage = resolve(BypassReason.NO_VK_LINK)
        assertEquals(ConnectionStage.ConnectedWithoutBypass(BypassReason.NO_VK_LINK), stage)
        assertTrue(stage.isConnected)
        assertTrue(stage.isDegraded)
        assertEquals(R.string.connected_without_bypass, stage.statusTextRes)
    }

    @Test
    fun noIssueKeepsTheUsualStages() {
        assertEquals(ConnectionStage.TurnTunnel, resolve(null))
        assertEquals(ConnectionStage.Idle, resolve(BypassReason.NO_VK_LINK, status = VpnStatus.DISCONNECTED))
        assertEquals(ConnectionStage.VpnConnect, resolve(BypassReason.NO_VK_LINK, status = VpnStatus.CONNECTING))
    }

    @Test
    fun failureOutranksAnIssue() {
        val stage = ConnectionStage.resolve(
            VpnStatus.CONNECTED, false, TurnMode.AUTO, TurnAutoDecision.TURN, null, null, false, false,
            ConnectionFailure.NETWORK_LOST, BypassReason.NO_VK_LINK,
        )
        assertEquals(ConnectionStage.Failed(ConnectionFailure.NETWORK_LOST), stage)
    }

    @Test
    fun graphForAConfigurationIssue() {
        val stage = ConnectionStage.ConnectedWithoutBypass(BypassReason.LOAD_FAILED)
        assertEquals(ConnectionNodeState.DONE, stage.state(ConnectionGraphNode.VPN_CONNECT))
        assertEquals(ConnectionNodeState.FAILED, stage.state(ConnectionGraphNode.WHITELIST_CHECK))
        assertEquals(ConnectionNodeState.FAILED, stage.state(ConnectionGraphNode.TURN_TUNNEL))
        assertEquals(ConnectionNodeState.OFF, stage.state(ConnectionGraphNode.CAPTCHA))
        assertEquals(ConnectionNodeState.HOLLOW, stage.state(ConnectionGraphNode.CONNECTED))
        assertEquals(R.string.bypass_node_load_failed, stage.nodeCaption(ConnectionGraphNode.TURN_TUNNEL))
        assertEquals(R.string.graph_node_connected_direct, stage.nodeTitleOverride(ConnectionGraphNode.CONNECTED))
        assertEquals(R.string.bypass_node_sites_may_fail, stage.nodeCaption(ConnectionGraphNode.CONNECTED))
        // The trunk is green up to the restrictions check, nothing past it.
        assertTrue(stage.isEdgeTraversed(ConnectionGraphNode.VPN_CONNECT, ConnectionGraphNode.WHITELIST_CHECK))
        assertEquals(false, stage.isEdgeTraversed(ConnectionGraphNode.WHITELIST_CHECK, ConnectionGraphNode.CONNECTED))
        assertEquals(false, stage.isEdgeTraversed(ConnectionGraphNode.WHITELIST_CHECK, ConnectionGraphNode.TURN_TUNNEL))
    }

    @Test
    fun captchaFailureMarksTheCaptchaNode() {
        val stage = ConnectionStage.ConnectedWithoutBypass(BypassReason.CAPTCHA_FAILED)
        assertEquals(ConnectionNodeState.DONE, stage.state(ConnectionGraphNode.TURN_TUNNEL))
        assertEquals(ConnectionNodeState.FAILED, stage.state(ConnectionGraphNode.CAPTCHA))
        assertNull(stage.nodeCaption(ConnectionGraphNode.TURN_TUNNEL))
        assertEquals(R.string.bypass_node_captcha_failed, stage.nodeCaption(ConnectionGraphNode.CAPTCHA))
        assertTrue(stage.isEdgeTraversed(ConnectionGraphNode.WHITELIST_CHECK, ConnectionGraphNode.TURN_TUNNEL))
    }

    @Test
    fun serverNotCoveredIsHollowNotRed() {
        val stage = ConnectionStage.ConnectedWithoutBypass(BypassReason.SERVER_NOT_COVERED)
        assertEquals(ConnectionNodeState.HOLLOW, stage.state(ConnectionGraphNode.TURN_TUNNEL))
    }

    @Test
    fun otherStagesHaveNoCaptions() {
        assertNull(ConnectionStage.ConnectedDirect.nodeCaption(ConnectionGraphNode.CONNECTED))
        assertNull(ConnectionStage.ConnectedDirect.nodeTitleOverride(ConnectionGraphNode.CONNECTED))
    }
}
