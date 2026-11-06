package com.dailytodo.mydeepseek

import org.json.JSONArray
import org.json.JSONObject

/** Pure wire rules: fixed official endpoints, bounded context and SSE decoding. */
object DeepSeekProtocol {
    const val MODEL = "deepseek-flash"
    const val MAX_INPUT = 4000
    const val MAX_REPLY = 16000
    const val CONTEXT_MESSAGES = 16
    const val CONTEXT_CHARS = 16000
    const val BILLING_NOTICE = "接入后，使用 API 聊天会按 DeepSeek 官方规则计费，费用从密钥对应的 API 账户额度中扣除。发送的内容、携带的最近对话和模型回复都可能计入用量。实际费用以官方价格和账单为准。"

    fun validKey(key: String) = key.length in 12..512 && key.all { it.code in 33..126 }
    fun validPet(id: String) = id.matches(Regex("android-[0-2]"))

    fun body(persona: String, records: List<JSONObject>, userText: String): JSONObject {
        require(userText.isNotBlank() && userText.length <= MAX_INPUT)
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", persona))
        // Only completed exchanges become context. Failed/stopped fragments remain
        // visible in history but are not mistaken for completed assistant answers.
        val pairs = mutableListOf<List<JSONObject>>()
        var previous: JSONObject? = null
        for (record in records) {
            if (record.optString("role") == "user") previous = record
            else if (record.optString("role") == "assistant") {
                if (previous != null && record.optString("status") == "complete" &&
                    record.optString("content").isNotBlank()) pairs.add(listOf(previous, record))
                previous = null
            }
        }
        val chosen = mutableListOf<List<JSONObject>>()
        var chars = userText.length
        for (pair in pairs.asReversed()) {
            val length = pair.sumOf { it.optString("content").length }
            if (chosen.size * 2 >= CONTEXT_MESSAGES || chars + length > CONTEXT_CHARS) break
            chosen.add(pair); chars += length
        }
        for (pair in chosen.asReversed()) for (record in pair)
            messages.put(JSONObject().put("role", record.getString("role")).put("content", record.getString("content")))
        messages.put(JSONObject().put("role", "user").put("content", userText))
        return JSONObject().put("model", MODEL).put("messages", messages).put("stream", true)
            .put("thinking", JSONObject().put("type", "disabled")).put("max_tokens", 800)
    }

    fun balance(data: JSONObject): JSONObject {
        require(data.has("is_available")) { "余额格式不完整" }
        val source = data.getJSONArray("balance_infos")
        val rows = JSONArray()
        for (i in 0 until source.length()) {
            val row = source.getJSONObject(i)
            val currency = row.getString("currency")
            require(currency == "CNY" || currency == "USD")
            val item = JSONObject().put("currency", currency)
            for (field in listOf("total_balance", "granted_balance", "topped_up_balance")) {
                val value = row.getString(field)
                require(value.length <= 64)
                value.toBigDecimal()
                item.put(field, value)
            }
            rows.put(item)
        }
        return JSONObject().put("available", data.getBoolean("is_available")).put("rows", rows)
    }

    fun error(code: Int): String = when (code) {
        401, 403 -> "密钥无效、已失效或没有访问权限，请检查后重新添加。"
        402 -> "API 账户额度不足，请在 DeepSeek 平台查看余额。"
        429 -> "请求较频繁，请稍后手动重试。"
        in 500..599 -> "DeepSeek 暂时繁忙，请稍后手动重试。"
        else -> "DeepSeek 没有接受这次请求（$code），请稍后重试。"
    }

    class SseDecoder(private val onText: (String) -> Unit) {
        private val data = StringBuilder()
        var done = false
            private set
        var finishReason: String? = null
            private set
        fun line(line: String) {
            require(line.length <= 262144) { "响应数据过大" }
            if (done || line.startsWith(':')) return
            if (line.isEmpty()) { dispatch(); return }
            if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
                require(data.length <= 262144)
            }
        }
        fun end() { dispatch() }
        private fun dispatch() {
            if (data.isEmpty() || done) return
            val payload = data.toString(); data.clear()
            if (payload.trim() == "[DONE]") { done = true; return }
            val item = JSONObject(payload)
            if (item.has("error")) throw IllegalStateException("DeepSeek 返回了错误，请手动重试。")
            val choice = item.optJSONArray("choices")?.optJSONObject(0) ?: return
            val delta = choice.optJSONObject("delta")
            val text = delta?.optString("content") ?: ""
            if (text.isNotEmpty() && text != "null") onText(text)
            if (!choice.isNull("finish_reason") && choice.has("finish_reason"))
                finishReason = choice.optString("finish_reason")
        }
    }
}
