package com.argsment.anywhere.data.sync

import com.argsment.anywhere.turn.TurnMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Forced refresh by revision + daily auto-refresh. All values are invented. */
class SubscriptionAutoRefreshTest {

    private val hour = 60 * 60 * 1000L
    private val now = 1_800_000_000_000L
    private val synced = SubscriptionSyncRecord(revUrl = "https://sub.example.invalid/AbC/rev", rev = 3)

    // MARK: - push block

    @Test
    fun parsesPushNextToTurn() {
        val body = """
        {"turn": {"version": 1, "servers": {"alpha.example.invalid": {"peer_addr": "203.0.113.10:56000",
            "supported": true, "wrap_key_hex": "00112233445566778899aabbccddeeff"}}},
         "push": {"rev": 7, "rev_url": "https://sub.example.invalid/AbC/rev",
            "apns_register_url": "https://sub.example.invalid/AbC/push/apns"}}
        """.trimIndent()
        assertEquals(SubscriptionPush(7, "https://sub.example.invalid/AbC/rev"), SubscriptionPush.parse(body))
        assertNotNull(TurnMetadata.extractFromSingBoxJson(body))
    }

    @Test
    fun parsesPushWithoutTurn() {
        val body = """{"push": {"rev": 0, "rev_url": "https://sub.example.invalid/AbC/rev"}}"""
        assertEquals(SubscriptionPush(0, "https://sub.example.invalid/AbC/rev"), SubscriptionPush.parse(body))
        assertNull(TurnMetadata.extractFromSingBoxJson(body))
    }

    @Test
    fun rejectsMissingOrBrokenPush() {
        assertNull(SubscriptionPush.parse("""{"turn": {"servers": {}}}"""))
        assertNull(SubscriptionPush.parse("""{"push": {"rev_url": "https://x.invalid/rev"}}"""))
        assertNull(SubscriptionPush.parse("""{"error": "not found"}"""))
        assertNull(SubscriptionPush.parse("not json"))
        // A non-http rev_url is dropped, the revision is kept.
        assertEquals(SubscriptionPush(2, null), SubscriptionPush.parse("""{"push": {"rev": 2, "rev_url": "file:///etc/passwd"}}"""))
    }

    @Test
    fun parsesRevEndpoint() {
        assertEquals(5L, SubscriptionPush.parseRev("""{"rev": 5}"""))
        assertNull(SubscriptionPush.parseRev("""{"error": "x"}"""))
        assertNull(SubscriptionPush.parseRev(""))
    }

    // MARK: - decision

    @Test
    fun freshAndInSyncIsLeftAlone() {
        assertFalse(SubscriptionAutoRefresh.shouldRefresh(now - hour, now, synced, 3))
        assertFalse(SubscriptionAutoRefresh.shouldRefresh(now - hour, now, synced, null))
        assertFalse(SubscriptionAutoRefresh.shouldRefresh(now - hour, now, synced, 2))
    }

    @Test
    fun newerServerRevisionRefreshes() {
        assertTrue(SubscriptionAutoRefresh.shouldRefresh(now - hour, now, synced, 4))
    }

    @Test
    fun olderThanADayRefreshes() {
        assertTrue(SubscriptionAutoRefresh.shouldRefresh(now - 24 * hour, now, synced, null))
        assertFalse(SubscriptionAutoRefresh.shouldRefresh(now - 23 * hour, now, synced, null))
    }

    @Test
    fun unknownStateRefreshes() {
        // Never refreshed since the feature shipped: learn the rev URL.
        assertTrue(SubscriptionAutoRefresh.shouldRefresh(now - hour, now, null, null))
        assertTrue(SubscriptionAutoRefresh.shouldRefresh(null, now, synced, null))
    }

    @Test
    fun clockMovedBackRefreshes() {
        assertTrue(SubscriptionAutoRefresh.shouldRefresh(now + 2 * hour, now, synced, null))
        assertFalse(SubscriptionAutoRefresh.shouldRefresh(now + 10_000, now, synced, null))
    }

    // MARK: - store

    @Test
    fun storeRecordsAndPersists() {
        val dir = Files.createTempDirectory("sync").toFile()
        val file = File(dir, SubscriptionSyncStore.FILE_NAME)
        val id = UUID.randomUUID()
        val store = SubscriptionSyncStore(file)
        assertNull(store.record(id))

        store.recordRefresh(id, SubscriptionPush(9, "https://sub.example.invalid/AbC/rev"))
        assertEquals(SubscriptionSyncRecord("https://sub.example.invalid/AbC/rev", 9), store.record(id))

        // Writes are asynchronous; wait for the file before reloading.
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(100)
        assertEquals(SubscriptionSyncRecord("https://sub.example.invalid/AbC/rev", 9), SubscriptionSyncStore(file).record(id))

        // A refresh without a push block still counts as synced (no rev URL to poll).
        store.recordRefresh(id, null)
        assertEquals(SubscriptionSyncRecord(null, 0), store.record(id))
        store.remove(id)
        assertNull(store.record(id))
        dir.deleteRecursively()
    }
}
