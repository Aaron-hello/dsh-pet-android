package com.dailytodo.mydeepseek

import org.json.JSONObject

object PersonaDetails {
    val keys = listOf("identity", "personality", "relationship", "speech", "boundaries", "examples")
    val labels = listOf("身份背景", "性格特点", "与用户的关系", "说话风格", "行为边界", "示例对话")
    fun categories(value: JSONObject?): JSONObject = JSONObject().also { out ->
        keys.forEach { key -> out.put(key, value?.optString(key).orEmpty()) }
    }
    fun length(profile: JSONObject): Int = PersonaRules.length(profile.optString("persona")) +
        keys.sumOf { PersonaRules.length(profile.optJSONObject("categories")?.optString(it).orEmpty()) }
    fun build(profile: JSONObject): String = buildString {
        append("你的名字是").append(profile.optString("name").take(40)).append("。\n")
        append(PersonaRules.bounded(profile.optString("persona")))
        val extra = profile.optJSONObject("categories")
        keys.forEachIndexed { i, key ->
            val value = extra?.optString(key).orEmpty().trim()
            if (value.isNotEmpty()) append("\n【").append(labels[i]).append("】\n").append(value)
        }
        append("\n表达方式：").append(profile.optString("style").take(500))
    }
}

object EmotionRules {
    val moods = listOf("平静", "开心", "难过", "生气", "惊讶", "害羞", "委屈")
    fun defaults() = JSONObject().put("mood", "平静").put("affinity", 50).put("auto", true)
    fun valid(value: JSONObject): Boolean = value.optString("mood") in moods &&
        value.opt("affinity") is Int && value.optInt("affinity") in 0..100 && value.opt("auto") is Boolean
    fun instruction(state: JSONObject) = "\n当前情绪：${state.optString("mood")}；当前好感度：${state.optInt("affinity")}/100。它们仅影响语气，保持角色设定。"
    const val MARKER_INSTRUCTION = "\n请先正常回复，需要配图或动作时先写对应标记。最后另起一行输出且仅输出 [状态:情绪,变化]，情绪只能为平静、开心、难过、生气、惊讶、害羞、委屈之一；变化是 -3 到 3 的整数，表示本轮好感度变化。不要向用户解释此状态标记。"
    private val marker = Regex("\\[状态[:：]\\s*([^,，\\]\\n]{1,12})\\s*[,，]\\s*([+-]?\\d{1,2})\\s*\\]\\s*$")
    private val anyMarker = Regex("\\[状态[:：][^\\]\\n]*\\]\\s*$")
    data class Parsed(val text: String, val mood: String?, val delta: Int?)
    fun parse(raw: String): Parsed {
        val match = marker.find(raw)
        val body = if (match != null) raw.substring(0, match.range.first).trimEnd()
            else streaming(anyMarker.replace(raw, ""))
        val mood = match?.groupValues?.get(1)?.trim()
        val delta = match?.groupValues?.get(2)?.toIntOrNull()
        return if (body.isNotBlank() && mood in moods && delta != null && delta in -3..3)
            Parsed(body, mood, delta) else Parsed(body, null, null)
    }
    fun streaming(raw: String): String = raw
        .replace(Regex("\\[状态[:：][^\\]\\n]*\\]"), "")
        .replace(Regex("\\[(?:状(?:态)?)(?:[:：][^\\]\\n]*)?$"), "")
        .replace(Regex("\\[$"), "")
        .trimEnd()
}
