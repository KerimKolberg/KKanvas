package com.squareify.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * The shared screens on the phone: the main screen and every editor open and close. Runs in this
 * test app with a pretend platform, so the real app, its data and the gallery are never touched.
 */
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun cleanUp() {
        scope.cancel()
    }

    /** Photos from memory; saving does nothing. */
    private class PretendPlatform(override val context: PlatformContext) : AppPlatform {
        val photos = mutableMapOf<String, Bitmap>()
        override val io = Dispatchers.IO
        override fun message(text: String, long: Boolean) = Unit
        override fun timestamp() = "20261004_120000"
        override fun now() = System.currentTimeMillis()
        override fun open(uri: MediaUri) = OpenedMedia(isVideo = false, displayName = uri.lastPathSegment ?: "photo")
        override fun loadPreview(uri: MediaUri, isVideo: Boolean, maxSize: Int): PlatformBitmap? =
            photos[uri.toString()]?.copy(Bitmap.Config.ARGB_8888, true)
        override fun loadPhoto(uri: MediaUri, maxSize: Int): PlatformBitmap = photos.getValue(uri.toString()).copy(Bitmap.Config.ARGB_8888, true)
        override fun findFaces(photo: PlatformBitmap): Pair<Float, Float>? = null
        override fun videoDurationMs(uri: MediaUri): Long? = null
        override fun saveImage(bitmap: PlatformBitmap, name: String, replace: MediaUri?): MediaUri? = null
        override fun deleteOwn(uri: MediaUri) = Unit
        override fun renderVideo(item: MediaItem) = Unit
        override val renderStates = MutableStateFlow<Map<String, RenderState>>(emptyMap())
        override fun clearRenderState(id: String) = Unit
        override val trash: OriginalsTrash? = null
    }

    private fun photo(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.RED)
        canvas.clipRect(width / 2, 0, width, height)
        canvas.drawColor(Color.BLUE)
    }

    /** Closes the open editor, throwing away what it holds if it asks. */
    private fun closeEditor() {
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        val asking = compose.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isNotEmpty() &&
            runCatching { compose.onNodeWithText("Discard").assertIsDisplayed() }.isSuccess
        if (asking) compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
    }

    @Test
    fun everyScreenOpensAndCloses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = PretendPlatform(context)
        val uris = (1..3).map { Uri.parse("content://kk.test/photo$it.jpg") }
        uris.forEachIndexed { i, uri -> platform.photos[uri.toString()] = photo(600 + 200 * i, 600) }
        val wide = Uri.parse("content://kk.test/wide.jpg").also { platform.photos[it.toString()] = photo(3000, 1000) }

        lateinit var model: AppModel
        compose.runOnUiThread { model = AppModel(platform, scope) }
        compose.setContent { SquareifyTheme { SquareifyScreen(model) } }
        compose.onNodeWithText("Add Photos/Videos").assertIsDisplayed()

        compose.runOnUiThread { model.startCollageFromPicker(uris.take(2)) }
        compose.waitUntil(10_000) { model.collageDraft != null }
        compose.onNodeWithText("New collage").assertIsDisplayed()
        closeEditor()
        compose.waitUntil(5_000) { model.collageDraft == null }

        compose.runOnUiThread { model.startCarouselFromPicker(uris) }
        compose.waitUntil(10_000) { model.carouselDraft != null }
        compose.onNodeWithText("New carousel").assertIsDisplayed()
        closeEditor()
        compose.waitUntil(5_000) { model.carouselDraft == null }

        compose.runOnUiThread { model.startCollageFromPicker(listOf(wide)) }
        compose.waitUntil(10_000) { model.panoramaDraft != null }
        compose.onNodeWithText("Carousel slides").assertIsDisplayed()
        closeEditor()
        compose.waitUntil(5_000) { model.panoramaDraft == null }

        // A photo in the grid, and its editor.
        compose.runOnUiThread { model.addMedia(uris.take(1)) }
        compose.waitUntil(10_000) { model.items.size == 1 && model.items[0].thumbnail != null }
        compose.onAllNodesWithContentDescription("Edit")[0].performClick()
        compose.onNodeWithText("Edit: photo1.jpg").assertIsDisplayed()
        closeEditor()
        compose.onNodeWithText("Add Photos/Videos").assertIsDisplayed()
    }
}
