package com.squareify.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit

/** Originals the app moved to the phone's trash, with restore and delete-now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyDeletedSheet(
    entries: List<TrashedOriginal>,
    onRestore: (List<TrashedOriginal>) -> Unit,
    onDeleteForever: (List<TrashedOriginal>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Recently deleted", style = MaterialTheme.typography.titleMedium)
            Text(
                "Originals moved to the phone's trash after their squared version was saved. " +
                    "Android deletes them for good 30 days after they were moved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text(
                    "Nothing here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onRestore(entries) }) { Text("Restore all") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onDeleteForever(entries) }) { Text("Delete all now") }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(entries, key = { it.uri.toString() }) { entry ->
                        TrashRow(
                            entry = entry,
                            onRestore = { onRestore(listOf(entry)) },
                            onDeleteForever = { onDeleteForever(listOf(entry)) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TrashRow(entry: TrashedOriginal, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val thumbnail = remember(entry.thumbnailPath) { entry.thumbnailPath?.let { BitmapFactory.decodeFile(it) } }
    // Whole days left, rounded down like Samsung's Recycle Bin shows it.
    val daysLeft = ((entry.expiresAt - System.currentTimeMillis()) / TimeUnit.DAYS.toMillis(1))
        .toInt().coerceAtLeast(0)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (entry.isVideo) "Video" else "Photo"} · gone for good in $daysLeft day${if (daysLeft == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRestore) { Text("Restore") }
        IconButton(onClick = onDeleteForever) {
            Icon(Icons.Default.DeleteForever, contentDescription = "Delete now")
        }
    }
}
