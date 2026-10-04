package com.squareify.desktop

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.squareify.app.AppModel
import com.squareify.app.Panorama
import com.squareify.app.PlatformContext
import com.squareify.app.SquareifyTheme
import com.squareify.app.toMediaUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * The screens as they look on Windows (at the Z13's maximized window size), saved as PNGs in
 * desktopApp/build/screens for a look; also proves every screen opens without crashing.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun save(node: SemanticsNodeInteraction, name: String) {
        val bitmap = node.captureToImage().asSkiaBitmap()
        val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(out, "$name.png").writeBytes(png)
    }

    @Test
    fun everyScreen() {
        val dir = Files.createTempDirectory("kk-screens").toFile()
        System.setProperty("kk.output.dir", File(dir, "out").path)
        val context = object : PlatformContext() {
            override val dataDir = File(dir, "data")
        }
        val media = TestMedia(File(dir, "in"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            runDesktopComposeUiTest(width = 1700, height = 1000) {
                val platform = DesktopPlatform(context)
                lateinit var model: AppModel
                runOnUiThread { model = AppModel(platform, scope) }
                setContent { SquareifyTheme { SquareifyWindow(model, platform) } }
                waitForIdle()
                save(onAllNodes(isRoot())[0], "01-empty")

                val photos = listOf(media.photo("Beach.jpg", 1200, 800), media.photo("Portrait.jpg", 800, 1200), media.photo("Wide.jpg", 3000, 1000))
                runOnUiThread { model.addMedia(photos.map { it.toMediaUri() } + media.clip("Clip.mp4", 640, 360, seconds = 1.0).toMediaUri()) }
                waitUntil(timeoutMillis = 30_000) { Snapshot.withoutReadObservation { model.items.size == 4 && model.items.count { it.isRendered } == 3 } }
                waitForIdle()
                save(onAllNodes(isRoot())[0], "02-grid")

                // A photo's editor.
                onAllNodesWithContentDescription("Edit")[0].performClick()
                waitForIdle()
                save(onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }, "03-edit-photo")
                onAllNodesWithContentDescription("Close")[0].performClick()
                waitForIdle()

                runOnUiThread { model.startCollageFromPicker(photos.take(2).map { it.toMediaUri() }) }
                waitUntil(timeoutMillis = 10_000) { model.collageDraft != null }
                waitForIdle()
                save(onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }, "04-collage")
                runOnUiThread { model.dismissCollageDraft() }

                runOnUiThread { model.startCarouselFromPicker(photos.map { it.toMediaUri() }) }
                waitUntil(timeoutMillis = 10_000) { model.carouselDraft != null }
                waitForIdle()
                save(onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }, "05-carousel")
                runOnUiThread { model.dismissCarouselDraft() }

                runOnUiThread { model.startCollageFromPicker(listOf(photos[2].toMediaUri())) }
                waitUntil(timeoutMillis = 10_000) { model.panoramaDraft != null }
                waitForIdle()
                save(onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }, "06-panorama")
                runOnUiThread { model.createPanorama(model.panoramaDraft!!, Panorama(3), model.globalSettings) }
                waitForIdle()
                save(onAllNodes(isRoot())[0], "07-grid-with-slides")
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
