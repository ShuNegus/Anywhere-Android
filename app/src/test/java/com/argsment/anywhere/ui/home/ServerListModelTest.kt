package com.argsment.anywhere.ui.home

import com.argsment.anywhere.data.model.ProxyChain
import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.data.model.Subscription
import com.argsment.anywhere.data.network.LatencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ServerListModelTest {

    private fun config(name: String, subscriptionId: UUID? = null) = ProxyConfiguration(
        name = name,
        serverAddress = "203.0.113.10",
        serverPort = 443u,
        uuid = UUID.randomUUID(),
        encryption = "none",
        subscriptionId = subscriptionId,
    )

    @Test
    fun noConfigurationsMeansNoSections() {
        assertTrue(ServerListModel.sections(emptyList(), emptyList(), emptyList(), "Chains").isEmpty())
    }

    @Test
    fun standaloneThenChainsThenSubscriptionsInStoreOrder() {
        val subA = Subscription(name = "Main", url = "https://a")
        val subB = Subscription(name = "Other", url = "https://b")
        val empty = Subscription(name = "Empty", url = "https://c")
        val solo = config("Solo")
        val a1 = config("🇷🇺 Russia", subA.id)
        val a2 = config("🇺🇸 USA 1", subA.id)
        val b1 = config("🇯🇵 Japan", subB.id)
        val chain = ProxyChain(name = "Via RU", proxyIds = listOf(a1.id, b1.id))

        val sections = ServerListModel.sections(
            configurations = listOf(solo, b1, a1, a2),
            chains = listOf(chain),
            subscriptions = listOf(subA, empty, subB),
            chainsHeader = "Chains",
        )

        assertEquals(4, sections.size)
        assertEquals(ServerListModel.STANDALONE_SECTION_ID, sections[0].id)
        assertNull(sections[0].header)
        assertNull(sections[0].subscriptionId)
        assertEquals(listOf(solo.id), sections[0].items.map { it.id })

        assertEquals(ServerListModel.CHAINS_SECTION_ID, sections[1].id)
        assertEquals("Chains", sections[1].header)
        assertNull("chains are not deletable as a subscription", sections[1].subscriptionId)

        assertEquals(subA.id, sections[2].subscriptionId)
        assertEquals("Main", sections[2].header)
        assertEquals(listOf(a1.id, a2.id), sections[2].items.map { it.id })
        assertEquals(subB.id, sections[3].subscriptionId)
    }

    @Test
    fun brokenOrShortChainsAreNotListed() {
        val a = config("A")
        val short = ProxyChain(name = "Short", proxyIds = listOf(a.id))
        val dangling = ProxyChain(name = "Dangling", proxyIds = listOf(a.id, UUID.randomUUID()))
        val sections = ServerListModel.sections(listOf(a), listOf(short, dangling), emptyList(), "Chains")
        assertEquals(1, sections.size)
        assertEquals(ServerListModel.STANDALONE_SECTION_ID, sections[0].id)
    }

    @Test
    fun selectedRowPrefersTheChain() {
        val chain = UUID.randomUUID()
        val configId = UUID.randomUUID()
        assertEquals(chain, ServerListModel.selectedRowId(chain, configId))
        assertEquals(configId, ServerListModel.selectedRowId(null, configId))
        assertNull(ServerListModel.selectedRowId(null, null))
    }

    @Test
    fun latenciesMergeAndMeasuringState() {
        val id = UUID.randomUUID()
        val chainId = UUID.randomUUID()
        val merged = ServerListModel.mergedLatencies(
            mapOf(id to LatencyResult.Success(18)),
            mapOf(chainId to LatencyResult.Failed, id to LatencyResult.Testing),
        )
        assertEquals(LatencyResult.Success(18), merged[id])
        assertEquals(LatencyResult.Failed, merged[chainId])

        assertFalse(ServerListModel.isMeasuring(mapOf(id to LatencyResult.Success(1)), emptyMap()))
        assertTrue(ServerListModel.isMeasuring(emptyMap(), mapOf(chainId to LatencyResult.Testing)))
    }

    @Test
    fun splitFlagSeparatesALeadingFlag() {
        assertEquals("🇺🇸" to "Los Angeles", ServerListModel.splitFlag("🇺🇸 Los Angeles"))
        assertEquals("🇳🇱" to "Netherlands 1", ServerListModel.splitFlag("🇳🇱Netherlands 1"))
        assertEquals("" to "Plain name", ServerListModel.splitFlag("Plain name"))
        assertEquals("" to "", ServerListModel.splitFlag(""))
        // A lone regional indicator is not a flag.
        assertEquals("" to "🇺 x", ServerListModel.splitFlag("🇺 x"))
        // Other emoji are not flags either.
        assertEquals("" to "🚀 Fast", ServerListModel.splitFlag("🚀 Fast"))
    }

    @Test
    fun swipeOffsetIsClampedAndSettles() {
        assertEquals(0f, SubscriptionSwipe.offset(isOpen = false, dragTranslation = 40f))
        assertEquals(-72f, SubscriptionSwipe.offset(isOpen = true, dragTranslation = 0f))
        assertEquals(-160f, SubscriptionSwipe.offset(isOpen = true, dragTranslation = -500f))

        assertEquals(SubscriptionSwipe.Outcome.DELETE, SubscriptionSwipe.settle(-160f))
        assertEquals(SubscriptionSwipe.Outcome.OPEN, SubscriptionSwipe.settle(-36f))
        assertEquals(SubscriptionSwipe.Outcome.OPEN, SubscriptionSwipe.settle(-100f))
        assertEquals(SubscriptionSwipe.Outcome.CLOSE, SubscriptionSwipe.settle(-35f))
        assertEquals(SubscriptionSwipe.Outcome.CLOSE, SubscriptionSwipe.settle(0f))
    }
}
