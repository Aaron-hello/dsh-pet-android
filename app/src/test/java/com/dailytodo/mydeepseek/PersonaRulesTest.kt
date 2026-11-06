package com.dailytodo.mydeepseek

import org.junit.Assert.*
import org.junit.Test

class PersonaRulesTest {
    @Test fun acceptsTenThousandUnicodeCharactersWithoutCuttingEmoji() {
        val prompt = "性".repeat(9999) + "😀"
        assertEquals(10_000, PersonaRules.length(prompt))
        assertTrue(PersonaRules.valid(prompt))
        assertEquals(prompt, PersonaRules.bounded(prompt))
        assertEquals(prompt, DeepSeekProtocol.body(PersonaRules.bounded(prompt), emptyList(), "你好")
            .getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(PersonaRules.valid(prompt + "好"))
        assertEquals(prompt, PersonaRules.bounded(prompt + "好"))
        assertFalse(PersonaRules.valid("  "))
    }
}
