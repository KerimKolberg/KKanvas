package com.squareify.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** kk-Squareify's data and results move to the kkanvas folders, and saved paths follow. */
class OldNamesTest {
    private val dir = Files.createTempDirectory("kk-names").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun dataResultsAndSavedPathsMove() {
        val oldData = File(dir, "AppData/kk-Squareify").apply { mkdirs() }
        val newData = File(dir, "AppData/kkanvas")
        val oldPictures = File(dir, "My Pictures/kk-Squareify").apply { mkdirs() }
        val newPictures = File(dir, "My Pictures/kkanvas")
        val photo = File(oldPictures, "squared_IMG 1.jpg").apply { writeText("jpeg") }
        val thumb = File(oldData, "trash/1.png").apply { parentFile.mkdirs(); writeText("png") }
        File(oldData, "projects.json").writeText("""{"items":[{"output":"${photo.toURI()}"}]}""")
        File(oldData, "trash.json").writeText("""[{"thumbnail":"${thumb.absolutePath.replace("\\", "\\\\")}"}]""")

        OldNames.move(oldData, newData, listOf(oldPictures to newPictures))

        assertFalse(oldData.exists())
        assertFalse(oldPictures.exists())
        val movedPhoto = File(newPictures, "squared_IMG 1.jpg")
        assertEquals("jpeg", movedPhoto.readText())
        assertEquals("""{"items":[{"output":"${movedPhoto.toURI()}"}]}""", File(newData, "projects.json").readText())
        val movedThumb = File(newData, "trash/1.png")
        assertTrue(movedThumb.isFile)
        assertEquals("""[{"thumbnail":"${movedThumb.absolutePath.replace("\\", "\\\\")}"}]""", File(newData, "trash.json").readText())
    }

    @Test
    fun filesJoinANewFolderThatIsAlreadyThere() {
        val old = File(dir, "Videos/kk-Squareify").apply { mkdirs() }
        val new = File(dir, "Videos/kkanvas").apply { mkdirs() }
        File(old, "a.mp4").writeText("old a")
        File(old, "b.mp4").writeText("old b")
        File(new, "b.mp4").writeText("new b")
        OldNames.move(File(dir, "no-data"), File(dir, "data"), listOf(old to new))
        assertEquals("old a", File(new, "a.mp4").readText())
        assertEquals("new b", File(new, "b.mp4").readText(), "nothing already there is replaced")
    }
}
