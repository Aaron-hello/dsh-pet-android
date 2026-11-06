package com.dailytodo.mydeepseek

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class TodoRulesTest {
    private val now = 1800000000000L
    private fun item(text: String = "买药", due: Long? = now + 10000) =
        TodoRules.create(JSONObject().put("text", text).put("dueAt", due ?: JSONObject.NULL), now)
    @Test fun sameCodePointLimitAsUpstreamAndNoInvalidDates() {
        assertEquals("买药", TodoRules.text("  买药  "))
        assertEquals(400, TodoRules.text("😀".repeat(200)).length)
        assertThrows(IllegalArgumentException::class.java) { TodoRules.text("😀".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) { TodoRules.text(" \n ") }
        assertThrows(IllegalArgumentException::class.java) { item(due = now - 1) }
        assertThrows(IllegalArgumentException::class.java) { TodoRules.due(JSONObject().put("dueAt", "明天")) }
    }
    @Test fun completingReopeningAndReschedulingMaintainReminderState() {
        val first = item().put("lastRemindedAt", now)
        val edited = TodoRules.update(first, JSONObject().put("text", "买药和棉签"), now + 10)
        assertEquals(now, edited.getLong("lastRemindedAt"))
        val completed = TodoRules.update(edited, JSONObject().put("done", true), now + 20)
        assertEquals(now + 20, completed.getLong("completedAt"))
        assertFalse(TodoRules.needsReminder(completed))
        val reopened = TodoRules.update(completed, JSONObject().put("done", false), now + 30)
        assertTrue(reopened.isNull("lastRemindedAt"))
        val rescheduled = TodoRules.update(first, JSONObject().put("dueAt", now + 20000), now + 40)
        assertTrue(rescheduled.isNull("lastRemindedAt"))
        assertEquals(now + 20000, rescheduled.getLong("dueAt"))
    }
    @Test fun listOrderMatchesOriginalPendingDueThenRecentlyCompleted() {
        val a = item("无期限", null)
        val b = item("晚一点", now + 2000)
        val c = item("先做", now + 1000)
        val d = TodoRules.update(item("做完了"), JSONObject().put("done", true), now + 10)
        assertEquals(listOf("先做", "晚一点", "无期限", "做完了"), TodoRules.sorted(listOf(a,d,b,c)).map { it.getString("text") })
    }
    @Test fun onlyUnremindedDueItemsTriggerAndConfirmedCompletedItemsDoNot() {
        val due = item().put("dueAt", now - 100)
        val done = JSONObject(due.toString()).put("done", true)
        val sent = JSONObject(due.toString()).put("lastRemindedAt", now - 10)
        assertEquals(1, TodoRules.dueNow(listOf(due, done, sent, item(), item(due=null)), now).size)
    }
    @Test fun analysisUsesPhoneClockZoneAndExplicitJsonWithoutConversationHistory() {
        val time = ZonedDateTime.parse("2026-11-03T10:00:00+08:00[Asia/Shanghai]")
        val body = TodoRules.analysisBody("明天下午三点买药", time)
        assertEquals(2, body.getJSONArray("messages").length())
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("Asia/Shanghai"))
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("2026-11-03T10:00"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }
    @Test fun ambiguousInvalidOrPastAiTimesRemainUnscheduledDrafts() {
        val rows = TodoRules.drafts("""{"todos":[{"text":"买药","dueAt":null,"note":"缺少具体时间"},{"text":"打电话","dueAt":"2020-01-01T10:00:00+08:00"},{"text":"看书","dueAt":"明天"}]}""", now)
        assertEquals(3, rows.length())
        for (i in 0 until rows.length()) assertTrue(rows.getJSONObject(i).isNull("dueAt"))
        assertTrue(rows.getJSONObject(1).getString("note").contains("手动"))
        assertThrows(Exception::class.java) { TodoRules.drafts("""{"todos":[{"text":""}]}""", now) }
    }
    @Test fun validOffsetTimeIsAnExactInstantAndDraftsHaveNoSavedRecordIdentity() {
        val at = ZonedDateTime.parse("2026-11-03T10:00:00+08:00[Asia/Shanghai]").toInstant().toEpochMilli()
        val result = TodoRules.drafts("""{"todos":[{"text":"买药","dueAt":"2026-11-04T15:00:00+08:00"}]}""", at).getJSONObject(0)
        assertEquals(ZonedDateTime.parse("2026-11-04T15:00:00+08:00").toInstant().toEpochMilli(), result.getLong("dueAt"))
        assertFalse(result.has("id"));assertFalse(result.has("done"))
    }
}
