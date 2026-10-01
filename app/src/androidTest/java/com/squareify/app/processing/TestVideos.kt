package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthetic test clips: a white square moving right by [STEP] px per frame over grey, at 30 fps,
 * optionally with a 440 Hz tone as sound.
 */
internal object TestVideos {
    const val FRAME_US = 33_333L
    const val STEP = 20f
    private const val SAMPLE_RATE = 44_100

    /** With a 90° rotation hint, a landscape-coded clip looks like a portrait phone recording. */
    fun create(
        file: File,
        frames: Int,
        rotation: Int = 0,
        width: Int = 1920,
        height: Int = 1080,
        step: Float = STEP,
        withSound: Boolean = false,
    ) {
        val video = encodeVideo(frames, width, height, step)
        val audio = if (withSound) encodeTone(frames * FRAME_US) else null
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        val videoTrack = muxer.addTrack(video.format)
        val audioTrack = audio?.let { muxer.addTrack(it.format) } ?: -1
        muxer.start()
        video.samples.forEach { muxer.writeSampleData(videoTrack, ByteBuffer.wrap(it.data), it.info) }
        audio?.samples?.forEach { muxer.writeSampleData(audioTrack, ByteBuffer.wrap(it.data), it.info) }
        muxer.stop()
        muxer.release()
    }

    /** Duration in µs of the file's first track of [mimePrefix] ("audio/", "video/"), or null if it has none. */
    fun trackDurationUs(file: File, mimePrefix: String): Long? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            for (t in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(t)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true) {
                    return format.getLong(MediaFormat.KEY_DURATION)
                }
            }
            return null
        } finally {
            extractor.release()
        }
    }

    private class Sample(val data: ByteArray, val info: MediaCodec.BufferInfo)
    private class Track(val format: MediaFormat, val samples: List<Sample>)

    private fun encodeVideo(frames: Int, width: Int, height: Int, step: Float): Track {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.WHITE
        val square = height / 9f
        val top = (height - square) / 2
        var frame = 0
        return encodeAll(encoder) { index ->
            if (frame == frames) {
                encoder.queueInputBuffer(index, 0, 0, frame * FRAME_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                false
            } else {
                canvas.drawColor(Color.rgb(64, 64, 64))
                val x = 100f + frame * step
                canvas.drawRect(x, top, x + square, top + square, paint)
                YuvImageWriter.writeBitmapToImage(bitmap, encoder.getInputImage(index)!!)
                encoder.queueInputBuffer(index, 0, width * height * 3 / 2, frame * FRAME_US, 0)
                frame++
                true
            }
        }
    }

    private fun encodeTone(durationUs: Long): Track {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val total = durationUs * SAMPLE_RATE / 1_000_000
        var written = 0L
        return encodeAll(encoder) { index ->
            val buffer = encoder.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            buffer.clear()
            if (written >= total) {
                encoder.queueInputBuffer(index, 0, 0, written * 1_000_000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                false
            } else {
                val count = min(min(1024L, total - written), buffer.remaining() / 2L).toInt()
                for (k in 0 until count) {
                    val t = (written + k).toDouble() / SAMPLE_RATE
                    buffer.putShort((sin(2 * PI * 440 * t) * 8000).toInt().toShort())
                }
                encoder.queueInputBuffer(index, 0, count * 2, written * 1_000_000 / SAMPLE_RATE, 0)
                written += count
                true
            }
        }
    }

    /** Runs [encoder] to the end; [queueNext] fills and queues one input buffer, returning false once it queued the end. */
    private fun encodeAll(encoder: MediaCodec, queueNext: (inputIndex: Int) -> Boolean): Track {
        encoder.start()
        val samples = mutableListOf<Sample>()
        var format: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) inputDone = !queueNext(index)
            }
            val out = encoder.dequeueOutputBuffer(info, 10_000)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                format = encoder.outputFormat
            } else if (out >= 0) {
                val data = encoder.getOutputBuffer(out)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val bytes = ByteArray(info.size)
                    data.position(info.offset)
                    data.get(bytes)
                    val flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                    samples += Sample(bytes, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, flags) })
                }
                encoder.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        encoder.stop()
        encoder.release()
        return Track(format!!, samples)
    }
}
