package com.argsment.anywhere.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backup-route icon state and the reason shown when it cannot be used. All values invented. */
class BypassStatusTest {

    private val relay = TurnServerInfo(host = "edge.example.invalid", supported = true, peerAddr = "203.0.113.1:56000", wrapKeyHex = "00")
    private fun metadata(vkLink: String? = "https://example.invalid/call/join/AAAA", required: Boolean? = true) =
        TurnMetadata(vkLink = vkLink, vkLinkRequired = required, servers = mapOf(relay.host to relay))

    private fun state(metadata: TurnMetadata? = null, outcome: TurnFetchOutcome? = null, known: Boolean = true, manual: String = "") =
        BypassStatus.subscriptionState(metadata, outcome, known, manual)

    @Test
    fun relaysMeanAvailableWhateverTheLastRequestDid() {
        assertEquals(SubscriptionBypassState.Available, state(metadata(), TurnFetchOutcome.OK))
        // A failed refresh keeps the stored relays: still usable.
        assertEquals(SubscriptionBypassState.Available, state(metadata(), TurnFetchOutcome.FAILED))
    }

    @Test
    fun missingRequiredLinkIsAnErrorUnlessTheUserHasOne() {
        assertEquals(SubscriptionBypassState.Error(BypassReason.NO_VK_LINK), state(metadata(vkLink = null), TurnFetchOutcome.OK))
        assertEquals(SubscriptionBypassState.Error(BypassReason.NO_VK_LINK), state(metadata(vkLink = "  "), TurnFetchOutcome.OK))
        assertEquals(SubscriptionBypassState.Available, state(metadata(vkLink = null), TurnFetchOutcome.OK, manual = "https://x.invalid/j"))
        assertEquals(SubscriptionBypassState.Available, state(metadata(vkLink = null, required = false), TurnFetchOutcome.OK))
    }

    @Test
    fun okWithoutTurnBlockIsNone() {
        assertEquals(SubscriptionBypassState.None, state(null, TurnFetchOutcome.OK))
    }

    @Test
    fun failuresAreReportedOnlyForOurSubscriptions() {
        assertEquals(SubscriptionBypassState.Error(BypassReason.SUBSCRIPTION_NOT_FOUND), state(null, TurnFetchOutcome.NOT_FOUND))
        assertEquals(SubscriptionBypassState.Error(BypassReason.LOAD_FAILED), state(null, TurnFetchOutcome.FAILED))
        // Another provider's 404 on /turn says nothing about its subscription.
        assertEquals(SubscriptionBypassState.Unknown, state(null, TurnFetchOutcome.NOT_FOUND, known = false))
        assertEquals(SubscriptionBypassState.Unknown, state(null, TurnFetchOutcome.FAILED, known = false))
    }

    @Test
    fun beforeTheFirstRecordedRefresh() {
        assertEquals(SubscriptionBypassState.None, state(null, null, known = true))
        assertEquals(SubscriptionBypassState.Unknown, state(null, null, known = false))
    }

    @Test
    fun issueOrder() {
        fun issue(
            disabled: Boolean = false,
            subscription: SubscriptionBypassState? = SubscriptionBypassState.Available,
            outcome: TurnFetchOutcome? = TurnFetchOutcome.OK,
            relay: Boolean = true,
            link: Boolean = true,
        ) = BypassStatus.issue(disabled, subscription, outcome, relay, link)

        assertNull(issue())
        assertEquals(BypassReason.DISABLED_IN_SETTINGS, issue(disabled = true, relay = false))
        assertEquals(BypassReason.LOAD_FAILED, issue(subscription = SubscriptionBypassState.Error(BypassReason.LOAD_FAILED)))
        assertEquals(BypassReason.SERVER_NOT_COVERED, issue(relay = false))
        assertEquals(BypassReason.SERVER_NOT_COVERED, issue(subscription = SubscriptionBypassState.None, relay = false))
        assertEquals(BypassReason.LOAD_FAILED, issue(subscription = SubscriptionBypassState.Unknown, outcome = TurnFetchOutcome.FAILED, relay = false))
        assertEquals(BypassReason.SUBSCRIPTION_NOT_FOUND, issue(subscription = null, outcome = TurnFetchOutcome.NOT_FOUND, relay = false))
        assertEquals(BypassReason.NO_VK_LINK, issue(link = false))
        // Standalone server, nothing known.
        assertEquals(BypassReason.SERVER_NOT_COVERED, issue(subscription = null, outcome = null, relay = false))
    }

    @Test
    fun onlyServerNotCoveredIsNotRed() {
        BypassReason.entries.forEach { assertEquals(it != BypassReason.SERVER_NOT_COVERED, it.isError) }
        assertTrue(BypassReason.CAPTCHA_FAILED.isRuntime)
        assertFalse(BypassReason.NO_VK_LINK.isRuntime)
    }

    @Test
    fun outcomeRawValuesRoundTrip() {
        TurnFetchOutcome.entries.forEach { assertEquals(it, TurnFetchOutcome.fromRaw(it.rawValue)) }
        assertNull(TurnFetchOutcome.fromRaw("nope"))
    }
}
