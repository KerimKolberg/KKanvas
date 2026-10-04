package com.squareify.desktop

import com.squareify.app.CellFit
import com.squareify.app.Collage
import com.squareify.app.FrameSettings
import com.squareify.app.MAX_DECODE_DIMENSION
import com.squareify.app.ShortClips
import com.squareify.app.processing.CollageRenderer
import com.squareify.app.processing.PhotoProcessor
import com.squareify.app.toFile
import com.squareify.app.videoCollageSize
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Videos on Windows: FFmpeg decodes, the shared renderers draw each frame exactly as on the phone,
 * FFmpeg encodes (on the Radeon with AMF where it can). Same timing rules as the phone: a single
 * video keeps its frame rate, trimmed and sped up or slowed down; a boomerang plays forward then
 * back; a video collage runs at 30 fps for as long as its longest clip.
 */
object DesktopVideo {
    /** Collages, as on the phone. */
    private const val COLLAGE_FPS = 30.0
    /** H.264 on the Radeon: no side longer than this. */
    private const val MAX_SIDE = 4096

    /** Renders one video into [output]; returns true if its sound couldn't be kept. */
    fun render(source: File, settings: FrameSettings, output: File, onProgress: (Float) -> Unit): Boolean {
        val probe = Ffmpeg.probe(source)
        val (canvasW, canvasH) = PhotoProcessor.canvasSize(probe.width, probe.height, settings)
        val (width, height) = fitToEncoder(canvasW, canvasH)
        val fps = probe.fps
        val edit = settings.video
        val range = edit.rangeMs(probe.durationMs)
        val lengthMs = range.last - range.first
        val sound = if (edit.keepsSound && probe.hasAudio) Ffmpeg.Sound(source, range.first, lengthMs) else null
        // Sped up or slowed down, the frames on the result's own timeline.
        val expected = max(1, (lengthMs / edit.speed / 1000 * fps).roundToInt())
        val boomerang = if (edit.boomerang) mutableListOf<ByteArray>() else null
        val forwardShare = if (boomerang != null) 0.6f else 0.9f

        Ffmpeg.writer(output, width, height, fps, sound).use { writer ->
            Ffmpeg.reader(source, probe.width, probe.height, fps, range.first, lengthMs, edit.speed).use { reader ->
                var n = 0
                while (true) {
                    val rgba = reader.next() ?: break
                    val frame = frameFrom(rgba, probe.width, probe.height)
                    val framed = PhotoProcessor.frame(frame, settings, width, height)
                    val out = pixelsOf(framed)
                    writer.write(out)
                    boomerang?.add(jpegOf(framed))
                    frame.close()
                    framed.close()
                    n++
                    onProgress(min(forwardShare, n.toFloat() / expected * forwardShare))
                }
            }
            if (boomerang != null && boomerang.size > 1) {
                // Back again: the same frames in reverse, without repeating the turning point.
                for (i in boomerang.size - 2 downTo 0) {
                    writer.write(pixelsOfJpeg(boomerang[i], width, height))
                    onProgress(forwardShare + (0.9f - forwardShare) * (boomerang.size - 1 - i) / (boomerang.size - 1))
                }
            }
            writer.finish()
        }
        onProgress(1f)
        return false
    }

