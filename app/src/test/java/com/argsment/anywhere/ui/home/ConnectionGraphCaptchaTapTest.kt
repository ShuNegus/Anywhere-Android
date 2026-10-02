package com.argsment.anywhere.ui.home

import com.argsment.anywhere.viewmodel.ConnectionGraphNode
import com.argsment.anywhere.viewmodel.ConnectionStage
import com.argsment.anywhere.vpn.turn.TurnFetchState
import com.argsment.anywhere.vpn.turn.TurnPoolSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which graph taps reopen the captcha sheet instead of collapsing the graph. */
class ConnectionGraphCaptchaTapTest {

    private val captchaForPeers = TurnPoolSummary(
        usable = true, sessions = 20, target = 40,
        fetch = TurnFetchState.CAPTCHA_WAIT, captchas = 1, setsObtained = 1,
    )

    @Test
    fun captchaNodeOpensTheCaptchaWhileOneWaits() {
        assertTrue(opensCaptcha(ConnectionGraphNode.CAPTCHA, ConnectionStage.Captcha, null, captchaWaiting = true))
        assertFalse(opensCaptcha(ConnectionGraphNode.CAPTCHA, ConnectionStage.Captcha, null, captchaWaiting = false))
        assertFalse(opensCaptcha(ConnectionGraphNode.TURN_TUNNEL, ConnectionStage.Captcha, null, captchaWaiting = true))
    }

    @Test
    fun connectedNodeOpensItWhenMorePeersNeedACaptcha() {
        val stage = ConnectionStage.ConnectedViaTurn(captchaSolved = true)
        assertTrue(opensCaptcha(ConnectionGraphNode.CONNECTED, stage, captchaForPeers, captchaWaiting = true))
        assertFalse(opensCaptcha(ConnectionGraphNode.CONNECTED, stage, captchaForPeers.copy(fetch = TurnFetchState.IDLE), captchaWaiting = true))
        assertFalse(opensCaptcha(ConnectionGraphNode.CONNECTED, ConnectionStage.ConnectedDirect, captchaForPeers, captchaWaiting = true))
    }
}
