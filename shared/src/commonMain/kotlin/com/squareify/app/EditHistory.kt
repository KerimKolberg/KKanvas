package com.squareify.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.TimeSource

/**
 * Undo and redo for an editor's state. Changes that come in quick succession (a slider or a photo
 * being dragged) make one step, so undo takes back the whole drag.
 */
class EditHistory<T>(initial: T, private val clock: () -> Long = ::monotonicMillis) {
    var value by mutableStateOf(initial)
        private set
    private val undos = mutableStateListOf<T>()
    private val redos = mutableStateListOf<T>()
    /** When the last recorded change came in; null before the first one and after an undo or redo. */
    private var lastChange: Long? = null

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /** Moves to [next]; with [record] false it isn't a step of its own (e.g. faces found in the background). */
    fun set(next: T, record: Boolean = true) {
        if (next == value) return
        if (record) {
            val now = clock()
            val last = lastChange
            if (last == null || now - last > BURST_MS) {
                undos.add(value)
                if (undos.size > MAX_STEPS) undos.removeAt(0)
            }
            lastChange = now
            redos.clear()
        }
        value = next
    }

    fun undo() {
        if (undos.isEmpty()) return
        redos.add(value)
        value = undos.removeAt(undos.lastIndex)
        lastChange = null
    }

    fun redo() {
        if (redos.isEmpty()) return
        undos.add(value)
        value = redos.removeAt(redos.lastIndex)
        lastChange = null
    }

    /** A property for one part of the state, so editors keep reading and assigning it as before. */
    fun <P> part(get: (T) -> P, put: (T, P) -> T): ReadWriteProperty<Any?, P> = object : ReadWriteProperty<Any?, P> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): P = get(value)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: P) = set(put(this@EditHistory.value, value))
    }

    private companion object {
        /** Changes closer together than this belong to the same step. */
        const val BURST_MS = 700L
        const val MAX_STEPS = 100
    }
}

private val start = TimeSource.Monotonic.markNow()

private fun monotonicMillis(): Long = start.elapsedNow().inWholeMilliseconds

/** Undo and redo buttons for an editor's title row. */
@Composable
fun UndoRedoButtons(history: EditHistory<*>) {
    IconButton(onClick = history::undo, enabled = history.canUndo) {
        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
    }
    IconButton(onClick = history::redo, enabled = history.canRedo) {
        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
    }
}
