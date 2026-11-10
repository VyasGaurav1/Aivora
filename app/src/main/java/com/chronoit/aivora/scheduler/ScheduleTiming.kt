package com.chronoit.aivora.scheduler

object ScheduleTiming {
    const val MIN_REPEAT = 15 * 60_000L
    const val MAX_DELAY = 365 * 24 * 60 * 60_000L
    fun afterExecution(dueAt: Long, repeatMillis: Long, now: Long): Long = nextOccurrence(dueAt, repeatMillis, maxOf(now, dueAt))
    fun nextOccurrence(dueAt: Long, repeatMillis: Long, now: Long): Long {
        require(dueAt >= 0 && now >= 0 && repeatMillis >= MIN_REPEAT)
        if (dueAt > now) return dueAt
        val periods = (now - dueAt) / repeatMillis + 1
        return Math.addExact(dueAt, Math.multiplyExact(periods, repeatMillis))
    }
}
