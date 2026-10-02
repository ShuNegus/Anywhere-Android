package com.argsment.anywhere.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Port of iOS `TurnMetadataTests`. All values are invented. */
class TurnMetadataTest {

    companion object {
        val SING_BOX_DOCUMENT = """
        {
          "log": { "level": "info" },
          "dns": { "servers": [] },
          "turn": {
            "version": 1,
            "vk_link_required": true,
            "vk_link": "https://example.invalid/call/join/AAAAAAAAAAAAAAAA",
            "defaults": {
              "wrap_mode": true,
              "num_streams": 10,
              "ready_timeout": 30,
              "captcha_solver": "v2",
              "streams_per_cred": 2
            },
            "servers": {
              "alpha.example.invalid": {
                "peer_addr": "203.0.113.10:56000",
                "supported": true,
                "wrap_key_hex": "00112233445566778899aabbccddeeff"
              },
              "beta.example.invalid": {
                "peer_addr": "198.51.100.20:56000",
                "supported": false,
                "wrap_key_hex": "ffeeddccbbaa99887766554433221100"
              }
            }
          },
          "outbounds": []
        }
        """.trimIndent()
    }

    @Test
    fun extractsTurnBlockFromSingBoxDocument() {
        val metadata = TurnMetadata.extractFromSingBoxJson(SING_BOX_DOCUMENT)!!
        assertEquals(1, metadata.version)
        assertEquals(true, metadata.vkLinkRequired)
        assertEquals("https://example.invalid/call/join/AAAAAAAAAAAAAAAA", metadata.vkLink)
        assertEquals(2, metadata.servers.size)

        val defaults = metadata.defaults!!
        assertEquals(true, defaults.wrapMode)
        assertEquals(10, defaults.numStreams)
        assertEquals(30, defaults.readyTimeout)
        assertEquals("v2", defaults.captchaSolver)
        assertEquals(2, defaults.streamsPerCred)
    }

    @Test
    fun foldsDictionaryKeyIntoServerHost() {
        val metadata = TurnMetadata.extractFromSingBoxJson(SING_BOX_DOCUMENT)!!

        val alpha = metadata.server("alpha.example.invalid")!!
        assertEquals("alpha.example.invalid", alpha.host)
        assertEquals("203.0.113.10:56000", alpha.peerAddr)
        assertEquals("00112233445566778899aabbccddeeff", alpha.wrapKeyHex)
        assertTrue(alpha.supported)
        assertTrue(alpha.isUsable)

        val beta = metadata.server("beta.example.invalid")!!
        assertFalse(beta.supported)
        assertFalse(beta.isUsable)

        assertEquals(listOf("alpha.example.invalid", "beta.example.invalid"), metadata.sortedServers.map { it.host })
        assertNull(metadata.server("gamma.example.invalid"))
        // Lookup is case-insensitive on the caller's side.
        assertNotNull(metadata.server("ALPHA.example.invalid"))
    }

    @Test
    fun survivesAPersistenceRoundTrip() {
        val metadata = TurnMetadata.extractFromSingBoxJson(SING_BOX_DOCUMENT)!!
        val id = UUID.randomUUID()
        val decoded = TurnMetadataStore.decode(TurnMetadataStore.encode(mapOf(id to metadata)))
        assertEquals(metadata, decoded[id])
        assertEquals("alpha.example.invalid", decoded[id]!!.server("alpha.example.invalid")!!.host)
    }

    @Test
    fun rejectsDocumentsWithoutUsableTurnBlock() {
        val cases = listOf(
            """{"outbounds": []}""",
            """{"turn": {"version": 1, "servers": {}}}""",
            """[{"turn": {}}]""",
            """{"turn": "nope"}""",
            "not json at all",
            "",
        )
        for (json in cases) {
            assertNull("should reject: $json", TurnMetadata.extractFromSingBoxJson(json))
        }
    }

    @Test
    fun toleratesAPartialTurnBlock() {
        val json = """{"turn": {"servers": {"solo.example.invalid": {"peer_addr": "192.0.2.1:56000"}}}}"""
        val metadata = TurnMetadata.extractFromSingBoxJson(json)!!
        assertEquals(1, metadata.version)
        assertNull(metadata.vkLink)
        assertNull(metadata.defaults)

        val solo = metadata.server("solo.example.invalid")!!
        assertEquals("192.0.2.1:56000", solo.peerAddr)
        assertTrue(solo.wrapKeyHex.isEmpty())
        assertFalse("absent means not supported", solo.supported)
        assertFalse(solo.isUsable)
    }

    @Test
    fun wrongTypesReadAsAbsent() {
        val json = """
            {"turn": {"version": "two", "vk_link": 5, "defaults": {"wrap_mode": "yes", "ready_timeout": 12},
             "servers": {"a.example.invalid": {"supported": "true", "peer_addr": 1}, "b": 7}}}
        """.trimIndent()
        val metadata = TurnMetadata.extractFromSingBoxJson(json)!!
        assertEquals(1, metadata.version)
        assertNull(metadata.vkLink)
        assertNull(metadata.defaults!!.wrapMode)
        assertEquals(12, metadata.defaults!!.readyTimeout)
        assertEquals(setOf("a.example.invalid"), metadata.servers.keys)
        assertFalse(metadata.server("a.example.invalid")!!.supported)
    }
}
