package com.squareify.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squareify.app.processing.asImage

@Composable
fun MediaCard(
    item: MediaItem,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onRenderVideo: () -> Unit,
    onShare: () -> Unit,
    /** Null where there's no Instagram app to post to (Windows). */
    onPostToInstagram: (() -> Unit)?,
    onRetry: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
            )
            // Long-press starts selecting; while selecting, a tap toggles instead of opening.
            .combinedClickable(
                onClickLabel = if (selectionMode) "Select" else "Open preview",
                onLongClickLabel = "Select",
                onClick = if (selectionMode) onToggleSelected else onOpen,
                onLongClick = onToggleSelected,
            )
    ) {
        item.thumbnail?.let {
            Image(
                bitmap = it.asImage(),
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                // Fit, not crop, so tall formats show their padding.
                contentScale = ContentScale.Fit,
            )
        }

        val panorama = item.panorama
        val carousel = item.carousel
        val label = when {
            item.collage != null -> if (item.isVideo) "Video collage" else "Collage"
            panorama != null -> "${panorama.slides} slides"
            carousel != null -> "Carousel · ${carousel.slides} slides"
            item.isVideo -> "Video"
            else -> null
        }
        if (label != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (item.isVideo && !item.isRendered && !item.isProcessing && item.error == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                FilledIconButton(onClick = onRenderVideo) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Render")
                }
            }
        }

        if (item.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    if (item.isVideo) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(item.progress * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        val error = item.error
        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = 0.75f))
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        error,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(
                        onClick = onRetry,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
            }
        }

        val warning = item.warning
        if (warning != null && !item.isProcessing) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF59E0B))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color.Black,
                )
                Spacer(Modifier.width(3.dp))
                Text(warning, color = Color.Black, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (item.originalTrashed) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Original moved to the trash",
                    modifier = Modifier.size(16.dp),
                    tint = Color.White,
                )
            }
        }

        if (selectionMode) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.35f)),
            )
        } else {
            CardActions(item, onEdit, onRemove, onShare, onPostToInstagram, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun CardActions(
    item: MediaItem,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit,
    /** Null where there's no Instagram app to post to (Windows). */
    onPostToInstagram: (() -> Unit)?,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Without the original there's nothing to re-edit.
        if (!item.originalTrashed) {
            FilledIconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "Edit", modifier = Modifier.size(18.dp))
            }
        }
        if (item.isRendered) {
            // Green = saved; tapping it offers posting to Instagram or sharing elsewhere.
            val savedTo = when {
                isDesktop -> if (item.isVideo) "Videos\\kkanvas" else "Pictures\\kkanvas"
                else -> if (item.isVideo) "Movies/kkanvas" else "Pictures/kkanvas"
            }
            var shareMenu by remember { mutableStateOf(false) }
            Box {
                FilledIconButton(
                    onClick = { shareMenu = true },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF16A34A),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Saved to $savedTo. Share",
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                    if (onPostToInstagram != null) {
                        DropdownMenuItem(
                            text = { Text("Post to Instagram") },
                            leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                            onClick = {
                                shareMenu = false
                                onPostToInstagram()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share…") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            shareMenu = false
                            onShare()
                        },
                    )
                }
            }
        }
        FilledIconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
        }
    }
}
