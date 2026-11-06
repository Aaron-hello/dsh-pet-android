package com.dailytodo.mydeepseek

import org.json.JSONObject

/** Mobile request budget, independent of the upstream animation/physics rules. */
object PetCompanionRules {
    const val HOUR = 3600000L
    const val LIMIT = 12
    fun recent(times: List<Long>, now: Long) = times.filter { it > now - HOUR }
    fun allowed(times: List<Long>, now: Long) = recent(times, now).size < LIMIT

    /** Same marker acceptance as upstream chat-action.ts / memes.ts. */
    fun decorate(raw: String, config: JSONObject): JSONObject {
        val actions = config.optJSONArray("actions")
        var action = ""
        var tagged = false
        var text = Regex("\\[动作[:：]\\s*([^\\]\\n]+?)\\s*\\]").replace(raw) { match ->
            tagged = true
            val name = match.groupValues[1].trim()
            if (action.isEmpty() && actions != null && (0 until actions.length()).any { actions.optString(it) == name }) action = name
            ""
        }
        if (tagged) text = text.replace(Regex("[ \\t]+$", RegexOption.MULTILINE), "")
            .replace(Regex("\\n{2,}"), "\n").trim()
        var image = ""
        val tag = Regex("\\[图[:：]\\s*([^\\]\\n]+?)\\s*\\]\\s*$").find(text)
        if (tag != null) {
            val name = tag.groupValues[1].trim()
            val pool = config.optJSONArray("chatMemes")
            val body = text.substring(0, tag.range.first).trim()
            if (body.isNotBlank() && pool != null && (0 until pool.length()).any { pool.getJSONObject(it).optString("name") == name }) {
                text = body; image = name
            }
        }
        return JSONObject().put("text", text).put("image", image).put("action", action)
    }

    fun streamingText(raw: String): String = raw
        .replace(Regex("\\[(?:动作|图)[:：][^\\]\\n]*\\]"), "")
        .replace(Regex("\\[(?:动(?:作)?|图)(?:[:：][^\\]\\n]*)?$"), "")
        .trimEnd()
}
