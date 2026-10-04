package com.squareify.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * A full-screen editor: ✕, the title and undo / redo on top, the controls scrolling below. It
 * can't be swiped away by accident; ✕ and the back button ask before throwing away [unsaved] work,
 * offering to [onSave] it instead.
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
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp),
                    content = content,
                )
            }
        }

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