    /** Renders a video collage into [output]; returns true if the chosen clip's sound couldn't be kept. */
    fun renderCollage(collage: Collage, settings: FrameSettings, output: File, onProgress: (Float) -> Unit): Boolean {
        val files = collage.cells.map { it.sourceUri.toFile() }
        val probes = collage.cells.mapIndexed { i, cell -> if (cell.isVideo) Ffmpeg.probe(files[i]) else null }
        val durationMs = probes.maxOf { it?.durationMs ?: 0L }
        require(durationMs > 0) { "None of the clips could be read" }
        val loop = collage.shortClips == ShortClips.LOOP
        val soundIndex = collage.soundCell?.takeIf { probes.getOrNull(it)?.hasAudio == true }
        val (requestedW, requestedH) = videoCollageSize(settings.format)
        val (width, height) = fitToEncoder(requestedW, requestedH)
        val frameCount = ((durationMs * COLLAGE_FPS + 999) / 1000).toInt()
        val sound = soundIndex?.let { i ->
            Ffmpeg.Sound(files[i], 0, frameCount * 1000L / COLLAGE_FPS.toLong(), loop = loop && probes[i]!!.durationMs < durationMs)
        }

        val rects = CollageRenderer.cellRects(collage, settings, width, height)
        val readers = arrayOfNulls<Ffmpeg.FrameReader>(collage.cells.size)
        val photos = arrayOfNulls<Bitmap>(collage.cells.size)
        val lastFrames = arrayOfNulls<Bitmap>(collage.cells.size)
        try {
            collage.cells.forEachIndexed { i, cell ->
                val rect = rects.getOrNull(i) ?: return@forEachIndexed
                val probe = probes[i]
                if (probe != null) {
                    val (w, h) = readSize(probe.width, probe.height, rect.width, rect.height, cell.fit, cell.zoom)
                    readers[i] = Ffmpeg.reader(files[i], w, h, COLLAGE_FPS, loop = loop)
                } else {
                    val needed = (max(rect.width, rect.height) * cell.zoom * 2).roundToInt().coerceIn(512, MAX_DECODE_DIMENSION)
                    photos[i] = DesktopImages.decode(files[i], needed)
                }
            }
            Ffmpeg.writer(output, width, height, COLLAGE_FPS, sound).use { writer ->
                for (f in 0 until frameCount) {
                    collage.cells.indices.forEach { c ->
                        val reader = readers[c] ?: return@forEach
                        // A clip that has ended (and doesn't loop) stays on its last frame.
                        val rgba = reader.next() ?: return@forEach
                        lastFrames[c]?.close()
                        lastFrames[c] = frameFrom(rgba, reader.width, reader.height)
                    }
                    val sources = collage.cells.indices.map { c -> lastFrames[c] ?: photos[c] }
                    val frame = CollageRenderer.render(collage, sources, settings, width, height)
                    writer.write(pixelsOf(frame))
                    frame.close()
                    onProgress(0.95f * (f + 1) / frameCount)
                }
                writer.finish()
            }
        } finally {
            readers.forEach { it?.close() }
            photos.forEach { it?.close() }
            lastFrames.forEach { it?.close() }
        }
        onProgress(1f)
        return false
    }

    /**
     * How large to read a clip: twice what its cell shows, so the final downscale stays smooth,
     * but never more than its full size. Keeps the aspect ratio.
     */
    private fun readSize(width: Int, height: Int, cellW: Float, cellH: Float, fit: CellFit, zoom: Float): Pair<Int, Int> {
        val shown = when (fit) {
            CellFit.FILL -> max(cellW / width, cellH / height) * zoom
            CellFit.FIT -> min(cellW / width, cellH / height)
        }
        val scale = min(1f, shown * 2)
        return max(16, (width * scale).roundToInt() / 2 * 2) to max(16, (height * scale).roundToInt() / 2 * 2)
    }

    /** Even sides (YUV 4:2:0), none longer than the encoder takes, keeping the shape. */
    private fun fitToEncoder(width: Int, height: Int): Pair<Int, Int> {
        val scale = min(1f, MAX_SIDE.toFloat() / max(width, height))
        return max(2, (width * scale).toInt() / 2 * 2) to max(2, (height * scale).toInt() / 2 * 2)
    }

    /** A decoded RGBA frame as a picture the renderers can draw from. */
    private fun frameFrom(rgba: ByteArray, width: Int, height: Int): Bitmap = Bitmap().apply {
        installPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba.copyOf(), width * 4)
    }

    private val rgbaInfo = { w: Int, h: Int -> ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL) }

    /** A drawn frame's pixels for the encoder (RGBA, row by row). */
    private fun pixelsOf(bitmap: Bitmap): ByteArray =
        checkNotNull(bitmap.readPixels(rgbaInfo(bitmap.width, bitmap.height), bitmap.width * 4, 0, 0)) { "Couldn't read a frame" }

    private fun jpegOf(bitmap: Bitmap): ByteArray {
        val image = Image.makeFromBitmap(bitmap)
        return try {
            checkNotNull(image.encodeToData(EncodedImageFormat.JPEG, 92)).bytes
        } finally {
            image.close()
        }
    }

    private fun pixelsOfJpeg(jpeg: ByteArray, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(jpeg))
        return try {
            check(bitmap.width == width && bitmap.height == height)
            pixelsOf(bitmap)
        } finally {
            bitmap.close()
        }
    }
}
