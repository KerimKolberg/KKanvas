package com.squareify.desktop

import com.squareify.app.DesktopContext
import com.squareify.app.logWarning
import java.io.File

/**
 * The app was called kk-Squareify before kkanvas. On the first start its data (%APPDATA%) and its
 * results (Pictures, Videos) move to the new folders, and the saved projects and the trash list
 * are pointed at the moved files.
 */
object OldNames {
    const val OLD_NAME = "kk-Squareify"

    fun moveToNewFolders() {
        try {
            move(
                oldData = File(DesktopContext.dataDir.parentFile, OLD_NAME),
                newData = DesktopContext.dataDir,
                results = listOf(WindowsFolders.pictures, WindowsFolders.videos).map { File(it.parentFile, OLD_NAME) to it },
            )
        } catch (e: Exception) {
            logWarning("kkanvas", "could not move the folders of kk-Squareify", e)
        }
    }

    /** Moves [oldData] to [newData] and each old results folder to its new one, then fixes the paths saved in [newData]. */
    fun move(oldData: File, newData: File, results: List<Pair<File, File>>) {
        val moves = (listOf(oldData to newData) + results).filter { (old, _) -> old.isDirectory }
        if (moves.isEmpty()) return
        // As saved: file: URIs (projects) and plain paths (trash thumbnails), written before the move.
        val before = moves.map { (old, new) -> Triple(old.toURI().toString(), jsonPath(old), new) }
        moves.forEach { (old, new) -> moveFolder(old, new) }
        val renames = before.map { (uri, path, new) -> Triple(uri, path, new.toURI().toString() to jsonPath(new)) }
        for (name in listOf("projects.json", "trash.json")) {
            val file = File(newData, name)
            if (!file.isFile) continue
            var text = file.readText()
            for ((oldUri, oldPath, new) in renames) {
                text = text.replace(oldUri, new.first).replace(oldPath + "\\\\", new.second + "\\\\")
            }
            file.writeText(text)
        }
    }

    /** [old] renamed to [new]; if [new] is there already, [old]'s files move in (nothing there is replaced). */
    private fun moveFolder(old: File, new: File) {
        if (!new.exists()) {
            new.parentFile?.mkdirs()
            if (old.renameTo(new)) return
        }
        new.mkdirs()
        old.listFiles()?.forEach { file ->
            val target = File(new, file.name)
            if (!target.exists()) file.renameTo(target)
        }
        old.delete()
    }

    /** A path as JSON writes it (backslashes doubled). */
    private fun jsonPath(file: File): String = file.absolutePath.replace("\\", "\\\\")
}
