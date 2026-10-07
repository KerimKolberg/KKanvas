package com.squareify.desktop

import androidx.compose.runtime.snapshots.Snapshot
import com.squareify.app.AppModel
import com.squareify.app.FrameFormat
import com.squareify.app.MediaItem
import com.squareify.app.Panorama
import com.squareify.app.PlatformContext
import com.squareify.app.collageSize
import com.squareify.app.slideSize
import com.squareify.app.toFile
import com.squareify.app.toMediaUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's functions on Windows, end to end: the shared [AppModel] with the Windows platform,
 * saving into a temporary Pictures / Videos and keeping its data in a temporary folder.
 */
class AppModelTest {
    private val dir = Files.createTempDirectory("kk-app").toFile()
    private val media = TestMedia(File(dir, "in"))
    private val data = File(dir, "data")
    private val pictures = File(dir, "out/Pictures/kkanvas")
    private val videos = File(dir, "out/Videos/kkanvas")
    private val scopes = mutableListOf<CoroutineScope>()

    init {
        System.setProperty("kk.output.dir", File(dir, "out").path)
    }

    private val context = object : PlatformContext() {
        override val dataDir = data
    }

    /** A new app, as if just started, on the window thread like the real one. */
    private fun newModel(): AppModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        scopes += scope
        // What Compose's window does: tell observers (the auto-save) about changed state.
        scope.launch {
            while (isActive) {
                Snapshot.sendApplyNotifications()
                delay(20)
            }
        }
        return runBlocking(Dispatchers.Swing) { AppModel(DesktopPlatform(context), scope) }
    }

    @AfterTest
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    private fun waitUntil(what: String, seconds: Long = 60, check: () -> Boolean) = runBlocking {
        withTimeout(seconds * 1000) {
            while (!withContext(Dispatchers.Swing) { check() }) delay(50)
        }
        assertTrue(true, what)
    }

    private fun AppModel.item(predicate: (MediaItem) -> Boolean) = items.first(predicate)

    private fun size(file: File): Pair<Int, Int> = DesktopImages.decode(file, 100_000).let { it.width to it.height }

    @Test
    fun photosAreSavedRightAwayAndAnEditOverwritesTheSameFile() {
        val model = newModel()
        val photo = media.photo("IMG_0001.jpg", 400, 200)
        runBlocking(Dispatchers.Swing) { model.addMedia(listOf(photo.toMediaUri())) }
        waitUntil("saved") { model.items.size == 1 && model.items[0].isRendered }
        val saved = File(pictures, "squared_IMG_0001.jpg")
        assertTrue(saved.isFile)
        assertEquals(400 to 400, size(saved))
        assertEquals(saved.toMediaUri(), model.items[0].outputUri)

        // A different format: the same file is replaced, with the new name.
        val item = model.items[0]
        runBlocking(Dispatchers.Swing) { model.applyEdit(item.id, item.settings.copy(format = FrameFormat.PORTRAIT)) }
        waitUntil("saved again") { model.items[0].isRendered && model.items[0].settings.format == FrameFormat.PORTRAIT }
        val portrait = File(pictures, "portrait_IMG_0001.jpg")
        assertTrue(portrait.isFile)
        assertFalse(saved.exists())
        assertEquals(400 to 500, size(portrait))
        assertEquals(1, pictures.listFiles()!!.size)
    }

    @Test
    fun aNameAlreadyTakenGetsANumber() {
        val model = newModel()
        val a = media.photo("same.jpg")
        val b = File(dir, "other/same.jpg").apply { parentFile.mkdirs(); writeBytes(a.readBytes()) }
        runBlocking(Dispatchers.Swing) { model.addMedia(listOf(a.toMediaUri(), b.toMediaUri())) }
        waitUntil("both saved") { model.items.size == 2 && model.items.all { it.isRendered } }
        assertTrue(File(pictures, "squared_same.jpg").isFile)
        assertTrue(File(pictures, "squared_same (1).jpg").isFile)
    }

    @Test
    fun collagesCarouselsAndPanoramasAreSaved() {
        val model = newModel()
        val photos = (1..3).map { media.photo("P$it.jpg", 600, 400) }
        runBlocking(Dispatchers.Swing) { model.startCollageFromPicker(photos.map { it.toMediaUri() }) }
        waitUntil("collage editor") { model.collageDraft != null }
        runBlocking(Dispatchers.Swing) { model.createCollage(model.collageDraft!!, model.globalSettings) }
        waitUntil("collage saved") { model.items.any { it.collage != null && it.isRendered } }
        val collage = model.item { it.collage != null }.outputUri!!.toFile()
        assertTrue(collage.name.startsWith("collage_"))
        assertEquals(collageSize(FrameFormat.SQUARE), size(collage))

        runBlocking(Dispatchers.Swing) { model.startCarouselFromPicker(photos.map { it.toMediaUri() }) }
        waitUntil("carousel editor") { model.carouselDraft != null }
        val draft = model.carouselDraft!!
        runBlocking(Dispatchers.Swing) { model.createCarousel(draft, model.globalSettings) }
        waitUntil("carousel saved") { model.items.any { it.carousel != null && it.isRendered } }
        val slides = model.item { it.carousel != null }.outputUris.map { it.toFile() }
        assertEquals(draft.slides, slides.size)
        slides.forEach { assertEquals(slideSize(FrameFormat.SQUARE), size(it)) }

        val wide = media.photo("wide.jpg", 3000, 1000)
        runBlocking(Dispatchers.Swing) { model.startCollageFromPicker(listOf(wide.toMediaUri())) }
        waitUntil("panorama editor") { model.panoramaDraft != null }
        runBlocking(Dispatchers.Swing) { model.createPanorama(model.panoramaDraft!!, Panorama(slides = 3), model.globalSettings) }
        waitUntil("panorama saved") { model.items.any { it.panorama != null && it.isRendered } }
        val pano = model.item { it.panorama != null }.outputUris.map { it.toFile() }
        assertEquals(listOf("carousel_wide_1.jpg", "carousel_wide_2.jpg", "carousel_wide_3.jpg"), pano.map { it.name })
    }

    @Test
    fun videosRenderOnRequestIntoVideos() {
        val model = newModel()
        val clip = media.clip("VID_0001.mp4", 640, 360, seconds = 1.5)
        runBlocking(Dispatchers.Swing) { model.addMedia(listOf(clip.toMediaUri())) }
        waitUntil("added") { model.items.size == 1 }
        val item = model.items[0]
        assertTrue(item.isVideo)
        assertFalse(item.isRendered)
        assertNotNull(item.preview, "a frame from the middle")
        runBlocking(Dispatchers.Swing) { model.renderVideo(item.id) }
        waitUntil("rendered", seconds = 120) { model.items[0].isRendered }
        val out = File(videos, "squared_VID_0001.mp4")
        assertTrue(out.isFile)
        val probe = Ffmpeg.probe(out)
        assertEquals(640, probe.width)
        assertEquals(640, probe.height)
        assertNull(model.items[0].error)
    }

    @Test
    fun theGridAndSettingsComeBackAfterARestart() {
        val first = newModel()
        val photo = media.photo("keep.jpg")
        runBlocking(Dispatchers.Swing) {
            first.updateGlobalSettings(first.globalSettings.copy(format = FrameFormat.STORY, bgColor = 0xFF123456.toInt()))
            first.addMedia(listOf(photo.toMediaUri()))
        }
        waitUntil("saved") { first.items.size == 1 && first.items[0].isRendered }
        waitUntil("projects written", seconds = 10) { File(data, "projects.json").let { it.isFile && "keep.jpg" in it.readText() && "\"isRendered\":true" in it.readText() } }
        Thread.sleep(500)

        val second = newModel()
        waitUntil("restored") { second.items.size == 1 && second.items[0].thumbnail != null }
        val restored = second.items[0]
        assertEquals("keep.jpg", restored.displayName)
        assertTrue(restored.isRendered)
        assertEquals(FrameFormat.STORY, restored.settings.format)
        assertEquals(FrameFormat.STORY, second.globalSettings.format)
        assertEquals(0xFF123456.toInt(), second.globalSettings.bgColor)
    }
}
