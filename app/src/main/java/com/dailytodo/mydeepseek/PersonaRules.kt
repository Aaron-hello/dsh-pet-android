package com.dailytodo.mydeepseek

object PersonaRules {
    const val MAX_PROMPT = 10_000
    fun length(value: String) = value.codePointCount(0, value.length)
    fun valid(value: String) = value.isNotBlank() && length(value) <= MAX_PROMPT
    fun bounded(value: String): String = if (length(value) <= MAX_PROMPT) value
        else value.substring(0, value.offsetByCodePoints(0, MAX_PROMPT))
}
