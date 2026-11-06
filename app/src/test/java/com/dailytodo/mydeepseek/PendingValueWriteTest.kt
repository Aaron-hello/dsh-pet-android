package com.dailytodo.mydeepseek

import org.junit.Assert.*
import org.junit.Test

class PendingValueWriteTest {
    @Test fun cancelBeforeSavingLeavesPreviousAccountAlone() {
        var stored: String? = "old-fixture"
        PendingValueWrite({ stored }, { stored = it }).rollback()
        assertEquals("old-fixture", stored)
    }
    @Test fun cancelAfterCommitRestoresThePreviousAccount() {
        var stored: String? = "old-fixture"
        val pending = PendingValueWrite({ stored }, { stored = it })
        pending.commit("new-fixture")
        assertEquals("new-fixture", stored)
        pending.rollback()
        assertEquals("old-fixture", stored)
        pending.rollback()
        assertEquals("old-fixture", stored)
    }
    @Test fun cancelledFirstConnectionLeavesNoAccount() {
        var stored: String? = null
        val pending = PendingValueWrite({ stored }, { stored = it })
        pending.commit("new-fixture"); pending.rollback()
        assertNull(stored)
    }
    @Test fun partiallyAppliedFailedWriteIsUndone() {
        var stored: String? = "old-fixture"
        val pending = PendingValueWrite({ stored }, { value: String? ->
            stored = value
            if (value == "new-fixture") throw IllegalStateException("fixture commit failure")
        })
        assertThrows(IllegalStateException::class.java) { pending.commit("new-fixture") }
        assertEquals("old-fixture", stored)
    }
}
