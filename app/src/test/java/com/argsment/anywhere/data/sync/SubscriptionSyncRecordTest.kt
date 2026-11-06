package com.argsment.anywhere.data.sync

import com.argsment.anywhere.turn.TurnFetchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a refresh updates the per-subscription record. URLs invented. */
class SubscriptionSyncRecordTest {

    private val push = SubscriptionPush(rev = 7, revUrl = "https://sub.example.invalid/x/rev")

    @Test
    fun okRecordsPushAndMarksSeen() {
        val r = SubscriptionSyncRecord.next(null, push, TurnFetchOutcome.OK)
        assertEquals(push.revUrl, r.revUrl)
        assertEquals(7L, r.rev)
        assertEquals(TurnFetchOutcome.OK, r.outcome)
        assertTrue(r.knownOurs)
    }

    @Test
    fun failedKeepsWhatWasKnown() {
        val before = SubscriptionSyncRecord.next(null, push, TurnFetchOutcome.OK)
        val after = SubscriptionSyncRecord.next(before, null, TurnFetchOutcome.FAILED)
        assertEquals(before.revUrl, after.revUrl)
        assertEquals(before.rev, after.rev)
        assertEquals(TurnFetchOutcome.FAILED, after.outcome)
        assertTrue(after.turnSeen)
    }

    @Test
    fun notFoundKeepsSeenAndRevUrl() {
        val before = SubscriptionSyncRecord.next(null, push, TurnFetchOutcome.OK)
        val after = SubscriptionSyncRecord.next(before, null, TurnFetchOutcome.NOT_FOUND)
        assertEquals(TurnFetchOutcome.NOT_FOUND, after.outcome)
        assertTrue(after.knownOurs)
        assertEquals(before.revUrl, after.revUrl)
    }

    @Test
    fun anotherProvidersSubscriptionIsNotOurs() {
        val r = SubscriptionSyncRecord.next(null, null, TurnFetchOutcome.NOT_FOUND)
        assertFalse(r.knownOurs)
        assertNull(SubscriptionSyncRecord.next(null, null, null).outcome)
    }

    @Test
    fun legacyRecordWithRevUrlCountsAsOurs() {
        assertTrue(SubscriptionSyncRecord(revUrl = "https://sub.example.invalid/x/rev", rev = 1).knownOurs)
    }
}
