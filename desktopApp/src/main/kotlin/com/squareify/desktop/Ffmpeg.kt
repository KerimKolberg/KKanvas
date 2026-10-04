package com.squareify.desktop

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * FFmpeg, run as a separate program: decoding videos (and HEIC photos), encoding on the Radeon
 * with AMD AMF (or in software), copying sound. Bundled with the app; for development it's found
 * in tools/ffmpeg.
 */
object Ffmpeg {
    private val dir: File by lazy {
        listOfNotNull(
            System.getProperty("compose.application.resources.dir")?.let { File(it, "ffmpeg") },
            System.getProperty("kk.ffmpeg.dir")?.let(::File),
        ).firstOrNull { File(it, "ffmpeg.exe").exists() } ?: File(".")
    }

    val ffmpeg: String get() = File(dir, "ffmpeg.exe").takeIf { it.exists() }?.path ?: "ffmpeg"
    val ffprobe: String get() = File(dir, "ffprobe.exe").takeIf { it.exists() }?.path ?: "ffprobe"

    val available: Boolean by lazy {
        try {
            run(listOf(ffmpeg, "-hide_banner", "-version")).exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /** The H.264 encoder to use: the Radeon's if it works here, else software. */
    val h264Encoder: String by lazy {
        val test = run(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=black:s=256x256:d=0.1",
                "-c:v", "h264_amf", "-f", "null", "-",
            ),
        )
        if (test.exitCode == 0) "h264_amf" else "libx264"
    }

    class Result(val exitCode: Int, val output: ByteArray, val errors: String)

    /** Runs [command] to the end, collecting what it prints. */
    fun run(command: List<String>, timeoutSeconds: Long = 600): Result {
        val process = ProcessBuilder(command).start()
        val errors = StringBuilder()
        val errorReader = Thread { process.errorStream.bufferedReader().forEachLine { errors.appendLine(it) } }.apply { start() }
        val output = process.inputStream.readBytes()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
        errorReader.join(2000)
        return Result(process.exitValue(), output, errors.toString())
    }

    /** What ffprobe tells about a video. */
    data class Probe(
        val durationMs: Long,
        val fps: Double,
        /** Size as shown, i.e. after the rotation the phone stored. */
        val width: Int,
        val height: Int,
        val hasAudio: Boolean,
    )

    fun probe(file: File): Probe {
        val result = run(
            listOf(
                ffprobe, "-v", "error", "-show_entries",
                "format=duration:stream=codec_type,width,height,avg_frame_rate,r_frame_rate:stream_side_data=rotation:stream_tags=rotate",
                "-of", "default=noprint_wrappers=0", file.path,
            ),
            timeoutSeconds = 60,
        )
        require(result.exitCode == 0) { "Can't read ${file.name}: ${result.errors.trim()}" }
        val text = result.output.decodeToString()
        var width = 0
        var height = 0
        var fps = 30.0
        var rotation = 0
        var hasAudio = false
        var duration = 0.0
        var type = ""
        text.lineSequence().map { it.trim() }.forEach { line ->
            val key = line.substringBefore('=')
            val value = line.substringAfter('=', "")
            when (key) {
                "[STREAM]" -> type = ""
                "codec_type" -> {
                    type = value
                    if (value == "audio") hasAudio = true
                }
                "width" -> if (type == "video" && width == 0) width = value.toIntOrNull() ?: 0
                "height" -> if (type == "video" && height == 0) height = value.toIntOrNull() ?: 0
                "avg_frame_rate" -> if (type == "video") rate(value)?.let { fps = it }
                "rotation", "TAG:rotate" -> if (type == "video") rotation = value.toDoubleOrNull()?.toInt() ?: rotation
                "duration" -> value.toDoubleOrNull()?.let { if (it > duration) duration = it }
            }
        }
        require(width > 0 && height > 0) { "${file.name} has no picture" }
        val turned = (rotation % 180 + 180) % 180 == 90
        return Probe(
            durationMs = (duration * 1000).toLong(),
            fps = fps.coerceIn(1.0, 60.0),
            width = if (turned) height else width,
            height = if (turned) width else height,
            hasAudio = hasAudio,
        )
    }

    /** "30000/1001" → 29.97. */
    private fun rate(value: String): Double? {
        val parts = value.split('/')
        val n = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val d = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (n > 0 && d > 0) n / d else null
    }

    /** One frame at [timeMs] as PNG bytes, longer side at most [maxSize]. */
    fun frameAt(file: File, timeMs: Long, maxSize: Int): ByteArray {
        val result = run(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", seconds(timeMs), "-i", file.path,
                "-frames:v", "1", "-vf", "scale='min($maxSize,iw)':'min($maxSize,ih)':force_original_aspect_ratio=decrease",
                "-f", "image2pipe", "-vcodec", "png", "-",
            ),
            timeoutSeconds = 60,
        )
        require(result.exitCode == 0 && result.output.isNotEmpty()) { "No frame from ${file.name}: ${result.errors.trim()}" }
        return result.output
    }

