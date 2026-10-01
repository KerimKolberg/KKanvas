package com.squareify.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import com.squareify.app.FrameSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Re-encodes a video as an H.264 MP4 padded to its format: every decoded frame goes through
 * [PhotoProcessor.frame], and the original audio track is copied over untouched.
 */
object VideoProcessor {
    private const val TAG = "VideoProcessor"
    private const val TIMEOUT_US = 10_000L
    private const val I_FRAME_INTERVAL = 2

    data class Probe(
        val durationUs: Long,
        val fps: Int,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val videoTrackIndex: Int,
        val audioTrackIndex: Int,
    )

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
        val rotated = probe.rotationDegrees == 90 || probe.rotationDegrees == 270
        val displayWidth = if (rotated) probe.height else probe.width
        val displayHeight = if (rotated) probe.width else probe.height
        val (canvasWidth, canvasHeight) =
            PhotoProcessor.canvasSize(displayWidth, displayHeight, settings.format)
        val fps = probe.fps.coerceIn(1, 60)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        Log.d(TAG, "selected codec=${encoder.name}")
        val (width, height) = fitToEncoder(encoder, canvasWidth, canvasHeight)
        Log.d(TAG, "probe=$probe canvas=${canvasWidth}x$canvasHeight output=${width}x$height fps=$fps")
        configureEncoder(encoder, width, height, fps)
        encoder.start()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        var audioFormat: MediaFormat? = null
        if (probe.audioTrackIndex >= 0) {
            val probeExtractor = MediaExtractor()
            probeExtractor.setDataSource(context, sourceUri, null)
            audioFormat = probeExtractor.getTrackFormat(probe.audioTrackIndex)
            probeExtractor.release()
        }

        var videoSamplesWritten = 0
        // YUV 4:2:0: a full-size luma plane plus two quarter-size chroma planes.
        val frameByteSize = width * height * 3 / 2

