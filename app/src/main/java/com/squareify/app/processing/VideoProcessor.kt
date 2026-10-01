package com.squareify.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.squareify.app.FrameSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Re-encodes a video as an H.264 MP4 padded to its format: every decoded frame goes through
 * [PhotoProcessor.frame], and the original audio track is copied over untouched.
 */
object VideoProcessor {
    private const val TAG = "VideoProcessor"
    internal const val TIMEOUT_US = 10_000L
    /** How long to wait for a rendered frame before repeating the previous one. */
    internal const val FRAME_TIMEOUT_MS = 2_500L

    data class Probe(
        val durationUs: Long,
        val fps: Int,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val videoTrackIndex: Int,
        val audioTrackIndex: Int,
    ) {
        private val rotated get() = rotationDegrees == 90 || rotationDegrees == 270

        /** Size as shown, i.e. after rotation. */
        val displayWidth: Int get() = if (rotated) height else width
        val displayHeight: Int get() = if (rotated) width else height
    }

    /**
     * [onProgress] runs from 0 to 0.9 while encoding, 0.92 after audio, 1 when finished.
     * Returns true if the source had sound but it couldn't be copied into the output.
     */
    suspend fun render(
        context: Context,
        sourceUri: Uri,
        settings: FrameSettings,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        val probe = probeSource(context, sourceUri)
        val (canvasWidth, canvasHeight) =
            PhotoProcessor.canvasSize(probe.displayWidth, probe.displayHeight, settings)
        val fps = probe.fps.coerceIn(1, 60)
        val edit = settings.video
        val range = edit.rangeMs(probe.durationUs / 1000)
        val startUs = range.first * 1000
        val endUs = range.last * 1000
        Log.d(TAG, "probe=$probe canvas=${canvasWidth}x$canvasHeight fps=$fps edit=$edit range=$range")

        val audio = if (edit.keepsSound) audioFormat(context, sourceUri, probe) else null
        val writer = Mp4Writer(outputFile, canvasWidth, canvasHeight, fps, audio)
        val boomerang = if (edit.boomerang) FrameCache(File(context.cacheDir, "boomerang_${outputFile.nameWithoutExtension}")) else null
        // Going forward is all the work, unless a boomerang has to come back as well.
        val forwardShare = if (boomerang != null) 0.6f else 0.9f
        // Sped up, frames closer together than this are dropped, so the result keeps the clip's frame rate.
        val minStepUs = 1_000_000L / fps * 3 / 4
        try {
            var lastOutUs = Long.MIN_VALUE
            decodeSequentially(
                context,
                sourceUri,
                probe.videoTrackIndex,
                probe.displayWidth,
                probe.displayHeight,
                startUs,
                endUs,
            ) { bitmap, ptsUs ->
                val outUs = ((ptsUs - startUs) / edit.speed).toLong().coerceAtLeast(0L)
                if (lastOutUs == Long.MIN_VALUE || outUs - lastOutUs >= minStepUs) {
                    lastOutUs = outUs
                    val framed = PhotoProcessor.frame(bitmap, settings, writer.width, writer.height)
                    writer.encode(framed, outUs)
                    boomerang?.add(framed, outUs)
                    if (framed !== bitmap) framed.recycle()
                }
                if (endUs > startUs) {
                    onProgress(min(forwardShare, (ptsUs - startUs).toFloat() / (endUs - startUs) * forwardShare))
                }
            }
            if (boomerang != null && boomerang.size > 1) {
                // Back again: the same frames in reverse, mirrored in time.
                val lastUs = boomerang.times.last()
                for (i in boomerang.size - 2 downTo 0) {
                    val frame = boomerang.load(i)
                    writer.encode(frame, 2 * lastUs - boomerang.times[i])
                    frame.recycle()
                    onProgress(forwardShare + (0.9f - forwardShare) * (boomerang.size - 1 - i) / (boomerang.size - 1))
                }
            }
            writer.finishVideo()
            onProgress(0.92f)
            // Sound left out on purpose (muted, other speed, boomerang) isn't "dropped".
            val soundDropped = if (audio != null) {
                copySound(context, sourceUri, probe, writer, startUs = startUs, endUs = endUs - startUs)
            } else {
                false
            }
            writer.close()
            onProgress(1f)
            soundDropped
        } finally {
            writer.release()
            boomerang?.delete()
        }
    }

