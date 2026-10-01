package com.squareify.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.squareify.app.CellFit
import com.squareify.app.Collage
import com.squareify.app.CollageCell
import com.squareify.app.FrameSettings
import com.squareify.app.ShortClips
import com.squareify.app.videoCollageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a collage with video clips in it. All clips play at once and the collage lasts as long
 * as the longest one; shorter clips freeze on their last frame or loop. The sound comes from one
 * chosen clip, or there is none. Photos in the collage stay still.
 */
object VideoCollageProcessor {
    private const val TAG = "VideoCollage"
    const val FPS = 30

    /**
     * [onProgress] runs from 0 to 0.9 while encoding, 0.92 after audio, 1 when finished.
     * Returns true if the chosen clip had sound but it couldn't be copied into the output.
     */
    suspend fun render(
        context: Context,
        collage: Collage,
        settings: FrameSettings,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        val probes = collage.cells.map { if (it.isVideo) VideoProcessor.probeSource(context, it.sourceUri) else null }
        val durationUs = probes.maxOf { it?.durationUs ?: 0L }
        require(durationUs > 0) { "None of the clips could be read" }
        val loop = collage.shortClips == ShortClips.LOOP
        val soundIndex = collage.soundCell?.takeIf { (probes.getOrNull(it)?.audioTrackIndex ?: -1) >= 0 }
        val audioFormat = soundIndex?.let { VideoProcessor.audioFormat(context, collage.cells[it].sourceUri, probes[it]!!) }

        val (requestedWidth, requestedHeight) = videoCollageSize(settings.format)
        val writer = Mp4Writer(outputFile, requestedWidth, requestedHeight, FPS, audioFormat)
        // Frame-available callbacks need their own thread; the GL work happens on this one.
        val frameThread = HandlerThread("VideoCollage-Frames")
        frameThread.start()
        val clips = arrayOfNulls<ClipReader>(collage.cells.size)
        val photos = arrayOfNulls<Bitmap>(collage.cells.size)
        try {
            val handler = Handler(frameThread.looper)
            val rects = CollageRenderer.cellRects(collage, settings, writer.width, writer.height)
            collage.cells.forEachIndexed { i, cell ->
                val rect = rects.getOrNull(i) ?: return@forEachIndexed
                val probe = probes[i]
                if (probe != null) {
                    val (w, h) = readSize(probe.displayWidth, probe.displayHeight, rect, cell)
                    Log.d(TAG, "clip $i: $probe read at ${w}x$h")
                    clips[i] = ClipReader(context, cell.sourceUri, probe, w, h, handler, loop)
                } else {
                    val needed = (max(rect.width(), rect.height()) * cell.zoom * 2).roundToInt().coerceIn(512, 4000)
                    photos[i] = PhotoProcessor.loadDownscaledBitmap(context, cell.sourceUri, needed)
                }
            }

            val frameUs = 1_000_000L / FPS
            // Rounded up, so the longest clip's last frame is included.
            val frameCount = ((durationUs * FPS + 999_999) / 1_000_000).toInt()
            var decodeMs = 0L
            var drawMs = 0L
            var encodeMs = 0L
            for (i in 0 until frameCount) {
                val timeUs = i * 1_000_000L / FPS
                val t0 = SystemClock.elapsedRealtime()
                val sources = collage.cells.indices.map { c -> clips[c]?.frameAt(timeUs, frameUs / 2) ?: photos[c] }
                val t1 = SystemClock.elapsedRealtime()
                val frame = CollageRenderer.render(collage, sources, settings, writer.width, writer.height)
                val t2 = SystemClock.elapsedRealtime()
                writer.encode(frame, timeUs)
                encodeMs += SystemClock.elapsedRealtime() - t2
                drawMs += t2 - t1
                decodeMs += t1 - t0
                frame.recycle()
                onProgress(0.9f * (i + 1) / frameCount)
            }
            Log.d(TAG, "$frameCount frames: decode ${decodeMs}ms, draw ${drawMs}ms, encode ${encodeMs}ms")
            writer.finishVideo()
            onProgress(0.92f)

            val soundDropped = if (soundIndex != null) {
                val probe = probes[soundIndex]!!
                VideoProcessor.copySound(
                    context,
                    collage.cells[soundIndex].sourceUri,
                    probe,
                    writer,
                    endUs = frameCount * 1_000_000L / FPS,
                    loopLengthUs = if (loop) clips[soundIndex]?.passLengthUs ?: probe.durationUs else 0L,
                )
            } else {
                false
            }
            writer.close()
            onProgress(1f)
            soundDropped
        } finally {
            clips.forEach { it?.release() }
            photos.forEach { it?.recycle() }
            writer.release()
            frameThread.quitSafely()
        }
    }

