package com.example.digicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoundTripStatsTest {
    @Test
    fun summarizesOnceTheWindowIsComplete() {
        val stats = RoundTripStats(windowMs = 1_000)

        assertNull(stats.add(10, 0))
        assertNull(stats.add(30, 500))
        assertEquals(RoundTripSummary(averageMs = 20, worstMs = 30), stats.add(20, 1_000))
    }

    @Test
    fun startsAFreshWindowAfterEachSummary() {
        val stats = RoundTripStats(windowMs = 1_000)
        stats.add(500, 0)
        stats.add(500, 1_000)

        assertNull(stats.add(40, 1_100))
        assertEquals(RoundTripSummary(averageMs = 50, worstMs = 60), stats.add(60, 2_100))
    }
}
