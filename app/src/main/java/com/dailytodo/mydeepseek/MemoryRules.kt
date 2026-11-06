package com.dailytodo.mydeepseek

object MemoryRules {
    const val HOUR = 3600000L
    fun ready(enabled: Boolean, paused: Boolean, pending: Int) = enabled && !paused && pending >= 10
    fun recent(times: List<Long>, now: Long) = times.filter { it > now - HOUR }
    fun delay(times: List<Long>, now: Long): Long {
        val recent = recent(times, now)
        return if (recent.size < 3) 0 else (recent.min() + HOUR - now).coerceAtLeast(0)
    }
    fun safe(text: String) = text.isNotBlank() && text.length <= 160 &&
        !Regex("(?i)sk-[a-z0-9_-]{8,}|密码|口令|验证码|银行卡号|api.?key|密钥").containsMatchIn(text)
}
