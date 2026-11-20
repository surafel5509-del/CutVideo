package com.cutvideo.editor.domain.history

import com.cutvideo.editor.domain.model.ProjectDoc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot based undo/redo.
 *
 * A [ProjectDoc] is a few kilobytes of plain data, so whole-document snapshots are cheap, perfectly
 * consistent and impossible to get "half undone" like command-inverse implementations. The history
 * is bounded so long sessions cannot grow without limit.
 */
class UndoRedoManager(private val limit: Int = 60) {

    private data class Entry(val label: String, val doc: ProjectDoc)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _undoLabel = MutableStateFlow<String?>(null)
    val undoLabel: StateFlow<String?> = _undoLabel.asStateFlow()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        publish()
    }

    /** Call *before* mutating [current] so the pre-edit state can be restored. */
    fun record(label: String, current: ProjectDoc) {
        undoStack.addLast(Entry(label, current))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        publish()
    }

    fun undo(current: ProjectDoc): ProjectDoc? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(Entry(entry.label, current))
        publish()
        return entry.doc
    }

    fun redo(current: ProjectDoc): ProjectDoc? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(Entry(entry.label, current))
        publish()
        return entry.doc
    }

    private fun publish() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _undoLabel.value = undoStack.lastOrNull()?.label
    }
}