    /** Rendered frames kept as JPEG files, to play a boomerang backwards without holding them in memory. */
    private class FrameCache(private val dir: File) {
        val times = mutableListOf<Long>()
        val size: Int get() = times.size

        init {
            dir.deleteRecursively()
            dir.mkdirs()
        }

        fun add(frame: Bitmap, timeUs: Long) {
            File(dir, "${times.size}.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            times += timeUs
        }

        fun load(index: Int): Bitmap =
            BitmapFactory.decodeFile(File(dir, "$index.jpg").absolutePath)
                ?: throw IllegalStateException("boomerang frame $index is missing")

        fun delete() {
            dir.deleteRecursively()
        }
    }

    /** The source's audio track format, or null if it has no sound. */
    internal fun audioFormat(context: Context, uri: Uri, probe: Probe): MediaFormat? {
        if (probe.audioTrackIndex < 0) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            extractor.getTrackFormat(probe.audioTrackIndex)
        } finally {
            extractor.release()
        }
    }

    /**
     * Copies the source's sound from [startUs] on into [writer], cut off at [endUs] of the
     * result. With [loopLengthUs] it starts over every [loopLengthUs] until [endUs], to stay in
     * step with a looping clip. Returns true if the source had sound but it couldn't be copied.
     */
    internal fun copySound(
        context: Context,
        uri: Uri,
        probe: Probe,
        writer: Mp4Writer,
        startUs: Long = 0L,
        endUs: Long = Long.MAX_VALUE,
        loopLengthUs: Long = 0L,
    ): Boolean {
        if (probe.audioTrackIndex < 0) return false
        if (writer.audioTrack < 0) {
            Log.w(TAG, "audio track was never added to the muxer, output will have no sound")
            return true
        }
        return try {
            remuxAudio(context, uri, probe.audioTrackIndex, writer, startUs, endUs, loopLengthUs)
            false
        } catch (e: Exception) {
            Log.w(TAG, "audio remux failed, output will have no sound", e)
            true
        }
    }