    /**
     * How large to read a clip: twice what its cell shows, so the final downscale stays smooth,
     * but never more than its full size. Keeps the aspect ratio.
     */
    private fun readSize(width: Int, height: Int, rect: RectF, cell: CollageCell): Pair<Int, Int> {
        val shown = when (cell.fit) {
            CellFit.FILL -> max(rect.width() / width, rect.height() / height) * cell.zoom
            CellFit.FIT -> min(rect.width() / width, rect.height() / height)
        }
        val scale = min(1f, shown * 2)
        return max(16, (width * scale).roundToInt() / 2 * 2) to max(16, (height * scale).roundToInt() / 2 * 2)
    }

    /**
     * Decodes one clip in step with the collage's timeline: [frameAt] returns the frame showing at
     * a given time. Decoded frames wait (as a held decoder output buffer) until their time comes.
     * Uses this thread's GL; create, use and release on the render thread.
     */
    private class ClipReader(
        context: Context,
        uri: Uri,
        probe: VideoProcessor.Probe,
        width: Int,
        height: Int,
        handler: Handler,
        private val loop: Boolean,
    ) {
        private val extractor = MediaExtractor()
        private val reader: GlFrameReader
        private val decoder: MediaCodec
        private val info = MediaCodec.BufferInfo()
        private val frameDurationUs = 1_000_000L / probe.fps.coerceIn(1, 240)
        private val durationUs = probe.durationUs

        private var inputDone = false
        private var outputDone = false
        /** A decoded frame not yet shown, and its time within the clip. */
        private var pendingIndex = -1
        private var pendingUs = 0L
        private var lastShownUs = 0L
        /** Timestamp of the clip's first frame; clip times count from there. */
        private var firstPtsUs = -1L
        /** Where the current pass through the clip started on the collage's timeline. */
        private var passStartUs = 0L
        /** Output buffers that came back empty in a row; guards against a stuck decoder. */
        private var idleRounds = 0
        private var current: Bitmap? = null

        /** How long one pass through the clip lasts; a looping clip starts over after this. */
        val passLengthUs: Long get() = max(durationUs, lastShownUs + frameDurationUs)

        init {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(probe.videoTrackIndex)
            reader = GlFrameReader(width, height, handler)
            decoder = try {
                VideoProcessor.openDecoder(extractor.getTrackFormat(probe.videoTrackIndex), reader.surface)
                    .also { it.start() }
            } catch (e: Exception) {
                reader.release()
                extractor.release()
                throw e
            }
        }

        /**
         * The frame showing at [timeUs] on the collage's timeline: the latest one due by then
         * (within [toleranceUs], so slightly uneven timestamps don't skip frames). Once the clip
         * has ended it stays on its last frame, or starts over when looping.
         */
        fun frameAt(timeUs: Long, toleranceUs: Long): Bitmap? {
            while (true) {
                if (pendingIndex >= 0) {
                    val due = timeUs - passStartUs + toleranceUs
                    // The very first frame shows right away, whatever its timestamp.
                    if (current != null && pendingUs > due) return current
                    show()
                    continue
                }
                if (outputDone) {
                    if (loop && current != null && timeUs - passStartUs >= passLengthUs) {
                        restart()
                        continue
                    }
                    return current
                }
                feedInput()
                takeOutput()
            }
        }

        fun release() {
            current?.recycle()
            current = null
            try {
                decoder.stop()
            } catch (_: IllegalStateException) {
            }
            decoder.release()
            reader.release()
            extractor.release()
        }

        /** Fills every free input buffer, so the decoder can work ahead while the others are busy. */
        private fun feedInput() {
            while (!inputDone) {
                val index = decoder.dequeueInputBuffer(0L)
                if (index < 0) return
                val buffer = decoder.getInputBuffer(index)
                val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                if (size < 0) {
                    decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }

        private fun takeOutput() {
            val index = decoder.dequeueOutputBuffer(info, VideoProcessor.TIMEOUT_US)
            if (index < 0) {
                if (++idleRounds > MAX_IDLE_ROUNDS) {
                    Log.w(TAG, "decoder stopped producing frames; holding the last one")
                    outputDone = true
                }
                return
            }
            idleRounds = 0
            val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size > 0) {
                if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                pendingIndex = index
                pendingUs = info.presentationTimeUs - firstPtsUs
            } else {
                decoder.releaseOutputBuffer(index, false)
            }
            if (endOfStream) outputDone = true
        }

        private fun show() {
            decoder.releaseOutputBuffer(pendingIndex, true)
            pendingIndex = -1
            lastShownUs = pendingUs
            if (reader.awaitFrame(VideoProcessor.FRAME_TIMEOUT_MS)) {
                val (bitmap, _) = reader.readFrame()
                current?.recycle()
                current = bitmap
            } else {
                // Keep showing the previous frame, so timing stays intact.
                Log.w(TAG, "no frame from the decoder at ${pendingUs}µs")
            }
        }

        private fun restart() {
            passStartUs += passLengthUs
            decoder.flush()
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            inputDone = false
            outputDone = false
            idleRounds = 0
        }

        private companion object {
            /** About 20 s of 10 ms waits without a frame. */
            const val MAX_IDLE_ROUNDS = 2_000
        }
    }
}
