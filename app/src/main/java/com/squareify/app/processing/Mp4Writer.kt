package com.squareify.app.processing

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Encodes bitmaps as H.264 into an MP4, plus optionally one audio track copied as-is from a
 * source ([audioFormat] is that track's format). The requested size is shrunk if the phone's
 * encoder can't handle it, so frames must be drawn at [width] x [height].
 *
 * Use: [encode] every frame, [finishVideo], write any audio with [writeAudio], [close];
 * [release] in a finally block.
 */
internal class Mp4Writer(
    outputFile: File,
    requestedWidth: Int,
    requestedHeight: Int,
    fps: Int,
    private val audioFormat: MediaFormat?,
) {
    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val size = fitToEncoder(encoder, requestedWidth, requestedHeight)
    val width: Int get() = size.first
    val height: Int get() = size.second

    private val muxer: MediaMuxer = try {
        Log.d(TAG, "codec=${encoder.name} requested=${requestedWidth}x$requestedHeight output=${width}x$height fps=$fps")
        configureEncoder(encoder, width, height, fps)
        encoder.start()
        MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } catch (e: Exception) {
        encoder.release()
        throw e
    }

    private var videoTrack = -1

    /** The muxer's audio track; -1 without audio, or if the video never got far enough to add it. */
    var audioTrack = -1
        private set

    private var muxerStarted = false
    private var encoderReleased = false
    private var muxerReleased = false
    private var videoSamplesWritten = 0
    private val bufferInfo = MediaCodec.BufferInfo()

    // YUV 4:2:0: a full-size luma plane plus two quarter-size chroma planes.
    private val frameByteSize = width * height * 3 / 2

    /** [frame] must be [width] x [height]. */
    fun encode(frame: Bitmap, presentationTimeUs: Long) {
        var inputIndex = -1
        while (inputIndex < 0) {
            inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) drain(false)
        }
        encoder.getInputImage(inputIndex)?.let { YuvImageWriter.writeBitmapToImage(frame, it) }
        encoder.queueInputBuffer(inputIndex, 0, frameByteSize, presentationTimeUs, 0)
        drain(false)
    }

    /** Ends the video track; audio can be written afterwards. */
    fun finishVideo() {
        // With Image input, end-of-stream has to be queued as an empty input buffer.
        var eosIndex = -1
        var eosAttempts = 0
        while (eosIndex < 0 && eosAttempts < 200) {
            eosIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (eosIndex < 0) {
                drain(false)
                eosAttempts++
            }
        }
        if (eosIndex >= 0) {
            encoder.queueInputBuffer(eosIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } else {
            Log.e(TAG, "could not obtain an input buffer to submit EOS after $eosAttempts attempts")
        }
        drain(true)
        Log.d(TAG, "video done: samples=$videoSamplesWritten track=$videoTrack muxerStarted=$muxerStarted")
        releaseEncoder()
        check(videoSamplesWritten > 0) { "No video frames were rendered" }
    }

    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        muxer.writeSampleData(audioTrack, buffer, info)
    }

    /** Finishes the file. */
    fun close() {
        releaseEncoder()
        muxer.stop()
        muxer.release()
        muxerReleased = true
    }

    /** Frees whatever is still open; after a failure the file is unusable. */
    fun release() {
        releaseEncoder()
        if (!muxerReleased) {
            muxerReleased = true
            try {
                muxer.release()
            } catch (e: Exception) {
                Log.w(TAG, "muxer release failed", e)
            }
        }
    }

    private fun releaseEncoder() {
        if (encoderReleased) return
        encoderReleased = true
        try {
            encoder.stop()
        } catch (_: IllegalStateException) {
        }
        encoder.release()
    }

    // The muxer can only start once every track is added; the video track's format
    // is only known after the encoder reports INFO_OUTPUT_FORMAT_CHANGED.
    private fun startMuxer() {
        if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
        muxer.start()
        muxerStarted = true
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            // Between frames, take only what's ready: waiting here would stall every frame.
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED: ${encoder.outputFormat}")
                videoTrack = muxer.addTrack(encoder.outputFormat)
                startMuxer()
            } else if (outIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outIndex)
                // Codec config (SPS/PPS) already reached the muxer via the output format.
                val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (bufferInfo.size > 0 && !isConfig && encodedData != null && muxerStarted) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(videoTrack, encodedData, bufferInfo)
                    videoSamplesWritten++
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    private companion object {
        const val TAG = "Mp4Writer"
        const val TIMEOUT_US = 10_000L
        const val I_FRAME_INTERVAL = 2

        /**
         * Shrinks [width] x [height] (keeping its aspect ratio) until [encoder] supports it,
         * e.g. a 4K landscape clip padded to 9:16 is far taller than any phone encoder allows.
         */
        fun fitToEncoder(encoder: MediaCodec, width: Int, height: Int): Pair<Int, Int> {
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

        fun configureEncoder(codec: MediaCodec, width: Int, height: Int, fps: Int) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            val targetBitrate = (width.toLong() * height * fps * 0.09).toInt().coerceIn(3_000_000, 80_000_000)
            format.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            // Say how YuvImageWriter's values are meant, so players don't have to guess.
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)

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
}
