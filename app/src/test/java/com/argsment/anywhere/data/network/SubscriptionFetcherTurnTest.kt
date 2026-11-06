package com.argsment.anywhere.data.network

import com.argsment.anywhere.turn.TurnMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The dedicated `<subscription>/turn` endpoint. All values are invented. */
class SubscriptionFetcherTurnTest {

    @Test
    fun appendsTurnToSubscriptionPath() {
        assertEquals(
            "https://sub.example.invalid/AbC-123_x/turn",
            SubscriptionFetcher.turnEndpointUrl("https://sub.example.invalid/AbC-123_x")
        )
    }

    @Test
    fun trailingSlashIsNotDoubled() {
        assertEquals(
            "https://sub.example.invalid/AbC/turn",
            SubscriptionFetcher.turnEndpointUrl("https://sub.example.invalid/AbC/")
        )
    }

    @Test
    fun keepsPortAndQueryDropsFragment() {
        assertEquals(
            "http://sub.example.invalid:8080/p/AbC/turn?x=1",
            SubscriptionFetcher.turnEndpointUrl("http://sub.example.invalid:8080/p/AbC?x=1#name")
        )
    }

    @Test
    fun rejectsUrlsWithoutPathOrHttpScheme() {
        assertNull(SubscriptionFetcher.turnEndpointUrl("https://sub.example.invalid"))
        assertNull(SubscriptionFetcher.turnEndpointUrl("https://sub.example.invalid/"))
        assertNull(SubscriptionFetcher.turnEndpointUrl("ftp://sub.example.invalid/AbC"))
        assertNull(SubscriptionFetcher.turnEndpointUrl("not a url"))
    }

    @Test
    fun endpointResponseParsesWithExistingExtractor() {
        val body = """
        {"turn": {"version": 1, "vk_link_required": true,
          "vk_link": "https://example.invalid/call/join/AAAA",
          "defaults": {"wrap_mode": true, "num_streams": 10},
          "servers": {"alpha.example.invalid": {"peer_addr": "203.0.113.10:56000",
            "supported": true, "wrap_key_hex": "00112233445566778899aabbccddeeff"}}}}
        """.trimIndent()
        val metadata = TurnMetadata.extractFromSingBoxJson(body)!!
        assertEquals(1, metadata.servers.size)
        assertEquals("https://example.invalid/call/join/AAAA", metadata.vkLink)
    }
}
