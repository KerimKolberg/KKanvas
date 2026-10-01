# kk-Squareify

Formerly "Squareify". The launcher icon comes from `logo/kk-squareify-logo.jpg`.

Android app that pads photos and videos to Instagram formats (1:1, 4:5, 3:4, 9:16)
with a solid-colour or blurred background, plus one-tap looks (and your own saved ones)
and brightness, contrast, saturation, warmth, fade, sharpness, grain and vignette.

- Photos are saved to `Pictures/Squareify` as soon as they are added or edited.
- Videos are rendered on demand ("Render all" queues every waiting video) by a
  foreground service (`RenderService`) and saved to `Movies/Squareify`, keeping the
  original audio. If the audio can't be copied, the card shows a "No sound" badge.
- Media can be shared into the app from the gallery, and saved results shared out
  (e.g. to Instagram) from the green button on each card or "Share all" in the top bar.
- Backgrounds: solid colour (presets, custom picker, colours from the photo, eyedropper),
  two-colour gradient, or blurred with adjustable strength. Border: margin, rounded
  corners, shadow. Frames: thin white border, Polaroid, film strip.
- Live preview while editing, full-screen preview on tap; hold either to see the original.
- Saving again after an edit overwrites the earlier file instead of adding a copy.
- The settings for new media are remembered between launches; the grid survives rotation.
- Long-press selects items: share them, remove them, move their originals to the phone's
  trash (restorable for 30 days under ⋮ → Recently deleted), or make a collage.
- Collages of 2-9 photos and/or clips in 13 layouts, started from the selection or straight
  from the "Collage" button (then the picked media aren't added or saved on their own). Each
  cell is filled (zoom, drag to pan) or fitted; with the Blurred style a fitted cell is padded
  with a blur of its own photo. Arrows or hold-and-drag swap photos between cells.
  Photo collages are saved 2160 px wide.
- Text tab in each item's editor: a caption or title in one of 6 fonts, with size, colour,
  shadow or box behind it, alignment and height. Looks don't affect it.
- Panorama carousel: one wide photo (picked with "Collage", or "Split into carousel slides" in
  its edit sheet) becomes 2-10 slides that join up seamlessly when swiped; shared in order. With clips in it a collage is a video
  (1080 px wide, 30 fps): all clips play at once for as long as the longest one, shorter
  clips freeze on their last frame or loop, and the sound comes from one chosen clip or none.

Kotlin + Jetpack Compose, minSdk 31, targetSdk 34.

## Source layout

| File | What it does |
| --- | --- |
| `MainActivity.kt` | Activity, main screen, settings panel, share-in/share-out |
| `MainViewModel.kt` | The grid and its actions: adding, saving photos, rendering videos, edits |
| `MediaCard.kt`, `EditSheet.kt`, `PreviewDialog.kt` | Grid card, edit sheet with live preview, full-screen preview |
| `CollageEditor.kt` | Collage sheet: preview to tap/drag, cell controls, layouts, clip options |
| `RecentlyDeleted.kt`, `TrashStore.kt` | Originals moved to the trash, and restoring them |
| `SettingsControls.kt` | Format, background, border and adjustment controls, colour picker |
| `MediaItem.kt` | `MediaItem`, `FrameSettings`, `FrameFormat`, `Border`, `Adjustments` |
| `Collage.kt` | Collage layouts, cells, clip options, cell geometry |
| `Panorama.kt`, `PanoramaEditor.kt` | Carousel slides from one wide photo: geometry and editor |
| `Looks.kt` | Built-in looks and the user's saved ones |
| `MediaLoading.kt` | Loading photos/video frames, thumbnails, collage previews, output file names |
| `GallerySaver.kt` | Saves to Pictures/ and Movies/Squareify, overwriting earlier saves |
| `SettingsStore.kt` | Remembers the settings for new media |
| `RenderService.kt`, `RenderStateHolder.kt` | Background video rendering and its progress |
| `processing/PhotoProcessor.kt` | Padding to a format, backgrounds, border, colour/sharpen/grain |
| `processing/CollageRenderer.kt` | Draws a collage from its cells' pictures |
| `processing/PanoramaRenderer.kt` | Draws a panorama strip, or one slide of it |
| `TextOverlay.kt`, `processing/TextRenderer.kt` | Captions: settings and drawing |
| `processing/VideoProcessor.kt` | Single video: decode, pad each frame, encode, copy the sound |
| `processing/VideoCollageProcessor.kt` | Video collage: one decoder per clip on a shared timeline |
| `processing/GlFrameReader.kt` | Reads decoded frames back through OpenGL |
| `processing/Mp4Writer.kt` | H.264 encoder + MP4 muxer shared by both video paths |
| `processing/ColorExtractor.kt` | Suggested background colours from a photo |
| `processing/StackBlur.kt` | Fast blur used for the blurred background |
| `processing/YuvImageWriter.kt` | Writes ARGB frames into the encoder's YUV input (BT.709 video range) |

On-device tests (`app/src/androidTest`) render synthetic clips and check motion, timing,
sound and colours; install both APKs and run them with
`adb shell am instrument -w com.squareify.app.test/androidx.test.runner.AndroidJUnitRunner`.

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
