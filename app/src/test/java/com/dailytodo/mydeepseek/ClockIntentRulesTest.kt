package com.dailytodo.mydeepseek

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ClockIntentRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    @Test fun prefillOnlyWhenClockNextOccurrenceMatchesTheWholeRequestedInstant() {
        val now = millis("2026-11-03T02:30:00Z")
        assertTrue(TodoRules.clockCanPrefill(millis("2026-11-03T04:00:00Z"), now, zone))
        assertFalse(TodoRules.clockCanPrefill(millis("2026-11-04T04:00:00Z"), now, zone))
        assertTrue(TodoRules.clockCanPrefill(millis("2026-11-04T01:00:00Z"), now, zone))
        assertFalse(TodoRules.clockCanPrefill(millis("2026-11-03T04:00:30Z"), now, zone))
        assertFalse(TodoRules.clockCanPrefill(millis("2026-11-03T01:00:00Z"), now, zone))
    }
}
