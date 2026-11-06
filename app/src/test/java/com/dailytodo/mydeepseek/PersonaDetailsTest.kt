package com.dailytodo.mydeepseek

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonaDetailsTest {
    @Test fun legacyPersonaAndCategorizedDetailsAreBothIncluded() {
        val old = "既有设定".repeat(2200)
        val profile = JSONObject().put("name", "小蓝").put("persona", old).put("style", "简洁")
            .put("categories", JSONObject().put("identity", "星际旅人").put("relationship", "老朋友"))
        val text = PersonaDetails.build(profile)
        assertTrue(text.contains(old)); assertTrue(text.contains("身份背景")); assertTrue(text.contains("星际旅人"))
        assertEquals(PersonaRules.length(old) + 7, PersonaDetails.length(profile))
        assertEquals("", PersonaDetails.categories(null).getString("examples"))
    }
    @Test fun validStateMarkerOnlyAcceptsBoundedKnownValues() {
        val result = EmotionRules.parse("好开心！\n[图:开心]\n[状态:开心,+2]")
        assertEquals("开心", result.mood); assertEquals(2, result.delta); assertTrue(result.text.endsWith("[图:开心]"))
        assertNull(EmotionRules.parse("你好\n[状态:开心,99]").delta)
        assertNull(EmotionRules.parse("你好\n[状态:未知,+1]").mood)
        assertNull(EmotionRules.parse("[状态:开心,1]").mood)
        assertEquals("你好", EmotionRules.parse("你好\n[状态:坏,+1]").text)
        assertEquals("你好", EmotionRules.streaming("你好\n[状态:开心,"))
        assertTrue(EmotionRules.valid(EmotionRules.defaults()))
        assertFalse(EmotionRules.valid(EmotionRules.defaults().put("affinity", 101)))
        assertFalse(EmotionRules.valid(EmotionRules.defaults().put("mood", "未知")))
    }
}
