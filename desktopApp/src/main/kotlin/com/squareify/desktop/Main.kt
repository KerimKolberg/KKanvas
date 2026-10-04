package com.squareify.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.squareify.app.APP_NAME
import com.squareify.app.AppModel
import com.squareify.app.DesktopWindow
import com.squareify.app.PHOTO_EXTENSIONS
import com.squareify.app.SquareifyScreen
import com.squareify.app.SquareifyTheme
import com.squareify.app.VIDEO_EXTENSIONS
import com.squareify.app.toMediaUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.datatransfer.DataFlavor
import java.io.File

fun main() {
    val platform = DesktopPlatform()
    // Lives as long as the app, on the window's thread, like the phone's ViewModel.
    val model = AppModel(platform, CoroutineScope(SupervisorJob() + Dispatchers.Main))
    application {
        val state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1400.dp, 900.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = APP_NAME,
            icon = remember { appIcon() },
        ) {
            DesktopWindow.frame = window
            SquareifyTheme {
                SquareifyWindow(model, platform)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SquareifyWindow(model: AppModel, platform: DesktopPlatform) {
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(platform) {
        platform.messages.collect { snackbars.showSnackbar(it) }
    }
    // Photos and videos dragged in from Explorer are added like picked ones.
    val dropTarget = remember(model) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = droppedFiles(event)
                if (files.isEmpty()) return false
                model.addMedia(files.map { it.toMediaUri() })
                return true
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { droppedFiles(it).isNotEmpty() }, target = dropTarget),
    ) {
        SquareifyScreen(model)
        SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** The photos and videos in a drop (folders and other files are left out). */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> = try {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        emptyList()
    } else {
        @Suppress("UNCHECKED_CAST")
        (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
            .filter { it.isFile && it.extension.lowercase().let { e -> e in PHOTO_EXTENSIONS || e in VIDEO_EXTENSIONS } }
    }
} catch (e: Exception) {
    emptyList()
}

/** The kk icon for the window and taskbar. */
private fun appIcon(): BitmapPainter? = try {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.readBytes()
    bytes?.let { BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap()) }
} catch (e: Exception) {
    null
}
