package com.squareify.app

import android.app.Application
import android.app.PendingIntent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.squareify.app.processing.FaceFinder
import com.squareify.app.processing.PhotoProcessor
import com.squareify.app.processing.loadDownscaledBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The phone's side of [AppPlatform]: MediaStore, the photo picker's links, the render service. */
class AndroidPlatform(private val app: Application) : AppPlatform {
    override val context: PlatformContext get() = app
    override val io = Dispatchers.IO

    private val main = Handler(Looper.getMainLooper())

    override fun message(text: String, long: Boolean) {
        main.post { Toast.makeText(app, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }

    override fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    override fun now(): Long = System.currentTimeMillis()

    override fun open(uri: Uri): OpenedMedia {
        keepAccess(app, uri)
        val type = app.contentResolver.getType(uri)
        val isVideo = type != null && type.startsWith("video/")
        return OpenedMedia(isVideo, queryDisplayName(app, uri) ?: uri.lastPathSegment ?: "media")
    }

    override fun loadPreview(uri: Uri, isVideo: Boolean, maxSize: Int): Bitmap? = loadSourceImage(app, uri, isVideo, maxSize)

    override fun loadPhoto(uri: Uri, maxSize: Int): Bitmap = PhotoProcessor.loadDownscaledBitmap(app, uri, maxSize)

    override fun findFaces(photo: Bitmap): Pair<Float, Float>? = FaceFinder.focus(photo)?.let { it.x to it.y }

    override fun videoDurationMs(uri: Uri): Long? = com.squareify.app.videoDurationMs(app, uri)

    override fun saveImage(bitmap: Bitmap, name: String, replace: Uri?): Uri? = GallerySaver.saveImage(app, bitmap, name, replace)

    override fun deleteOwn(uri: Uri) = GallerySaver.deleteOwn(app, uri)

    override fun renderVideo(item: MediaItem) = RenderService.enqueue(app, item)

    override val renderStates = RenderStateHolder.states

    override fun clearRenderState(id: String) = RenderStateHolder.clear(id)

    override val trash = AndroidTrash(app)
}

/**
 * The phone's trash. Android asks the user itself before anything moves: [confirm] shows its
 * question (set by the activity while it's on screen), [onConfirmResult] brings the answer back.
 */
class AndroidTrash(private val app: Application) : OriginalsTrash {
    var confirm: ((IntentSender) -> Unit)? = null
    private var pending: CompletableDeferred<Boolean>? = null

    override val moveExplanation =
        "Their squared versions are saved, so the original photos and videos can go. " +
            "They move to the phone's trash and are deleted for good after 30 days. " +
            "Until then you can restore them under ⋮ → Recently deleted.\n\n" +
            "Android will ask you to confirm next."

    override val listExplanation =
        "Originals moved to the phone's trash after their squared version was saved. " +
            "Android deletes them for good 30 days after they were moved."

    override val keepsForDays = 30

    fun onConfirmResult(approved: Boolean) {
        pending?.complete(approved)
        pending = null
    }

    override fun trashableUri(item: MediaItem): Uri? = mediaStoreUri(app, item.sourceUri, item.isVideo)

    override suspend fun moveToTrash(uris: List<Uri>) = ask { MediaStore.createTrashRequest(app.contentResolver, uris, true) }

    override suspend fun restore(uris: List<Uri>) = ask { MediaStore.createTrashRequest(app.contentResolver, uris, false) }

    override suspend fun deleteForever(uris: List<Uri>) = ask { MediaStore.createDeleteRequest(app.contentResolver, uris) }

    private suspend fun ask(create: () -> PendingIntent): Boolean {
        val show = confirm ?: return false
        val request = try {
            create()
        } catch (e: Exception) {
            Log.w("Squareify", "Android refused the request", e)
            Toast.makeText(app, "That didn't work: ${e.message ?: e}", Toast.LENGTH_LONG).show()
            return false
        }
        val answer = CompletableDeferred<Boolean>()
        pending?.complete(false)
        pending = answer
        show(request.intentSender)
        return answer.await()
    }

    override fun loadEntries(): List<TrashedOriginal> = TrashStore.load(app)

    override fun saveEntries(entries: List<TrashedOriginal>) = TrashStore.save(app, entries)

    override fun saveThumbnail(uri: Uri, preview: Bitmap?): String? = TrashStore.saveThumbnail(app, uri, preview)

    override fun deleteThumbnail(entry: TrashedOriginal) = TrashStore.deleteThumbnail(entry)

    override fun loadThumbnail(path: String): Bitmap? = BitmapFactory.decodeFile(path)
}
