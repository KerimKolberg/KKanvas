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
- The format, background and slider settings are remembered between launches.

Kotlin + Jetpack Compose, minSdk 31, targetSdk 34.

## Source layout

| File | What it does |
| --- | --- |
| `MainActivity.kt` | Compose UI: settings panel, media grid, edit sheet, sharing, gallery saving |
| `MediaItem.kt` | `MediaItem`, `FrameSettings`, `FrameFormat`, `Adjustments`, `PaddingStyle` |
| `SettingsStore.kt` | Remembers the settings for new media |
| `RenderService.kt` | Foreground service that renders queued videos |
| `RenderStateHolder.kt` | Shares render progress between the service and the UI |
| `processing/PhotoProcessor.kt` | Padding to a format, blur background, colour/sharpen/grain |
| `processing/VideoProcessor.kt` | MediaCodec decode, pad each frame, H.264 encode, audio remux |
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
