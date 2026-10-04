package com.squareify.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull

/** Windows' face detector, for smart crop. */
class FacesTest {
    @Test
    fun aPictureWithoutFacesHasNoFocus() {
        val dir = Files.createTempDirectory("kk-faces").toFile()
        try {
            assertNull(WindowsFaces.focus(DesktopImages.decode(TestMedia(dir).photo("plain.jpg", 800, 600), 800)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun windowsOwnPictures() {
        // Windows' wallpapers and lock screen pictures: shows the detector runs on real photos.
        val pictures = File("C:/Windows/Web").walkTopDown().filter { it.extension.equals("jpg", true) }.toList()
        pictures.forEach { file ->
            val focus = WindowsFaces.focus(DesktopImages.decode(file, 1600))
            println("FACES ${file.parentFile.name}/${file.name}: $focus")
        }
    }
}
