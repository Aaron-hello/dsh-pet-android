package com.dailytodo.mydeepseek

/** Actual service/renderer state shared with the control page, never a persisted
 * "running" flag that can outlive the Android process. All access is on main. */
object PetRuntime {
    data class State(val phase: String = "idle", val message: String = "", val detail: String = "")
    var state = State()
        private set
    var activeSize: Int = 200
    var activeCount: Int = 1
    private val observers = linkedSetOf<(State) -> Unit>()
    fun update(phase: String, message: String = "", detail: String = "") {
        state = State(phase, message, detail)
        observers.toList().forEach { it(state) }
    }
    fun observe(callback: (State) -> Unit) { observers.add(callback); callback(state) }
    fun remove(callback: (State) -> Unit) { observers.remove(callback) }
}
