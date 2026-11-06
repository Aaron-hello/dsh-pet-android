package com.dailytodo.mydeepseek

/** A cancellable credential write. All methods run on the same serial disk queue. */
class PendingValueWrite<T>(private val read: () -> T?, private val write: (T?) -> Unit) {
    private var previous: T? = null
    private var touched = false
    fun commit(value: T) {
        check(!touched)
        previous = read()
        touched = true
        try { write(value) }
        catch (failure: Exception) {
            try { rollback() } catch (undo: Exception) { failure.addSuppressed(undo) }
            throw failure
        }
    }
    fun rollback() {
        if (!touched) return
        write(previous)
        touched = false; previous = null
    }
}