        // The muxer can only start once every track is added; the video track's format
        // is only known after the encoder reports INFO_OUTPUT_FORMAT_CHANGED.
        fun maybeStartMuxer() {
            if (muxerStarted || muxerVideoTrack < 0) return
            val format = audioFormat
            if (format != null && muxerAudioTrack < 0) {
                muxerAudioTrack = muxer.addTrack(format)
            }
            muxer.start()
            muxerStarted = true
        }

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) {
                // Only valid for Surface input; ours is Image input, so this normally throws.
                try {
                    encoder.signalEndOfInputStream()
                } catch (_: Exception) {
                }
            }
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) return
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "video INFO_OUTPUT_FORMAT_CHANGED: ${encoder.outputFormat}")
                    muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                    maybeStartMuxer()
                } else if (outIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if (bufferInfo.size > 0 && encodedData != null && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                        videoSamplesWritten++
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }

        fun encodeFrame(bitmap: Bitmap, presentationTimeUs: Long) {
            val framed = PhotoProcessor.frame(bitmap, settings, width, height)
            var inputIndex = -1
            while (inputIndex < 0) {
                inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex < 0) drainEncoder(false)
            }
            encoder.getInputImage(inputIndex)?.let { YuvImageWriter.writeBitmapToImage(framed, it) }
            encoder.queueInputBuffer(inputIndex, 0, frameByteSize, presentationTimeUs, 0)
            if (framed !== bitmap) framed.recycle()
            drainEncoder(false)
        }

        try {
            decodeSequentially(
                context,
                sourceUri,
                probe.videoTrackIndex,
                probe.rotationDegrees,
                displayWidth,
                displayHeight,
            ) { bitmap, ptsUs ->
                encodeFrame(bitmap, ptsUs)
                if (probe.durationUs > 0) {
                    onProgress(min(0.9f, ptsUs.toFloat() / probe.durationUs * 0.9f))
                }
            }

            // With Image input, end-of-stream has to be queued as an empty input buffer.
            var eosIndex = -1
            var eosAttempts = 0
            while (eosIndex < 0 && eosAttempts < 200) {
                eosIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (eosIndex < 0) {
                    drainEncoder(false)
                    eosAttempts++
                }
            }
            if (eosIndex >= 0) {
                encoder.queueInputBuffer(eosIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                Log.e(TAG, "could not obtain an input buffer to submit EOS after $eosAttempts attempts")
            }
            drainEncoder(true)
            Log.d(
                TAG,
                "encode loop done: videoSamplesWritten=$videoSamplesWritten " +
                    "muxerVideoTrack=$muxerVideoTrack muxerStarted=$muxerStarted"
            )
        } finally {
            encoder.stop()
            encoder.release()
        }

        onProgress(0.92f)
        var soundDropped = false
        if (probe.audioTrackIndex >= 0) {
            if (muxerAudioTrack >= 0) {
                try {
                    remuxAudio(context, sourceUri, probe.audioTrackIndex, muxer, muxerAudioTrack)
                } catch (e: Exception) {
                    Log.w(TAG, "audio remux failed, output will have no sound", e)
                    soundDropped = true
                }
            } else {
                Log.w(TAG, "audio track was never added to the muxer, output will have no sound")
                soundDropped = true
            }
        }
        muxer.stop()
        muxer.release()
        onProgress(1f)
        soundDropped
    }

    private fun decodeSequentially(
        context: Context,
        sourceUri: Uri,
        videoTrackIndex: Int,
        rotationDegrees: Int,
        displayWidth: Int,
        displayHeight: Int,
        onFrame: (Bitmap, Long) -> Unit,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, sourceUri, null)
        extractor.selectTrack(videoTrackIndex)
        val format = extractor.getTrackFormat(videoTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("no mime for video track")

        // The decoder outputs frames in the stream's stored (unrotated) orientation.
        val rawWidth = when (rotationDegrees) {
            90, 270 -> displayHeight
            else -> displayWidth
        }
        val rawHeight = when (rotationDegrees) {
            90, 270 -> displayWidth
            else -> displayHeight
        }
        val imageReader = ImageReader.newInstance(rawWidth, rawHeight, PixelFormat.RGBA_8888, 3)

        val decoder = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            if (mime != MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) throw e
            Log.w(TAG, "no decoder for $mime ($e) — falling back to video/hevc base layer")
            format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        }
        try {
            // Ask the decoder to tone-map HDR sources to SDR.
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        } catch (e: Exception) {
            Log.w(TAG, "could not request SDR color transfer ($e)")
        }
        decoder.configure(format, imageReader.surface, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val pixelCopyThread = HandlerThread("VideoProcessor-PixelCopy")
        pixelCopyThread.start()
        val pixelCopyHandler = Handler(pixelCopyThread.looper)
        var lastGoodBitmap: Bitmap? = null
        var inputDone = false
        var outputDone = false

        try {
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
                    val shouldRender = bufferInfo.size > 0
                    decoder.releaseOutputBuffer(outIndex, shouldRender)
                    if (shouldRender) {
                        val captured = acquireBitmapViaPixelCopy(
                            imageReader, displayWidth, displayHeight, pixelCopyHandler
                        )
                        if (captured == null) {
                            Log.w(
                                TAG,
                                "frame capture failed after retries at pts=${bufferInfo.presentationTimeUs}; " +
                                    "duplicating previous frame"
                            )
                        }
                        // Reuse the previous frame on a failed capture so timing stays intact.
                        val frameBitmap = captured ?: lastGoodBitmap
                        if (frameBitmap != null) {
                            onFrame(frameBitmap, bufferInfo.presentationTimeUs)
                        }
                        if (captured != null) {
                            if (lastGoodBitmap !== captured) lastGoodBitmap?.recycle()
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
            decoder.stop()
            decoder.release()
            imageReader.close()
            extractor.release()
            pixelCopyThread.quitSafely()
        }
    }

    /**
     * Waits for the decoded frame to reach [reader], then copies the surface into an ARGB
     * bitmap of the display size with PixelCopy.
     */
    private fun acquireBitmapViaPixelCopy(
        reader: ImageReader,
        width: Int,
        height: Int,
        handler: Handler,
    ): Bitmap? {
        var image: Image? = null
        var attempts = 0
        while (image == null && attempts < 50) {
            image = reader.acquireLatestImage()
            if (image == null) {
                Thread.sleep(2)
                attempts++
            }
        }
        if (image == null) {
            Log.w(TAG, "acquireBitmapViaPixelCopy: no image available after $attempts attempts")
            return null
        }
        image.close()

        repeat(5) { attempt ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            var resultCode = -1
            PixelCopy.request(reader.surface, bitmap, { copyResult ->
                resultCode = copyResult
                latch.countDown()
            }, handler)
            val completed = latch.await(2, TimeUnit.SECONDS)
            if (completed && resultCode == PixelCopy.SUCCESS) return bitmap
            if (!completed) {
                Log.w(TAG, "PixelCopy timed out (attempt ${attempt + 1})")
            } else {
                Log.w(TAG, "PixelCopy failed with result=$resultCode (attempt ${attempt + 1})")
            }
            Thread.sleep(5)
        }
        return null
    }

    private fun remuxAudio(
        context: Context,
        sourceUri: Uri,
        audioTrackIndex: Int,
        muxer: MediaMuxer,
        muxerAudioTrack: Int,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, sourceUri, null)
        extractor.selectTrack(audioTrackIndex)
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            muxer.writeSampleData(muxerAudioTrack, buffer, info)
            extractor.advance()
        }
        extractor.release()
    }

    private fun probeSource(context: Context, uri: Uri): Probe {
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

    /**
     * Shrinks [width] x [height] (keeping its aspect ratio) until [encoder] supports it,
     * e.g. a 4K landscape clip padded to 9:16 is far taller than any phone encoder allows.
     */
    private fun fitToEncoder(encoder: MediaCodec, width: Int, height: Int): Pair<Int, Int> {
        val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            ?: return width / 2 * 2 to height / 2 * 2
        // YUV 4:2:0 needs even dimensions; some encoders require coarser alignment.
        val alignW = max(2, caps.widthAlignment)
        val alignH = max(2, caps.heightAlignment)
        var scale = 1.0
        while (scale > 0.05) {
            val w = (width * scale).toInt() / alignW * alignW
            val h = (height * scale).toInt() / alignH * alignH
            if (w > 0 && h > 0 && caps.isSizeSupported(w, h)) return w to h
            scale *= 0.95
        }
        return width / alignW * alignW to height / alignH * alignH
    }

    private fun configureEncoder(codec: MediaCodec, width: Int, height: Int, fps: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        val targetBitrate = (width.toLong() * height * fps * 0.09).toInt().coerceIn(3_000_000, 80_000_000)
        format.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

        try {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            Log.d(TAG, "creating encoder with format=$format")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Main profile configure failed ($e), retrying with encoder default profile")
            format.removeKey(MediaFormat.KEY_PROFILE)
            Log.d(TAG, "creating encoder with format=$format")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }
}
