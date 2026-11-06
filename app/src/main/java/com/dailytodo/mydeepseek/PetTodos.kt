package com.dailytodo.mydeepseek

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Serial atomic writes, independent of the pet service and of API credentials. */
class PetTodos private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = PetSecureStore(app)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<(JSONObject) -> Unit>()
    private val reminderObservers = linkedSetOf<(String) -> Unit>()
    private var diskRecords: List<JSONObject>? = null // IO executor only.
    private var records = emptyList<JSONObject>()
    private var ready = false
    private var error = ""
    private var reminderError = ""
    init {
        io.execute {
            try {
                diskRecords = store.todos()
                val result = diskRecords!!
                main.post { records = result; ready = true; emit(); schedule() }
            } catch (_: Exception) {
                main.post { ready = true; error = "本机待办未能读取。为保护已有记录，暂不写入；请保留应用数据并检查手机存储。"; emit() }
            }
        }
    }
    fun state(): JSONObject {
        val list = JSONArray(); TodoRules.sorted(records).forEach { list.put(JSONObject(it.toString())) }
        return JSONObject().put("ready", ready).put("error", error).put("reminderError", reminderError).put("items", list)
    }
    fun observe(callback: (JSONObject) -> Unit) { observers.add(callback); callback(state()) }
    fun remove(callback: (JSONObject) -> Unit) { observers.remove(callback) }
    fun observeReminders(callback: (String) -> Unit) { reminderObservers.add(callback) }
    fun removeReminders(callback: (String) -> Unit) { reminderObservers.remove(callback) }
    private fun emit() { observers.toList().forEach { it(state()) } }
    private fun schedule() {
        val problem = if (runCatching { TodoAlarms.schedule(app, records) }.isFailure)
            "系统暂时未能安排提醒，请检查权限后重新打开此页。" else ""
        if (problem != reminderError) { reminderError = problem; emit() }
    }
    private fun change(callback: (Boolean, String) -> Unit, mutation: (List<JSONObject>) -> List<JSONObject>) {
        io.execute {
            try {
                val previous = diskRecords ?: throw IllegalStateException("本机待办尚未准备好，请稍后重试。")
                val next = mutation(previous.map { JSONObject(it.toString()) })
                require(next.size <= TodoRules.MAX_ITEMS) { "本机最多保存 1000 项待办，请先清理已完成事项。" }
                store.saveTodos(next); diskRecords = next
                main.post { records = next; error = ""; emit(); schedule(); callback(true, "已保存到手机。") }
            } catch (failure: Exception) {
                val message = if (failure is IllegalArgumentException || failure is IllegalStateException)
                    failure.message.orEmpty() else "待办没有保存成功，请检查手机存储后重试。"
                main.post { callback(false, message) }
            }
        }
    }
    fun add(rows: JSONArray, callback: (Boolean, String) -> Unit) {
        change(callback) { previous ->
            require(rows.length() in 1..10) { "一次最多添加 10 项待办。" }
            val now = System.currentTimeMillis()
            previous + (0 until rows.length()).map { TodoRules.create(rows.getJSONObject(it), now) }
        }
    }
    fun update(id: String, patch: JSONObject, callback: (Boolean, String) -> Unit) {
        change({ ok, message -> if (ok) TodoAlarms.cancelNotification(app, id); callback(ok, message) }) { previous ->
            require(previous.any { it.optString("id") == id }) { "这项待办已不存在。" }
            previous.map { if (it.optString("id") == id) TodoRules.update(it, patch, System.currentTimeMillis()) else it }
        }
    }
    fun delete(id: String, callback: (Boolean, String) -> Unit) {
        change({ ok, message -> if (ok) TodoAlarms.cancelNotification(app, id); callback(ok, message) }) { previous ->
            previous.filter { it.optString("id") != id }
        }
    }
    fun reschedule(done: () -> Unit = {}) {
        io.execute { main.post { schedule(); done() } }
    }
    fun deliver(done: () -> Unit) {
        io.execute {
            try {
                val previous = diskRecords
                if (previous == null) { main.post { done() }; return@execute }
                val now = System.currentTimeMillis(); val due = TodoRules.dueNow(previous, now)
                if (TodoAlarms.notifyDue(app, due)) {
                    val ids = due.map { it.getString("id") }.toSet()
                    val next = previous.map { JSONObject(it.toString()).also { row ->
                        if (row.optString("id") in ids) row.put("lastRemindedAt", now)
                    } }
                    store.saveTodos(next); diskRecords = next
                    main.post {
                        records = next; emit(); schedule()
                        val text = due.first().optString("text") + if (due.size > 1) " 等 ${due.size} 项" else ""
                        reminderObservers.toList().forEach { it(text) }; done()
                    }
                } else main.post { if (due.isEmpty()) schedule(); done() }
            } catch (_: Exception) { main.post { done() } }
        }
    }
    companion object {
        @Volatile private var instance: PetTodos? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: PetTodos(context).also { instance = it } }
    }
}
