package com.dailytodo.mydeepseek

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Runs only while the user-started overlay is visible. No alarms or wake locks. */
class PetCompanion(private val api: PetApi, private val reply: (String, JSONObject) -> Unit) {
    private data class State(var sleeping: Boolean = false, var busy: Boolean = false, var due: Long = 0)
    private val states = linkedMapOf<String, State>()
    private val handler = Handler(Looper.getMainLooper())
    private var screenOn = true
    private var closed = false
    private val tick = Runnable { runDue() }
    private val observer: (JSONObject) -> Unit = { refresh(it) }
    init { api.observeAccount(observer) }

    fun ready(id: String) {
        states[id] = State(due = System.currentTimeMillis() + api.companionPeriod(false))
        refresh(api.accountState())
    }
    fun interaction(id: String, sleeping: Boolean, busy: Boolean) {
        val state = states[id] ?: return
        val now = System.currentTimeMillis()
        val shouldCancel = (busy && !state.busy) || state.sleeping != sleeping
        if (state.sleeping != sleeping) state.due = now + api.companionPeriod(sleeping)
        if (state.busy && !busy) state.due = maxOf(state.due, now + 1000)
        state.sleeping = sleeping; state.busy = busy
        if (shouldCancel) api.cancelLine()
        refresh(api.accountState())
    }
    fun screen(visible: Boolean) {
        screenOn = visible
        if (!visible) api.cancelLine()
        // Screen-off time never accrues pending automatic requests.
        states.values.forEach { it.due = System.currentTimeMillis() + api.companionPeriod(it.sleeping) }
        refresh(api.accountState())
    }
    private fun refresh(account: JSONObject) {
        handler.removeCallbacks(tick)
        if (closed || !screenOn || !account.optBoolean("ready") || !account.optBoolean("configured") ||
            account.optBoolean("connecting") || account.optBoolean("todoBusy") || !account.isNull("activePet")) return
        val options = account.optJSONObject("companion") ?: return
        if (options.optBoolean("busy") || options.optString("paused").isNotBlank()) return
        val now = System.currentTimeMillis()
        val next = states.values.filter { !it.busy && options.optBoolean(if (it.sleeping) "dream" else "whisper") }
            .minOfOrNull { it.due } ?: return
        handler.postDelayed(tick, maxOf(1, next - now, api.nextAutomaticSlot() - now))
    }
    private fun runDue() {
        val now = System.currentTimeMillis()
        val candidate = states.entries.firstOrNull { !it.value.busy && it.value.due <= now && api.canGenerateAutomatically(it.value.sleeping) }
        if (candidate != null) {
            val state = candidate.value
            state.due = now + api.companionPeriod(state.sleeping)
            val dream = state.sleeping
            api.generateLine(candidate.key, dream, automatic = true) { result, _ ->
                if (!closed && screenOn && result != null && states[candidate.key]?.sleeping == dream)
                    reply(candidate.key, result)
            }
        }
        refresh(api.accountState())
    }
    fun close() {
        closed = true; handler.removeCallbacksAndMessages(null); api.removeAccount(observer)
        api.cancelLine(); states.clear()
    }
}
