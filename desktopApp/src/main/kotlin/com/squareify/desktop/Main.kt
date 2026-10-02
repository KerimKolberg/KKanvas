package com.squareify.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.squareify.shared.Shared
import java.io.File

/**
 * Phase 0 (DESKTOP.md): a test window that records how touch, pen and two-finger gestures reach
 * Compose on this machine. Writes a summary to [LOG] when closed.
 */
private val LOG = File(System.getProperty("user.home"), "kk-squareify-input-test.txt")

fun main() = application {
    var summary by remember { mutableStateOf("Nothing yet") }
    val types = remember { mutableMapOf<String, Int>() }
    var maxPointers by remember { mutableStateOf(0) }
    var zoomSeen by remember { mutableStateOf(1f) }
    var rotationSeen by remember { mutableStateOf(0f) }
    var scrolls by remember { mutableStateOf(0) }

    fun describe() =
        "Pointer types: $types\nMost pointers at once: $maxPointers\n" +
            "Pinch zoom seen: ${"%.2f".format(zoomSeen)}x  rotation seen: ${"%.0f".format(rotationSeen)}°\n" +
            "Wheel / touchpad scrolls: $scrolls"

    Window(
        onCloseRequest = {
            LOG.writeText(describe())
            exitApplication()
        },
        title = "${Shared.NAME} — input test",
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val density = LocalDensity.current.density
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0E2038))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    val key = change.type.toString()
                                    types[key] = (types[key] ?: 0) + 1
                                }
                                maxPointers = maxOf(maxPointers, event.changes.count { it.pressed })
                                val zoom = event.calculateZoom()
                                if (kotlin.math.abs(zoom - 1f) > kotlin.math.abs(zoomSeen - 1f)) zoomSeen = zoom
                                val rotation = event.calculateRotation()
                                if (kotlin.math.abs(rotation) > kotlin.math.abs(rotationSeen)) rotationSeen = rotation
                                summary = describe()
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll) {
                                    scrolls++
                                    summary = describe()
                                }
                            }
                        }
                    },
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Input test for the Windows app", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(
                        "Tap and drag with a finger, draw with the pen, pinch and turn with two fingers, " +
                            "use the touchpad and mouse wheel. Then close this window.",
                        color = Color.White,
                    )
                    Text("Screen scale: ${density}x", color = Color(0xFF35BDB9))
                    Text(summary, color = Color(0xFF35BDB9), modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}