    /**
     * A decoder for [format], configured to render onto [surface]. Tries the phone's decoders in
     * its preferred order (hardware first): with several clips at once (collages) the hardware
     * decoder can run out of room, and a software one then takes over.
     */
    internal fun openDecoder(format: MediaFormat, surface: Surface): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("no mime for video track")
        try {
            // Ask the decoder to tone-map HDR sources to SDR.
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        } catch (e: Exception) {
            Log.w(TAG, "could not request SDR color transfer ($e)")
        }
        // Dolby Vision falls back to its HEVC base layer if nothing decodes it.
        val mimes = if (mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) {
            listOf(mime, MediaFormat.MIMETYPE_VIDEO_HEVC)
        } else {
            listOf(mime)
        }
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        var lastError: Exception? = null
        for (type in mimes) {
            val names = codecInfos
                .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(type, ignoreCase = true) } }
                .map { it.name }
                .filterNot { it.endsWith(".secure") }
            for (name in names) {
                val codec = try {
                    MediaCodec.createByCodecName(name)
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
                try {
                    format.setString(MediaFormat.KEY_MIME, type)
                    // The format carries the rotation; the decoder applies it to what it renders.
                    codec.configure(format, surface, null, 0)
                    Log.d(TAG, "decoder $name for $type")
                    return codec
                } catch (e: Exception) {
                    Log.w(TAG, "decoder $name refused $type ($e)")
                    lastError = e
                    codec.release()
                }
            }
        }
        throw lastError ?: IllegalStateException("no decoder for $mime")
    }

    private fun decodeSequentially(
        context: Context,
        sourceUri: Uri,
        videoTrackIndex: Int,
        displayWidth: Int,
        displayHeight: Int,
        /** Frames before this are decoded but not read back; decoding stops after [endUs]. */
        startUs: Long,
        endUs: Long,
        onFrame: (Bitmap, Long) -> Unit,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, sourceUri, null)
        extractor.selectTrack(videoTrackIndex)
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val format = extractor.getTrackFormat(videoTrackIndex)

        // Frame-available callbacks need their own thread; the GL work happens on this one.
        val frameThread = HandlerThread("VideoProcessor-Frames")
        frameThread.start()
        val reader = GlFrameReader(displayWidth, displayHeight, Handler(frameThread.looper))

        val bufferInfo = MediaCodec.BufferInfo()
        var decoder: MediaCodec? = null
        var lastGoodBitmap: Bitmap? = null
        var inputDone = false
        var outputDone = false

        try {
            decoder = openDecoder(format, reader.surface)
            decoder.start()
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        val sampleSize = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "decoder INFO_OUTPUT_FORMAT_CHANGED: ${decoder.outputFormat}")
                } else if (outIndex >= 0) {
                    // Before a trimmed start: decoded (the stream needs it) but not read back.
                    val pastEnd = bufferInfo.size > 0 && bufferInfo.presentationTimeUs > endUs
                    val shouldRender = bufferInfo.size > 0 && !pastEnd && bufferInfo.presentationTimeUs >= startUs
                    decoder.releaseOutputBuffer(outIndex, shouldRender)
                    if (pastEnd) outputDone = true
                    if (shouldRender) {
                        val pts = bufferInfo.presentationTimeUs
                        val captured = if (reader.awaitFrame(FRAME_TIMEOUT_MS)) {
                            val (bitmap, frameTimeUs) = reader.readFrame()
                            if (frameTimeUs != pts) Log.w(TAG, "expected the frame at $pts µs, got $frameTimeUs µs")
                            bitmap
                        } else {
                            Log.w(TAG, "no frame from the decoder at pts=$pts; repeating the previous frame")
                            null
                        }
                        // Reuse the previous frame if one goes missing, so timing stays intact.
                        val frameBitmap = captured ?: lastGoodBitmap
                        if (frameBitmap != null) {
                            onFrame(frameBitmap, pts)
                        }
                        if (captured != null) {
                            lastGoodBitmap?.recycle()
                            lastGoodBitmap = captured
                        }
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        } finally {
            lastGoodBitmap?.recycle()
            if (decoder != null) {
                try {
                    decoder.stop()
                } catch (_: IllegalStateException) {
                    // Never started.
                }
                decoder.release()
            }
            reader.release()
            extractor.release()
            frameThread.quitSafely()
        }
    }

    private fun remuxAudio(
        context: Context,
        sourceUri: Uri,
        audioTrackIndex: Int,
        writer: Mp4Writer,
        startUs: Long,
        endUs: Long,
        loopLengthUs: Long,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, sourceUri, null)
        extractor.selectTrack(audioTrackIndex)
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var offsetUs = 0L
        var lastWrittenUs = Long.MIN_VALUE
        try {
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    // The sound ran out: a looping clip starts over if the video goes on.
                    if (loopLengthUs <= 0 || offsetUs + loopLengthUs >= endUs) break
                    offsetUs += loopLengthUs
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    continue
                }
                // Trimmed: sound before the start is skipped, the rest moves up to the result's start.
                if (extractor.sampleTime < startUs) {
                    extractor.advance()
                    continue
                }
                val ptsUs = extractor.sampleTime - startUs + offsetUs
                if (ptsUs >= endUs) break
                // A pass's sound can run a little longer than its picture; the next pass cuts it off.
                if (ptsUs > lastWrittenUs) {
                    val keyFrame = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    info.set(0, size, ptsUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    writer.writeAudio(buffer, info)
                    lastWrittenUs = ptsUs
                }
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }

    internal fun probeSource(context: Context, uri: Uri): Probe {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var videoTrack = -1
        var audioTrack = -1
        var width = 0
        var height = 0
        var fps = 30
        var durationUs = 0L
        var rotation = 0

        for (t in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(t)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrack < 0) {
                videoTrack = t
                width = format.getInteger(MediaFormat.KEY_WIDTH)
                height = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    // Some containers store the frame rate as a float.
                    fps = try {
                        format.getInteger(MediaFormat.KEY_FRAME_RATE)
                    } catch (e: ClassCastException) {
                        format.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt()
                    }
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                }
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                }
            } else if (mime.startsWith("audio/") && audioTrack < 0) {
                audioTrack = t
            }
        }
        extractor.release()

        if (durationUs <= 0) {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            durationUs = ms * 1000
            retriever.release()
        }

        return Probe(durationUs, fps, width, height, rotation, videoTrack, audioTrack)
    }
}
