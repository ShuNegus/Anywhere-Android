package com.argsment.anywhere.vpn.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnCoreStatusTest {

    private fun status(
        usable: Boolean = true, sessions: Int = 20, target: Int = 40, fetch: String = "captcha_wait",
        version: Int = 1, captchas: Int = 1, setsObtained: Int = 2,
    ) = """
        {"version":$version,"usable":$usable,"sessions":$sessions,"target":$target,"phase":5,
         "fetch":{"state":"$fetch","set_id":1,"backoff_ms":0,"waiting":3},
         "sets_obtained":$setsObtained,"sets_retired":0,"captchas":$captchas,
         "sets":[{"id":0,"state":"full","active":10,"pending":0,"capacity":20},
                 {"id":1,"state":"filling","active":10,"pending":2,"capacity":0}],
         "future_field":"ignored"}
    """.trimIndent()

    @Test
    fun decodesTheGoSnapshot() {
        val s = TurnCoreStatus.decode(status())!!
        assertTrue(s.usable)
        assertEquals(20, s.sessions)
        assertEquals(40, s.target)
        assertEquals(5, s.phase)
        assertEquals(TurnFetchState.CAPTCHA_WAIT, s.fetch.state)
        assertEquals(1, s.fetch.setId)
        assertEquals(0, s.fetch.backoffMs)
        assertEquals(3, s.fetch.waiting)
        assertEquals(2, s.setsObtained)
        assertEquals(1, s.captchas)
        assertEquals(listOf("full", "filling"), s.sets.map { it.state })
        assertEquals(20, s.sets[0].capacity)
    }

    @Test
    fun rejectsUnknownVersionsAndBrokenPayloads() {
        assertNull(TurnCoreStatus.decode(status(version = 2)))
        assertNull(TurnCoreStatus.decode(""))
        assertNull(TurnCoreStatus.decode(null))
        assertNull(TurnCoreStatus.decode("not json"))
        assertNull(TurnCoreStatus.decode("""{"version":1,"usable":true}"""))
    }

    @Test
    fun backoffIsOptionalAndUnknownFetchStateIsTolerated() {
        val json = status(fetch = "brand_new").replace(""","backoff_ms":0""", "")
        val s = TurnCoreStatus.decode(json)!!
        assertNull(s.fetch.backoffMs)
        assertEquals(TurnFetchState.UNKNOWN, s.fetch.state)
    }

    @Test
    fun combinesSeveralRelays() {
        val a = TurnCoreStatus.decode(status(usable = false, sessions = 0, target = 10, fetch = "fetching", captchas = 0, setsObtained = 0))!!
        val b = TurnCoreStatus.decode(status(usable = true, sessions = 20, target = 40, fetch = "captcha_auto", captchas = 2, setsObtained = 1))!!
        val pool = TurnPoolSummary.combining(listOf(a, b))!!
        assertTrue(pool.usable)
        assertEquals(20, pool.sessions)
        assertEquals(50, pool.target)
        assertEquals(TurnFetchState.CAPTCHA_AUTO, pool.fetch)
        assertEquals(2, pool.captchas)
        assertEquals(1, pool.setsObtained)
        assertTrue(pool.isAddingPeers)
        assertFalse(pool.needsCaptchaForMorePeers)
        assertNull(TurnPoolSummary.combining(emptyList()))
    }

    @Test
    fun usablePoolWaitingForCaptchaNeedsItForMorePeers() {
        val pool = TurnPoolSummary.combining(listOf(TurnCoreStatus.decode(status())!!))!!
        assertTrue(pool.needsCaptchaForMorePeers)
        val idle = TurnPoolSummary.combining(listOf(TurnCoreStatus.decode(status(fetch = "idle", sessions = 40))!!))!!
        assertFalse(idle.isAddingPeers)
    }

    @Test
    fun snapshotPhaseIsTheLaggingPool() {
        fun host(name: String, phase: Int?, sessions: Int) =
            TurnHostStatistics(name, sessions, 0, phase, null, null, null)
        assertEquals(
            TurnPhase.CAPTCHA_AUTO,
            TurnStatsSnapshot(listOf(host("a", 5, 10), host("b", 2, 0), host("c", 0, 0))).phase
        )
        assertEquals(TurnPhase.READY, TurnStatsSnapshot(listOf(host("a", 0, 3))).phase)
        assertEquals(TurnPhase.READY, TurnStatsSnapshot(listOf(host("a", null, 3))).phase)
        assertNull(TurnStatsSnapshot(listOf(host("a", 0, 0))).phase)
        assertNull(TurnStatsSnapshot().phase)
        assertNotNull(TurnStatsSnapshot(listOf(host("a", 4, 0))).phase)
    }
}
