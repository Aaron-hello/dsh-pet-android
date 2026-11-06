package com.dailytodo.mydeepseek

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.crypto.KeyGenerator
import java.util.concurrent.CancellationException

class DeepSeekProtocolTest {
    private val fixtureKey = "sk-fixture-not-a-real-key"
    private val balanceFixture = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.3456","granted_balance":"2.0000","topped_up_balance":"10.3456"}]}"""
    private fun record(role: String, content: String, status: String = "complete") =
        JSONObject().put("role", role).put("content", content).put("status", status)

    @Test fun contextRetainsRecentCompleteExchanges() {
        val records = (0..9).flatMap { listOf(record("user", "question-$it"), record("assistant", "answer-$it")) } +
            listOf(record("user", "interrupted"), record("assistant", "partial", "stopped"))
        val body = DeepSeekProtocol.body("original persona", records, "new question")
        val messages = body.getJSONArray("messages")
        assertEquals(18, messages.length())
        assertEquals("original persona", messages.getJSONObject(0).getString("content"))
        assertEquals("question-2", messages.getJSONObject(1).getString("content"))
        assertEquals("new question", messages.getJSONObject(17).getString("content"))
        assertFalse(messages.toString().contains("partial"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(800, body.getInt("max_tokens"))
        assertTrue(body.getBoolean("stream"))
    }
    @Test fun contextAndNewInputAreBounded() {
        val records = listOf(record("user", "a".repeat(9000)), record("assistant", "b".repeat(9000)))
        assertEquals(2, DeepSeekProtocol.body("persona", records, "hello").getJSONArray("messages").length())
        assertThrows(IllegalArgumentException::class.java) {
            DeepSeekProtocol.body("persona", emptyList(), "a".repeat(4001))
        }
    }
    @Test fun sseJoinsDataAndHidesReasoning() {
        val text = StringBuilder()
        val decoder = DeepSeekProtocol.SseDecoder { text.append(it) }
        listOf(": keepalive", "", """data: {"choices":[{"delta":{"reasoning_content":"hidden"}}]}""", "",
            """data: {"choices":[""", """data: {"delta":{"content":"你好"}}]}""", "",
            """data: {"choices":[{"delta":{"content":"，主人"},"finish_reason":"stop"}]}""", "",
            "data: [DONE]", "").forEach(decoder::line)
        assertEquals("你好，主人", text.toString())
        assertEquals("stop", decoder.finishReason)
        assertTrue(decoder.done)
    }
    @Test fun balancesKeepPrecisionAndRealNegativeAmounts() {
        val value = DeepSeekProtocol.balance(JSONObject(balanceFixture))
        assertEquals("12.3456", value.getJSONArray("rows").getJSONObject(0).getString("total_balance"))
        val negative = JSONObject(balanceFixture).apply {
            put("is_available", false)
            getJSONArray("balance_infos").getJSONObject(0).put("total_balance", "-0.01")
        }
        assertEquals("-0.01", DeepSeekProtocol.balance(negative).getJSONArray("rows").getJSONObject(0).getString("total_balance"))
        assertThrows(Exception::class.java) { DeepSeekProtocol.balance(JSONObject("""{"error":"network failure"}""")) }
    }
    @Test fun balanceUsesOfficialHttpsAndHeaderOnlyAuthentication() {
        lateinit var connection: FakeConnection
        val transport = DeepSeekTransport { url -> FakeConnection(url, 200, balanceFixture).also { connection = it } }
        val value = transport.balance(fixtureKey, ApiCall())
        assertEquals("https://api.deepseek.com/user/balance", connection.url.toString())
        assertFalse(connection.url.toString().contains(fixtureKey))
        assertEquals("Bearer $fixtureKey", connection.headers["Authorization"])
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("GET", connection.requestMethod)
        assertTrue(connection.disconnected)
        assertTrue(value.getBoolean("available"))
    }
    @Test fun streamingUsesOnePostAndErrorsNeverAutoRetry() {
        lateinit var connection: FakeConnection
        var attempts = 0
        val sse = """data: {"choices":[{"delta":{"content":"嗨"},"finish_reason":"stop"}]}

data: [DONE]

"""
        val transport = DeepSeekTransport { url ->
            attempts++; FakeConnection(url, 200, sse).also { connection = it }
        }
        val output = StringBuilder()
        val finish = transport.chat(fixtureKey, DeepSeekProtocol.body("persona", emptyList(), "hello"), ApiCall()) { output.append(it) }
        assertEquals("嗨", output.toString()); assertEquals("stop", finish); assertEquals(1, attempts)
        assertEquals("/chat/completions", connection.url.path)
        assertEquals("POST", connection.requestMethod)
        assertEquals("deepseek-flash", JSONObject(connection.sent.toString("UTF-8")).getString("model"))
        val denied = DeepSeekTransport { url -> attempts++; FakeConnection(url, 401, "{}") }
        assertThrows(DeepSeekTransport.ApiFailure::class.java) { denied.balance(fixtureKey, ApiCall()) }
        assertEquals(2, attempts)
    }
    @Test fun interruptedStreamIsNotReportedAsComplete() {
        val sse = """data: {"choices":[{"delta":{"content":"半句"}}]}

"""
        val transport = DeepSeekTransport { url -> FakeConnection(url, 200, sse) }
        assertThrows(DeepSeekTransport.ApiFailure::class.java) {
            transport.chat(fixtureKey, JSONObject(), ApiCall()) {}
        }
    }
    @Test fun cancellationAndHeaderInjection() {
        var count = 0
        val transport = DeepSeekTransport { url -> count++; FakeConnection(url, 200, balanceFixture) }
        val call = ApiCall().apply { cancel() }
        assertThrows(CancellationException::class.java) { transport.balance(fixtureKey, call) }
        assertEquals(0, count)
        assertFalse(DeepSeekProtocol.validKey("sk-test\r\nInjected: true"))
        assertFalse(DeepSeekProtocol.validPet("../android-0"))
    }
    @Test fun encryptionRoundTripRandomNonceAndTamperDetection() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val data = "private key and chat text".toByteArray()
        val first = SecretEnvelope.encrypt(key, data)
        val second = SecretEnvelope.encrypt(key, data)
        assertFalse(first.contentEquals(second))
        assertFalse(String(first).contains("private key"))
        assertArrayEquals(data, SecretEnvelope.decrypt(key, first))
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { SecretEnvelope.decrypt(key, first) }
    }
    private class FakeConnection(url: URL, private val status: Int, private val data: String) : HttpsURLConnection(url) {
        val headers = mutableMapOf<String, String>()
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(data.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = sent
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
