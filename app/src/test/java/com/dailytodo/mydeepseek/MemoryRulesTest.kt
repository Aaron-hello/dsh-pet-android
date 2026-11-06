package com.dailytodo.mydeepseek

import org.junit.Assert.*
import org.junit.Test

class MemoryRulesTest {
    @Test fun extractionRequiresConsentTenCompletedTurnsAndNoPause() {
        assertFalse(MemoryRules.ready(false, false, 10))
        assertFalse(MemoryRules.ready(true, false, 9))
        assertFalse(MemoryRules.ready(true, true, 10))
        assertTrue(MemoryRules.ready(true, false, 10))
    }
    @Test fun rollingQuotaWaitsUntilTheOldestAttemptExpires() {
        val now = 10_000_000L
        assertEquals(0L, MemoryRules.delay(listOf(now - 1000, now - 2000), now))
        assertEquals(3_597_000L, MemoryRules.delay(listOf(now - 3000, now - 2000, now - 1000), now))
        assertEquals(0L, MemoryRules.delay(listOf(now - MemoryRules.HOUR, now - 2000, now - 1000), now))
        assertTrue(MemoryRules.delay(listOf(now + 10_000, now, now), now) > 0)
    }
    @Test fun obviousSecretsAreRejectedWhileOrdinaryMemoriesAreAccepted() {
        assertTrue(MemoryRules.safe("喜欢在周末看电影"))
        assertFalse(MemoryRules.safe("API key sk-abcdefgh123456"))
        assertFalse(MemoryRules.safe("密码是 123456"))
        assertFalse(MemoryRules.safe("x".repeat(161)))
        assertFalse(MemoryRules.safe("   "))
    }
}
