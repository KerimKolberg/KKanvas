package com.squareify.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide video render progress, keyed by [MediaItem.id].
 * Written by [RenderService], observed by the UI.
 */
object RenderStateHolder {

    private val _states = MutableStateFlow<Map<String, RenderState>>(emptyMap())
    val states: StateFlow<Map<String, RenderState>> = _states.asStateFlow()

    fun markQueued(id: String) {
        _states.update { it + (id to RenderState(isProcessing = true)) }
    }

    fun markStarted(id: String) {
        _states.update {
            it + (id to (it[id] ?: RenderState()).copy(isProcessing = true, progress = 0f, error = null))
        }
    }

    fun updateProgress(id: String, progress: Float) {
        _states.update { it + (id to (it[id] ?: RenderState()).copy(progress = progress)) }
    }

    fun markComplete(id: String, outputUri: MediaUri?, warning: String? = null) {
        _states.update {
            it + (id to RenderState(
                isProcessing = false,
                progress = 1f,
                isRendered = true,
                outputUri = outputUri,
                warning = warning,
            ))
        }
    }

    fun markFailed(id: String, message: String) {
        _states.update { it + (id to RenderState(isProcessing = false, error = message)) }
    }

    fun clear(id: String) {
        _states.update { it - id }
    }
}
