package com.argsment.anywhere.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TurnMetadataStoreTest {

    private fun server(host: String, usable: Boolean) =
        TurnServerInfo(host, supported = usable, peerAddr = if (usable) "192.0.2.1:56000" else "", wrapKeyHex = "")

    private fun metadata(vararg servers: TurnServerInfo, vkLink: String? = null, defaults: TurnDefaults? = null) =
        TurnMetadata(vkLink = vkLink, defaults = defaults, servers = servers.associateBy { it.host })

    @Test
    fun usableServerWinsAcrossSubscriptions() {
        val store = TurnMetadataStore(null)
        store.setMetadata(metadata(server("jp.example.invalid", false)), UUID.randomUUID())
        store.setMetadata(metadata(server("jp.example.invalid", true)), UUID.randomUUID())
        assertTrue(store.server("JP.example.invalid")!!.isUsable)
        assertEquals(1, store.allServers().size)
        assertTrue(store.allServers().single().isUsable)
    }

    @Test
    fun defaultsAndLinkComeFromTheOwningSubscription() {
        val store = TurnMetadataStore(null)
        store.setMetadata(metadata(server("a.example.invalid", true), vkLink = "  "), UUID.randomUUID())
        store.setMetadata(
            metadata(server("b.example.invalid", true), vkLink = "https://vk.example.invalid/x", defaults = TurnDefaults(readyTimeout = 7)),
            UUID.randomUUID()
        )
        assertEquals(7, store.defaults("b.example.invalid")!!.readyTimeout)
        assertNull(store.defaults("a.example.invalid"))
        assertEquals("https://vk.example.invalid/x", store.subscriptionVKLink())
    }

    @Test
    fun refreshWithoutTurnRemovesTheBlock() {
        val store = TurnMetadataStore(null)
        val id = UUID.randomUUID()
        store.setMetadata(metadata(server("a.example.invalid", true)), id)
        store.setMetadata(null, id)
        assertNull(store.server("a.example.invalid"))
        assertTrue(store.all().isEmpty())
    }
}
