package com.example.digicar

data class RoundTripSummary(val averageMs: Long, val worstMs: Long)

/** Condenses command-to-`OK` times into one summary per time window. Not thread-safe. */
class RoundTripStats(private val windowMs: Long = 1_000) {
    private var windowStartMs: Long? = null
    private var count = 0
    private var totalMs = 0L
    private var worstMs = 0L

    /** @return a summary when this sample completes a window, otherwise null */
    fun add(roundTripMs: Long, nowMs: Long): RoundTripSummary? {
        val startMs = windowStartMs ?: nowMs.also { windowStartMs = it }
        count++
        totalMs += roundTripMs
        worstMs = maxOf(worstMs, roundTripMs)
        if (nowMs - startMs < windowMs) return null

        val summary = RoundTripSummary(totalMs / count, worstMs)
        windowStartMs = null
        count = 0
        totalMs = 0
        worstMs = 0
        return summary
    }
}
