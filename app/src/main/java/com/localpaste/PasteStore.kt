package com.localpaste

import android.os.Handler
import android.os.Looper

/** In-memory pastes shared by the web server and the app screen. Newest first, capped at [MAX_PASTES]. */
object PasteStore {
    const val MAX_PASTES = 50

    class Paste(val id: Long, val text: String, val time: Long)

    private val pastes = ArrayDeque<Paste>()
    private var nextId = 1L
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread after any change, from the app or from the web. */
    var listener: (() -> Unit)? = null

    fun all(): List<Paste> = synchronized(this) { pastes.toList() }

    fun add(text: String): Paste {
        val paste = synchronized(this) {
            Paste(nextId++, text, System.currentTimeMillis()).also {
                pastes.addFirst(it)
                while (pastes.size > MAX_PASTES) pastes.removeLast()
            }
        }
        changed()
        return paste
    }

    fun delete(id: Long): Boolean {
        val removed = synchronized(this) { pastes.removeAll { it.id == id } }
        if (removed) changed()
        return removed
    }

    fun clear() {
        synchronized(this) { pastes.clear() }
        changed()
    }

    private fun changed() {
        main.post { listener?.invoke() }
    }
}
