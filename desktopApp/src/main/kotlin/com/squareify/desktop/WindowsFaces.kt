package com.squareify.desktop

import org.jetbrains.skia.Bitmap
import java.io.BufferedReader
import java.io.File
import java.io.Writer
import kotlin.math.max
import kotlin.math.min

/**
 * Smart crop on Windows: Windows' own face detector (Windows.Media.FaceAnalysis), reached
 * through a PowerShell helper that stays open, so each photo takes a moment rather than seconds.
 * Like the phone's FaceFinder, it returns the middle of the area the faces take up.
 */
object WindowsFaces {
    /** Detection runs on a copy this large: quick, and plenty for faces worth keeping in view. */
    private const val WORK_SIZE = 640

    private class Helper(val process: Process, val input: Writer, val output: BufferedReader)

    private var helper: Helper? = null

    private val script = """
        ${'$'}ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Runtime.WindowsRuntime
        ${'$'}asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
        function Await(${'$'}operation, [Type] ${'$'}type) {
            ${'$'}task = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}operation))
            ${'$'}task.Wait(-1) | Out-Null
            ${'$'}task.Result
        }
        [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null
        [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
        [Windows.Media.FaceAnalysis.FaceDetector, Windows.Media.FaceAnalysis, ContentType = WindowsRuntime] | Out-Null
        ${'$'}detector = Await ([Windows.Media.FaceAnalysis.FaceDetector]::CreateAsync()) ([Windows.Media.FaceAnalysis.FaceDetector])
        [Console]::Out.WriteLine('ready')
        while (${'$'}true) {
            ${'$'}path = [Console]::In.ReadLine()
            if (${'$'}path -eq ${'$'}null) { break }
            try {
                ${'$'}file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync(${'$'}path)) ([Windows.Storage.StorageFile])
                ${'$'}stream = Await (${'$'}file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
                ${'$'}decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync(${'$'}stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
                ${'$'}bitmap = Await (${'$'}decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
                ${'$'}gray = [Windows.Graphics.Imaging.SoftwareBitmap]::Convert(${'$'}bitmap, [Windows.Graphics.Imaging.BitmapPixelFormat]::Gray8)
                ${'$'}faces = Await (${'$'}detector.DetectFacesAsync(${'$'}gray)) ([System.Collections.Generic.IList[Windows.Media.FaceAnalysis.DetectedFace]])
                foreach (${'$'}face in ${'$'}faces) {
                    ${'$'}b = ${'$'}face.FaceBox
                    [Console]::Out.WriteLine("face ${'$'}(${'$'}b.X) ${'$'}(${'$'}b.Y) ${'$'}(${'$'}b.Width) ${'$'}(${'$'}b.Height)")
                }
                [Console]::Out.WriteLine("size ${'$'}(${'$'}gray.PixelWidth) ${'$'}(${'$'}gray.PixelHeight)")
                ${'$'}stream.Dispose()
            } catch {
                [Console]::Out.WriteLine("error ${'$'}(${'$'}_.Exception.Message)")
            }
            [Console]::Out.WriteLine('end')
        }
    """.trimIndent()

    private fun start(): Helper {
        val file = File.createTempFile("kk-faces-", ".ps1").apply {
            deleteOnExit()
            writeText(script)
        }
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file.path)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader()
        check(output.readLine() == "ready") { "the face detector didn't start" }
        return Helper(process, process.outputStream.bufferedWriter(), output)
    }

    /** Where the faces in [photo] are, as fractions (0–1) of its width and height; null if none. */
    @Synchronized
    fun focus(photo: Bitmap): Pair<Float, Float>? {
        val small = File.createTempFile("kk-faces-", ".png")
        try {
            val scaled = DesktopImages.decode(DesktopImages.png(photo), WORK_SIZE)
            small.writeBytes(DesktopImages.png(scaled))
            scaled.close()
            val running = helper?.takeIf { it.process.isAlive } ?: start().also { helper = it }
            running.input.write(small.absolutePath + "\n")
            running.input.flush()
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -1f
            var bottom = -1f
            var width = 0f
            var height = 0f
            while (true) {
                val line = running.output.readLine() ?: break
                val parts = line.split(' ')
                when (parts[0]) {
                    "face" -> {
                        val (x, y, w, h) = parts.drop(1).map { it.toFloat() }
                        left = min(left, x)
                        top = min(top, y)
                        right = max(right, x + w)
                        // The head reaches a little below the face box (chin, neck), as the phone counts it.
                        bottom = max(bottom, y + h * 1.15f)
                    }
                    "size" -> {
                        width = parts[1].toFloat()
                        height = parts[2].toFloat()
                    }
                    "end" -> break
                }
            }
            if (right < 0 || width <= 0 || height <= 0) return null
            return ((left + right) / 2 / width).coerceIn(0f, 1f) to ((top + bottom) / 2 / height).coerceIn(0f, 1f)
        } catch (e: Exception) {
            helper?.process?.destroy()
            helper = null
            return null
        } finally {
            small.delete()
        }
    }
}
