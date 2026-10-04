package com.squareify.desktop

import java.io.File

/**
 * Where saved results go: Pictures\kk-Squareify and Videos\kk-Squareify, in the user's real
 * Pictures and Videos folders (which may have been moved, e.g. into OneDrive).
 */
object WindowsFolders {
    const val FOLDER = "kk-Squareify"

    /** For tests: everything under this folder instead. */
    private val override: File? get() = System.getProperty("kk.output.dir")?.let(::File)

    val pictures: File get() = File(override?.let { File(it, "Pictures") } ?: known("My Pictures", "Pictures"), FOLDER)
    val videos: File get() = File(override?.let { File(it, "Videos") } ?: known("My Video", "Videos"), FOLDER)

    /** A shell folder from the registry, or %USERPROFILE%\[fallback]. */
    private fun known(registryName: String, fallback: String): File {
        val home = System.getenv("USERPROFILE") ?: System.getProperty("user.home")
        return try {
            val process = ProcessBuilder(
                "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders", "/v", registryName,
            ).start()
            val line = process.inputStream.bufferedReader().readLines().firstOrNull { it.trim().startsWith(registryName) }
            process.waitFor()
            val value = line?.substringAfter("REG_EXPAND_SZ", "")?.ifEmpty { line.substringAfter("REG_SZ", "") }?.trim()
            value?.takeIf { it.isNotEmpty() }?.let { File(expand(it)) } ?: File(home, fallback)
        } catch (e: Exception) {
            File(home, fallback)
        }
    }

    /** "%USERPROFILE%\Pictures" → "C:\Users\me\Pictures". */
    private fun expand(path: String): String =
        Regex("%([^%]+)%").replace(path) { match -> System.getenv(match.groupValues[1]) ?: match.value }
}
