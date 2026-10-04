package com.squareify.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.squareify.app.processing.asImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Swipe through the slides as they'll look on Instagram, before saving. [renderSlide] draws one
 * slide (from 0); [warnings] point out seams that won't look right.
 */
@Composable
fun SwipePreviewDialog(
    slides: Int,
    aspect: Float,
    renderSlide: (Int) -> PlatformBitmap,
    warnings: List<String>,
    onDismiss: () -> Unit,
) {
    val pager = rememberPagerState { slides }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Column(modifier = Modifier.align(Alignment.Center)) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                    val bitmap by produceState<PlatformBitmap?>(null, page) {
                        value = withContext(Dispatchers.Default) { renderSlide(page) }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect),
                        contentAlignment = Alignment.Center,
                    ) {
                        val image = bitmap
                        if (image == null) {
                            CircularProgressIndicator(color = Color.White)
                        } else {
                            Image(
                                image.asImage(),
                                contentDescription = "Slide ${page + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                // Instagram's dots under the picture.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    repeat(slides) { i ->
                        Box(
                            modifier = Modifier
                                .size(if (i == pager.currentPage) 7.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (i == pager.currentPage) Color(0xFF3897F0) else Color.White.copy(alpha = 0.4f)),
                        )
                    }
                }
                if (warnings.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF3A2E10))
                            .padding(12.dp),
                    ) {
                        warnings.forEach { warning ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(warning, color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Text(
                "${pager.currentPage + 1}/$slides",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close preview", tint = Color.White)
            }
        }
    }
}

/** Slides are rendered this wide for the preview: sharp on a phone screen, quick to draw. */
const val SWIPE_PREVIEW_WIDTH = 900
