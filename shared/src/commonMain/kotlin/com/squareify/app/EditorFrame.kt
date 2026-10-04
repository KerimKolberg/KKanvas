package com.squareify.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * A full-screen editor: ✕, the title and undo / redo on top, the [preview] and the controls
 * scrolling below (on big windows side by side). It can't be swiped away by accident; ✕ and the
 * back button ask before throwing away [unsaved] work, offering to [onSave] it instead. Ctrl+Z / Ctrl+Y
 * undo and redo, Esc closes.
 */
@Composable
fun EditorFrame(
    title: String,
    history: EditHistory<*>?,
    unsaved: Boolean,
    /** What would be lost, for the question: "collage", "carousel", "slides", "changes". */
    what: String,
    onSave: () -> Unit,
    onClose: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    /** What's being edited, drawn above the controls, or on big windows beside them. */
    preview: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    fun requestClose() {
        if (unsaved) asking = true else onClose()
    }

    Dialog(
        onDismissRequest = ::requestClose, // the back button
        properties = editorDialogProperties(),
    ) {
        val focus = remember { FocusRequester() }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Keyboard (Windows): Ctrl+Z / Ctrl+Y undo and redo, Esc closes.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed && history != null -> {
                            history.undo()
                            true
                        }
                        event.isCtrlPressed && (event.key == Key.Y || (event.key == Key.Z && event.isShiftPressed)) && history != null -> {
                            history.redo()
                            true
                        }
                        event.key == Key.Escape -> {
                            requestClose()
                            true
                        }
                        else -> false
                    }
                }
                .focusRequester(focus)
                .focusable(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = ::requestClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (history != null) UndoRedoButtons(history)
                }
                HorizontalDivider()
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    if (preview != null && maxWidth >= WIDE_LAYOUT) {
                        // A big window: the preview fills the left, the controls scroll on the right.
                        Row(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                CompositionLocalProvider(LocalWideEditor provides true) { preview() }
                            }
                            VerticalDivider()
                            Column(
                                modifier = Modifier
                                    .width(CONTROLS_WIDTH)
                                    .fillMaxHeight()
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 16.dp),
                                content = content,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 16.dp),
                        ) {
                            preview?.invoke(this)
                            content()
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }

        if (asking) {
            AlertDialog(
                onDismissRequest = { asking = false },
                title = { Text("Close without saving?") },
                text = { Text("Your $what will be lost if you close now.") },
                confirmButton = {
                    TextButton(onClick = {
                        asking = false
                        onSave()
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            asking = false
                            onClose()
                        }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { asking = false }) { Text("Keep editing") }
                    }
                },
            )
        }
    }
}

/** True inside an editor's preview when it has the left of a big window to itself. */
val LocalWideEditor = compositionLocalOf { false }

private val CONTROLS_WIDTH = 520.dp
