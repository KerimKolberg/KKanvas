package com.squareify.app

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide video render progress, keyed by [MediaItem.id].
 * Written by [RenderService], observed by the UI.
 */
object RenderStateHolder {

    data class State(
        val isProcessing: Boolean = false,
        val progress: Float = 0f,
        val isRendered: Boolean = false,
        val outputUri: Uri? = null,
        val error: String? = null,
        val warning: String? = null,
    )

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun markQueued(id: String) {
        _states.update { it + (id to State(isProcessing = true)) }
    }

    fun markStarted(id: String) {
        _states.update {
            it + (id to (it[id] ?: State()).copy(isProcessing = true, progress = 0f, error = null))
        }
    }

    fun updateProgress(id: String, progress: Float) {
        _states.update { it + (id to (it[id] ?: State()).copy(progress = progress)) }
    }

    fun markComplete(id: String, outputUri: Uri?, warning: String? = null) {
        _states.update {
            it + (id to State(
                isProcessing = false,
                progress = 1f,
                isRendered = true,
                outputUri = outputUri,
                warning = warning,
            ))
        }
    }

    fun markFailed(id: String, message: String) {
        _states.update { it + (id to State(isProcessing = false, error = message)) }
    }

    fun clear(id: String) {
        _states.update { it - id }
    }
}
