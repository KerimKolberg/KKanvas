package com.squareify.desktop

import com.squareify.app.MediaItem
import com.squareify.app.PlatformContext
import com.squareify.app.toMediaUri
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Originals to the Windows Recycle Bin and back, with a throwaway file of the test's own. */
class RecycleBinTest {
    private val dir = Files.createTempDirectory("kk-bin").toFile()
    private val bin = RecycleBin(object : PlatformContext() {
        override val dataDir = File(dir, "data")
    })

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun anOriginalGoesToTheRecycleBinAndComesBack() = runBlocking {
        val original = TestMedia(dir).photo("kk-test-original-${System.nanoTime()}.jpg")
        val bytes = original.readBytes()
        val item = MediaItem(sourceUri = original.toMediaUri(), isVideo = false, displayName = original.name)
        assertNotNull(bin.trashableUri(item))

        assertTrue(bin.moveToTrash(listOf(original.toMediaUri())))
        assertFalse(original.exists())
        assertNull(bin.trashableUri(item), "gone from its folder")

        assertTrue(bin.restore(listOf(original.toMediaUri())))
        assertTrue(original.exists())
        assertTrue(bytes.contentEquals(original.readBytes()))

        // Out again, then deleted for good: nothing of it stays in the Recycle Bin.
        assertTrue(bin.moveToTrash(listOf(original.toMediaUri())))
        assertTrue(bin.deleteForever(listOf(original.toMediaUri())))
        assertFalse(bin.restore(listOf(original.toMediaUri())) && original.exists())
    }

    @Test
    fun theAppsOwnResultsAreNotOffered() {
        System.setProperty("kk.output.dir", File(dir, "out").path)
        val saved = File(dir, "out/Pictures/kk-Squareify/squared_x.jpg").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertNull(bin.trashableUri(MediaItem(sourceUri = saved.toMediaUri(), isVideo = false, displayName = saved.name)))
    }

    @Test
    fun theListIsKept() {
        val entries = listOf(com.squareify.app.TrashedOriginal(File(dir, "a.jpg").toMediaUri(), "a.jpg", false, 123L, null))
        bin.saveEntries(entries)
        assertEquals(entries, bin.loadEntries())
    }
}
