# kk-Squareify

Formerly "Squareify". The launcher icon comes from `logo/kk-squareify-logo.jpg`.

Android app that pads photos and videos to Instagram formats (1:1, 4:5, 3:4, 9:16)
with a solid-colour or blurred background, plus brightness, saturation, sharpness
and grain adjustments.

- Photos are saved to `Pictures/Squareify` as soon as they are added or edited.
- Videos are rendered on demand ("Render all" queues every waiting video) by a
  foreground service (`RenderService`) and saved to `Movies/Squareify`, keeping the
  original audio. If the audio can't be copied, the card shows a "No sound" badge.
- Media can be shared into the app from the gallery, and saved results shared out
  (e.g. to Instagram) from the green button on each card or "Share all" in the top bar.
- Backgrounds: solid colour (presets, custom picker, colours from the photo, eyedropper),
  two-colour gradient, or blurred with adjustable strength. Border: margin, rounded
  corners, shadow.
- Live preview while editing, full-screen preview on tap; hold either to see the original.
- Saving again after an edit overwrites the earlier file instead of adding a copy.
- The settings for new media are remembered between launches; the grid survives rotation.

Kotlin + Jetpack Compose, minSdk 31, targetSdk 34.

## Source layout

| File | What it does |
| --- | --- |
| `MainActivity.kt` | Activity, main screen, settings panel, share-in/share-out |
| `MainViewModel.kt` | The grid and its actions: adding, saving photos, rendering videos, edits |
| `MediaCard.kt`, `EditSheet.kt`, `PreviewDialog.kt` | Grid card, edit sheet with live preview, full-screen preview |
| `SettingsControls.kt` | Format, background, border and adjustment controls, colour picker |
| `MediaItem.kt` | `MediaItem`, `FrameSettings`, `FrameFormat`, `Border`, `Adjustments` |
| `MediaLoading.kt` | Loading photos/video frames, thumbnails, output file names |
| `GallerySaver.kt` | Saves to Pictures/ and Movies/Squareify, overwriting earlier saves |
| `SettingsStore.kt` | Remembers the settings for new media |
| `RenderService.kt`, `RenderStateHolder.kt` | Background video rendering and its progress |
| `processing/PhotoProcessor.kt` | Padding to a format, backgrounds, border, colour/sharpen/grain |
| `processing/VideoProcessor.kt` | MediaCodec decode, pad each frame, H.264 encode, audio remux |
| `processing/ColorExtractor.kt` | Suggested background colours from a photo |
| `processing/StackBlur.kt` | Fast blur used for the blurred background |
| `processing/YuvImageWriter.kt` | Writes ARGB frames into the encoder's YUV input |

## Build

Open this folder in Android Studio and run the `app` configuration, or from a terminal:

```
gradlew assembleDebug testDebugUnitTest
```

The APK ends up in `app/build/outputs/apk/debug/`. GitHub Actions
(`.github/workflows/android.yml`) builds it and runs the tests on every push.

`app/debug.keystore` is a debug-only signing key, checked in so local and CI builds
can update each other on the phone. A Play Store release would need its own private key.

## History

The original project was lost with an old laptop. Its source was recovered on
2026-09-30 by decompiling the debug APK (v1.0) still installed on the phone; class,
function and variable names and UI text match the original. v1.1 moved to the
current Android toolchain and added formats, remembered settings, sharing, retry
and "Render all".
