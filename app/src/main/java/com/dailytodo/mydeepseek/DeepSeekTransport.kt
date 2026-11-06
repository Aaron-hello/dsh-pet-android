package com.dailytodo.mydeepseek

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class ApiCall {
    val cancelled = AtomicBoolean(false)
    @Volatile var connection: HttpsURLConnection? = null
    fun cancel() { cancelled.set(true); connection?.disconnect() }
    fun check() { if (cancelled.get()) throw CancellationException() }
}

/** Only native code can attach credentials. No redirects, JS fetch or auto-retry. */
class DeepSeekTransport(
    private val connectionFactory: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }
) {
    private fun open(path: String, key: String, call: ApiCall): HttpsURLConnection {
        require(DeepSeekProtocol.validKey(key)); call.check()
        val connection = connectionFactory(URL("https://api.deepseek.com$path"))
        call.connection = connection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000; connection.readTimeout = 45000
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Accept", "application/json")
        call.check()
        return connection
    }
    fun balance(key: String, call: ApiCall): JSONObject {
        val connection = open("/user/balance", key, call)
        try {
            connection.requestMethod = "GET"
            val status = connection.responseCode
            call.check()
            if (status !in 200..299) throw ApiFailure(DeepSeekProtocol.error(status))
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { readBounded(it, 65536) }
            return DeepSeekProtocol.balance(JSONObject(text))
        } finally { connection.disconnect(); call.connection = null }
    }
    fun chat(key: String, body: JSONObject, call: ApiCall, onText: (String) -> Unit): String? {
        val connection = open("/chat/completions", key, call)
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            call.check()
            if (status !in 200..299) throw ApiFailure(DeepSeekProtocol.error(status))
            val decoder = DeepSeekProtocol.SseDecoder(onText)
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    call.check()
                    val line = reader.readLine() ?: break
                    decoder.line(line)
                    if (decoder.done) break
                }
            }
            call.check(); decoder.end()
            if (!decoder.done && decoder.finishReason == null) throw ApiFailure("连接中断，回复可能不完整。可手动重试。")
            return decoder.finishReason
        } finally { connection.disconnect(); call.connection = null }
    }
    private fun readBounded(reader: java.io.Reader, maximum: Int): String {
        val result = StringBuilder(); val buffer = CharArray(2048)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) return result.toString()
            result.append(buffer, 0, count)
            if (result.length > maximum) throw ApiFailure("响应数据过大，请稍后重试。")
        }
    }
    class ApiFailure(message: String) : Exception(message)
    companion object {
        fun message(error: Exception): String = when (error) {
            is ApiFailure -> error.message.orEmpty()
            is SocketTimeoutException -> "连接超时，请检查网络后手动重试。"
            is java.io.IOException -> "网络连接失败，请检查网络后手动重试。"
            else -> "这次没有成功完成，请稍后手动重试。"
        }
    }
}
