package com.dailytodo.mydeepseek

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PetCompanionRulesTest {
    @Test fun rollingBudgetIsSharedAndDoesNotResetAtTheClockHour() {
        val now = 10 * PetCompanionRules.HOUR
        val times = (0 until 12).map { now - it * 1000 }
        assertFalse(PetCompanionRules.allowed(times, now))
        assertFalse(PetCompanionRules.allowed(times, now + 2000))
        assertTrue(PetCompanionRules.allowed(times, now + PetCompanionRules.HOUR))
        assertFalse(PetCompanionRules.allowed(times, now - 100000)) // Clock moving back cannot reset budget.
    }
    @Test fun decorationUsesOnlyDeclaredOriginalAssetsAndFirstValidAction() {
        val config = JSONObject("""{"actions":["开心"],"chatMemes":[{"name":"可爱"}]}""")
        val reply = PetCompanionRules.decorate("主人你好！\n[动作:不存在]\n[动作：开心]\n[图:可爱]", config)
        assertEquals("主人你好！", reply.getString("text"))
        assertEquals("开心", reply.getString("action"))
        assertEquals("可爱", reply.getString("image"))
        val unknown = PetCompanionRules.decorate("你好\n[图:../../secret]", config)
        assertEquals("", unknown.getString("image"))
        assertEquals("你好\n[图:../../secret]", unknown.getString("text"))
        assertEquals("", PetCompanionRules.decorate("[图:可爱]", config).getString("image"))
    }
    @Test fun partialControlMarkersStayOutOfStreamingText() {
        for (tail in listOf("[动", "[动作", "[动作:", "[动作:开心", "[动作:开心]", "[图:可爱"))
            assertEquals("你好", PetCompanionRules.streamingText("你好\n$tail"))
        assertEquals("你好", PetCompanionRules.streamingText("你好"))
    }
}