    /** A photo FFmpeg can read but Skia can't (HEIC), as PNG bytes. */
    fun decodeToPng(file: File): ByteArray {
        val result = run(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-i", file.path, "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-"))
        require(result.exitCode == 0 && result.output.isNotEmpty()) { "Can't read ${file.name}: ${result.errors.trim()}" }
        return result.output
    }

    fun seconds(ms: Long): String = "%.3f".format(java.util.Locale.US, ms / 1000.0)

    /**
     * Decodes [file] into raw RGBA frames of [width] x [height] at [fps], read with [read]. [filters]
     * go before the scaling (trim, speed).
     */
    class FrameReader(command: List<String>, val width: Int, val height: Int) : AutoCloseable {
        private val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val input: InputStream = process.inputStream.buffered(width * height * 4 * 2)
        private val frame = ByteArray(width * height * 4)

        /** The next frame's bytes (RGBA, row by row), or null at the end. The array is reused. */
        fun next(): ByteArray? {
            var read = 0
            while (read < frame.size) {
                val n = input.read(frame, read, frame.size - read)
                if (n < 0) return null
                read += n
            }
            return frame
        }

        override fun close() {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    fun reader(file: File, width: Int, height: Int, fps: Double, startMs: Long = 0, lengthMs: Long? = null, speed: Float = 1f, loop: Boolean = false): FrameReader {
        val command = buildList {
            addAll(listOf(ffmpeg, "-hide_banner", "-loglevel", "error"))
            if (loop) addAll(listOf("-stream_loop", "-1"))
            if (startMs > 0) addAll(listOf("-ss", seconds(startMs)))
            if (lengthMs != null) addAll(listOf("-t", seconds(lengthMs)))
            addAll(listOf("-i", file.path, "-an"))
            val filters = buildList {
                if (speed != 1f) add("setpts=(PTS-STARTPTS)/$speed")
                add("fps=$fps")
                add("scale=$width:$height:flags=bicubic")
            }
            addAll(listOf("-vf", filters.joinToString(","), "-f", "rawvideo", "-pix_fmt", "rgba", "-"))
        }
        return FrameReader(command, width, height)
    }

    /** Encodes raw RGBA frames written with [write] into an MP4 at [fps], with sound from [audio] if given. */
    class FrameWriter(private val process: Process, private val output: File) : AutoCloseable {
        private val stdin = process.outputStream.buffered(1 shl 20)
        private val errors = StringBuilder()
        private val errorReader = Thread { process.errorStream.bufferedReader().forEachLine { synchronized(errors) { errors.appendLine(it) } } }.apply { start() }

        fun write(rgba: ByteArray) = stdin.write(rgba)

        /** Waits for FFmpeg to finish the file; throws if it failed. */
        fun finish() {
            stdin.close()
            process.waitFor()
            errorReader.join(5000)
            check(process.exitValue() == 0 && output.length() > 0) { "Encoding failed: ${synchronized(errors) { errors.toString().trim().takeLast(400) }}" }
        }

        override fun close() {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Sound for an encode: from [file] starting at [startMs], [lengthMs] long, looped if [loop]. */
    data class Sound(val file: File, val startMs: Long, val lengthMs: Long, val loop: Boolean = false)

    fun writer(output: File, width: Int, height: Int, fps: Double, sound: Sound?, encoder: String = h264Encoder): FrameWriter {
        // As on the phone: bitrate for the size and frame rate, BT.709 limited range.
        val bitrate = (width.toLong() * height * fps * 0.09).toLong().coerceIn(3_000_000, 80_000_000)
        val command = buildList {
            addAll(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"))
            addAll(listOf("-f", "rawvideo", "-pix_fmt", "rgba", "-s", "${width}x$height", "-r", fpsText(fps), "-i", "-"))
            if (sound != null) {
                if (sound.loop) addAll(listOf("-stream_loop", "-1"))
                if (sound.startMs > 0) addAll(listOf("-ss", seconds(sound.startMs)))
                addAll(listOf("-i", sound.file.path))
            }
            addAll(listOf("-map", "0:v"))
            if (sound != null) addAll(listOf("-map", "1:a:0?", "-t", seconds(sound.lengthMs), "-c:a", "aac", "-b:a", "192k"))
            addAll(listOf("-vf", "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p"))
            addAll(listOf("-c:v", encoder, "-b:v", "$bitrate", "-maxrate", "${bitrate * 3 / 2}", "-bufsize", "${bitrate * 2}"))
            addAll(listOf("-g", "${(fps * 2).toInt()}"))
            if (encoder == "h264_amf") addAll(listOf("-quality", "quality", "-rc", "vbr_peak"))
            addAll(listOf("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv"))
            addAll(listOf("-movflags", "+faststart", output.path))
        }
        return FrameWriter(ProcessBuilder(command).start(), output)
    }

    private fun fpsText(fps: Double): String = if (fps == fps.toInt().toDouble()) fps.toInt().toString() else "%.4f".format(java.util.Locale.US, fps)
}
