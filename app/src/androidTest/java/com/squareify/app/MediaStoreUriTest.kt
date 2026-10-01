package com.squareify.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreUriTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun photoPickerPhotoMapsToItsGalleryEntry() {
        val picked = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000356542")
        assertEquals(
            "content://media/external/images/media/1000356542",
            mediaStoreUri(context, picked, isVideo = false).toString(),
        )
    }

    @Test
    fun photoPickerVideoMapsToTheVideoCollection() {
        val picked = Uri.parse("content://media/picker_get_content/0/com.android.providers.media.photopicker/media/42")
        assertEquals("content://media/external/video/media/42", mediaStoreUri(context, picked, isVideo = true).toString())
    }

    @Test
    fun cloudOnlyPickerItemHasNoGalleryEntry() {
        val picked = Uri.parse("content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/abc123")
        assertNull(mediaStoreUri(context, picked, isVideo = false))
    }

    @Test
    fun galleryUriIsUsedAsIs() {
        val shared = Uri.parse("content://media/external/images/media/77")
        assertEquals(shared, mediaStoreUri(context, shared, isVideo = false))
    }
}
