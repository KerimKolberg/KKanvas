package com.squareify.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/** The phone's window onto the shared app ([SquareifyScreen]). */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    // Android's own confirmation for moving to / restoring from / emptying the trash.
    private val trashConfirmation = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.platform.trash.onConfirmResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel.platform.trash.confirm = { trashConfirmation.launch(IntentSenderRequest.Builder(it).build()) }
        // Only on a fresh start: after a rotation the shared items are already in the ViewModel.
        if (savedInstanceState == null) {
            viewModel.model.addMedia(intent.sharedMediaUris())
        }
        setContent {
            SquareifyTheme {
                AskForNotifications()
                SquareifyScreen(viewModel.model)
            }
        }
    }

    override fun onDestroy() {
        viewModel.platform.trash.confirm = null
        super.onDestroy()
    }
}

/** Needed for the render progress notification on Android 13+. Rendering works without it. */
@Composable
private fun AskForNotifications() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Media sent to us through the system share sheet ("Share → kkanvas"). */
private fun Intent.sharedMediaUris(): List<Uri> = when (action) {
    Intent.ACTION_SEND ->
        listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
    Intent.ACTION_SEND_MULTIPLE ->
        IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else -> emptyList()
}
