package com.argsment.anywhere.ui.home

import com.argsment.anywhere.turn.BypassReason
import com.argsment.anywhere.viewmodel.ConnectionFailure
import com.argsment.anywhere.viewmodel.ConnectionGraphNode
import com.argsment.anywhere.viewmodel.ConnectionNodeState
import com.argsment.anywhere.viewmodel.ConnectionStage
import org.junit.Assert.assertEquals
import org.junit.Test

/** The mock-up plaque's dot and the nodes the expanded card lists. */
class ConnectionGraphLayoutTest {

    @Test
    fun collapsedDotFollowsTheWholeConnection() {
        assertEquals(ConnectionNodeState.OFF, collapsedDotState(ConnectionStage.Idle))
        assertEquals(ConnectionNodeState.CURRENT, collapsedDotState(ConnectionStage.VpnConnect))
        assertEquals(ConnectionNodeState.CURRENT, collapsedDotState(ConnectionStage.TurnTunnel))
        assertEquals(ConnectionNodeState.DONE, collapsedDotState(ConnectionStage.ConnectedDirect))
        assertEquals(ConnectionNodeState.DONE, collapsedDotState(ConnectionStage.ConnectedViaTurn(captchaSolved = false)))
        assertEquals(ConnectionNodeState.FAILED, collapsedDotState(ConnectionStage.Failed(ConnectionFailure.NO_NETWORK)))
        assertEquals(
            ConnectionNodeState.FAILED,
            collapsedDotState(ConnectionStage.ConnectedWithoutBypass(BypassReason.NO_VK_LINK)),
        )
        // "This server has no backup route" is not an error: hollow, not red.
        assertEquals(
            ConnectionNodeState.HOLLOW,
            collapsedDotState(ConnectionStage.ConnectedWithoutBypass(BypassReason.SERVER_NOT_COVERED)),
        )
    }

    @Test
    fun directConnectionListsTheTrunkOnly() {
        val nodes = visibleNodes(ConnectionStage.ConnectedDirect)
        assertEquals(
            listOf(
                ConnectionGraphNode.NETWORK_CHECK,
                ConnectionGraphNode.VPN_CONNECT,
                ConnectionGraphNode.WHITELIST_CHECK,
                ConnectionGraphNode.CONNECTED,
            ),
            nodes,
        )
        assertEquals(nodes, visibleNodes(ConnectionStage.Idle))
    }

    @Test
    fun backupRouteNodesAppearOnceInPlay() {
        assert(ConnectionGraphNode.TURN_TUNNEL in visibleNodes(ConnectionStage.TurnTunnel))
        assert(ConnectionGraphNode.CAPTCHA in visibleNodes(ConnectionStage.Captcha))
        // Connected through TURN without a captcha: the skipped captcha step stays hidden.
        val viaTurn = visibleNodes(ConnectionStage.ConnectedViaTurn(captchaSolved = false))
        assert(ConnectionGraphNode.TURN_TUNNEL in viaTurn)
        assert(ConnectionGraphNode.CAPTCHA !in viaTurn)
        // The failed captcha shows both the tunnel and the captcha step.
        val captchaFailed = visibleNodes(ConnectionStage.ConnectedWithoutBypass(BypassReason.CAPTCHA_FAILED))
        assert(ConnectionGraphNode.TURN_TUNNEL in captchaFailed && ConnectionGraphNode.CAPTCHA in captchaFailed)
        // Not for this server: the backup-route step is shown hollow, with its caption.
        val notCovered = visibleNodes(ConnectionStage.ConnectedWithoutBypass(BypassReason.SERVER_NOT_COVERED))
        assert(ConnectionGraphNode.TURN_TUNNEL in notCovered && ConnectionGraphNode.CAPTCHA !in notCovered)
    }
}
