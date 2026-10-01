package com.squareify.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.squareify.app.processing.VideoCollageProcessor
import com.squareify.app.processing.VideoProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Foreground service that renders queued videos one at a time, so a render keeps
 * running when the app goes to the background. Progress goes to [RenderStateHolder].
 */
class RenderService : Service() {

    private data class RenderRequest(
        val id: String,
        val sourceUri: Uri,
        val settings: FrameSettings,
        val displayName: String,
        /** An earlier render of the same item, overwritten instead of adding a copy. */
        val replaceUri: Uri?,
        /** Set for a video collage; then [sourceUri] is unused. */
        val collage: CollageSpec?,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private val queue = ConcurrentLinkedQueue<RenderRequest>()
    private var processingJob: Job? = null
    /**
     * Where each item's latest render was saved. An edit made while the item was still rendering
     * is queued without knowing that file; it overwrites it instead of adding a second copy.
     */
    private val savedUris = ConcurrentHashMap<String, Uri>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.toRenderRequest()
        if (request != null) {
            queue.add(request)
            RenderStateHolder.markQueued(request.id)
        }
        ensureProcessing()
        return START_REDELIVER_INTENT
    }

    private fun Intent.toRenderRequest(): RenderRequest? {
        val id = getStringExtra(EXTRA_ID) ?: return null
        val sourceUri = IntentCompat.getParcelableExtra(this, EXTRA_SOURCE_URI, Uri::class.java) ?: return null
        return RenderRequest(
            id = id,
            sourceUri = sourceUri,
            settings = IntentCompat.getSerializableExtra(this, EXTRA_SETTINGS, FrameSettings::class.java)
                ?: FrameSettings(),
            displayName = getStringExtra(EXTRA_DISPLAY_NAME) ?: "video",
            replaceUri = IntentCompat.getParcelableExtra(this, EXTRA_REPLACE_URI, Uri::class.java),
            collage = IntentCompat.getSerializableExtra(this, EXTRA_COLLAGE, CollageSpec::class.java),
        )
    }

    private fun ensureProcessing() {
        if (processingJob?.isActive == true) return
        processingJob = serviceScope.launch {
            startForeground(NOTIFICATION_ID, buildNotification("Rendering video…", 0f))
            acquireWakeLock()
            try {
                while (true) {
                    val request = queue.poll() ?: break
                    processOne(request)
                }
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun processOne(request: RenderRequest) {
        RenderStateHolder.markStarted(request.id)
        try {
            val outFile = File(cacheDir, "render_${request.id}.mp4")
            val onProgress = { p: Float ->
                RenderStateHolder.updateProgress(request.id, p)
                updateNotification(request.displayName, p)
            }
            val collage = request.collage
            val soundDropped = if (collage != null) {
                VideoCollageProcessor.render(applicationContext, collage.toCollage(), request.settings, outFile, onProgress)
            } else {
                VideoProcessor.render(applicationContext, request.sourceUri, request.settings, outFile, onProgress)
            }
            val savedUri = GallerySaver.saveVideo(
                applicationContext,
                outFile,
                // Collages are already named, e.g. "collage_20261001_120000".
                if (collage != null) request.displayName else outputFileName(request.settings.format, request.displayName),
                replace = request.replaceUri ?: savedUris[request.id],
            )
            savedUri?.let { savedUris[request.id] = it }
            RenderStateHolder.markComplete(
                request.id,
                savedUri,
                // Shown as a badge on the card; the source's audio couldn't be copied.
                warning = if (soundDropped) "No sound" else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "render failed for ${request.displayName}", e)
            RenderStateHolder.markFailed(request.id, e.message ?: e.toString())
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Squareify::RenderWakeLock").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(text: String, progress: Float): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setProgress(100, (100 * progress).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(displayName: String, progress: Float) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID,
            buildNotification("Rendering $displayName… ${(100 * progress).toInt()}%", progress),
        )
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Video rendering", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val TAG = "RenderService"
        private const val CHANNEL_ID = "render"
        private const val NOTIFICATION_ID = 1001

        private const val EXTRA_ID = "id"
        private const val EXTRA_SOURCE_URI = "sourceUri"
        private const val EXTRA_SETTINGS = "settings"
        private const val EXTRA_DISPLAY_NAME = "displayName"
        private const val EXTRA_REPLACE_URI = "replaceUri"
        private const val EXTRA_COLLAGE = "collage"

        fun enqueue(context: Context, item: MediaItem) {
            val intent = Intent(context, RenderService::class.java)
            intent.putExtra(EXTRA_ID, item.id)
            intent.putExtra(EXTRA_SOURCE_URI, item.sourceUri)
            intent.putExtra(EXTRA_SETTINGS, item.settings)
            intent.putExtra(EXTRA_DISPLAY_NAME, item.displayName)
            item.outputUri?.let { intent.putExtra(EXTRA_REPLACE_URI, it) }
            item.collage?.let { intent.putExtra(EXTRA_COLLAGE, it.toSpec()) }
            context.startForegroundService(intent)
        }
    }
}
