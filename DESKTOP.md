# kk-Squareify for Windows — plan

Goal: a Windows app (.exe) for the ROG Flow Z13 (GZ302EA: Ryzen AI Max+ 395, Radeon 8060S, touch +
pen) with exactly the phone app's functions, and the same results for the same settings.

## The approach: one codebase, two apps

"Same functions, same bugs" only holds if both apps run the **same code**. So instead of writing
a second app, the project becomes **Kotlin Multiplatform** with **Compose Multiplatform**, which
runs the same Kotlin and the same Compose UI on Android and on Windows (desktop JVM):

```
Squareify/
  shared/          Kotlin Multiplatform library: almost everything
    commonMain/    models, geometry, templates, looks, undo, projects (JSON),
                   all drawing (photos, collages, panoramas, carousels, text,
                   stickers, textures, watermark) and the editors' UI
    androidMain/   phone-only parts: MediaCodec video, MediaStore, pickers,
                   trash, sharing, Android's face detector
    desktopMain/   Windows-only parts: FFmpeg video (AMD AMF), files and
                   folders, Recycle Bin, drag and drop
  app/             the Android app: activity, render service, manifest
  desktopApp/      the Windows app: window, menus, packaging to .exe
```

Drawing moves from Android's `android.graphics` to Compose's own graphics API (`ImageBitmap`,
`Canvas`, `Paint`, shaders, colour filters, blend modes), which is backed by Android's graphics
on the phone and by Skia (the same engine underneath Android) on Windows. A handful of things that
API lacks (drop shadows, reading and writing pixels for blur / sharpen / grain) get a small
phone and Windows version each.

**Proof of sameness**: the 64 on-device tests keep guarding the phone app through every step, and
the same picture tests run on Windows too. Reference scenes (each look, frame, shape, collage
layout, template, text, sticker, texture) are rendered on both and compared pixel by pixel.

## Phases

Every phase ends with the phone app unchanged for the user, all phone tests green, a commit, and
(from phase 3 on) a Windows build to try.

0. **Set-up** — Compose Multiplatform version matching Kotlin 2.4.20 / AGP 9.4.1; full JDK with
   jpackage; FFmpeg with AMD AMF; open-licensed fonts (see "Downloads"). A throwaway window on the
   Z13 to confirm Compose, touch and pen behave.
1. **Split the project** — add `shared` (Android + desktop targets) and move the platform-free
   code: data models, collage / panorama / carousel geometry, templates, looks, video edits, undo
   history, project JSON (org.json → kotlinx.serialization-compatible plain JSON, same file
   format). Phone app unchanged.
2. **Shared drawing** — port PhotoProcessor, CollageRenderer, StripRenderer, CarouselRenderer,
   PhotoShapes, TextRenderer, StickerRenderer, Textures, WatermarkRenderer, StackBlur to
   commonMain on Compose graphics. Bundle the six caption fonts so text is identical on both.
   Phone tests prove nothing changed; the same tests run on Windows.
3. **Windows app, photos** — window with the grid, settings panel, editors (shared UI), full-size
   preview, saving to Pictures\kk-Squareify with the same names, saved projects in %APPDATA%,
   add photos via the file picker or by dragging files in from Explorer. First .exe.
4. **Windows video** — FFmpeg decodes, the shared code draws each frame, FFmpeg encodes on the
   Radeon with AMF (h264_amf; software x264 if AMF isn't there). Trim, speed, mute, boomerang,
   video collages, sound kept / looped exactly like the phone.
5. **Windows touches** — two-pane layout for the big screen (canvas left, controls right);
   keyboard shortcuts (Ctrl+Z / Ctrl+Y, Delete, Ctrl+S); handles to resize and turn photos with
   the pen or mouse (desktop Compose has limited two-finger gestures); smart crop with a face
   detector that works on Windows (and maybe the same one on the phone, for identical crops).
6. **Packaging and CI** — kk-Squareify.exe with the kk icon (portable folder first, installer
   optional), version shared with the phone app, GitHub builds the Windows app on every push.

## Progress

- **Phase 0 — done.** Compose Multiplatform 1.12.1 builds with AGP 9.4.1 / Gradle 9.8 / Kotlin
  2.4.20 (newer than its tested range; deprecation warnings only). Multiplatform Material 3 stays
  on **1.9.0**: it maps to the phone's androidx Material 3 1.4.0, while newer ones are alphas that
  would quietly upgrade the phone app. The phone APK's contents are unchanged by the new modules.
  Temurin 21 (with jpackage) is in `tools/jdk-21`; FFmpeg 9.0.2 in `tools/ffmpeg` has `h264_amf`,
  `hevc_amf`, `av1_amf` and `libx264` (300 frames of 1080×1350: 0.58 s on the Radeon, 0.89 s in
  software). Input test on the Z13: desktop Compose sees **every touch and the pen as one mouse
  pointer**, no multi-touch and no pinch; wheel and touchpad scrolling arrive. So: handles to
  resize and turn, Ctrl+wheel / touchpad to zoom (phase 5). Real two-finger gestures would need
  Windows' pointer messages read natively — possible later, not planned.
- **Phase 1 — done.** In `shared/commonMain` (package `com.squareify.app`, so the phone code
  didn't change its imports): the data models, collage / panorama / carousel geometry, templates,
  video edits, looks, undo history, settings, saved projects. The phone-only types are aliases
  on Android (`MediaUri` = `Uri`, `PlatformBitmap` = `Bitmap`, `PlatformContext` = `Context`),
  settings go through a small `Preferences` interface (SharedPreferences on the phone, a
  .properties file in %APPDATA%\kk-Squareify on Windows), and JSON is kotlinx.serialization's
  instead of Android's org.json — same file format, and files written by the old code load the
  same (tested). The shared tests run on the desktop JVM (`./gradlew :shared:desktopTest`);
  the phone's 64 on-device tests pass.
- **Phase 2 — done.** All renderers (photos, frames, collages,
  panoramas, carousels, shapes, text, stickers, textures, watermark, blur, colour suggestions)
  are in `shared/commonMain/.../processing` on Compose graphics. What that API lacks is in
  `Graphics.kt` with a phone and a Windows version: shadows (Android's setShadowLayer; Skia's
  drop shadow with the same radius-to-sigma rule), image shaders with a crop, pixels in and out,
  fonts, PNG decoding. The colour matrix is built exactly like Android's ColorMatrix, so the
  phone's colours don't move. Captions use six bundled fonts (Roboto Bold, Noto Serif Italic,
  Cutive Mono, Dancing Script Bold, Coming Soon, Roboto Condensed Bold; open licences, 3.5 MB)
  instead of the phone's system fonts, so text is the same on both — captions on the phone look
  slightly different from before. The renderers still take and return the platform's bitmap,
  so the phone code around them hardly changed. The picture tests moved to `shared/commonTest`:
  all 85 shared tests pass on Windows and on the phone (as a separate test app), and the app's
  21 remaining on-device tests (video, saving, projects) pass too.
- **Phase 3 — done.** The grid's logic is `AppModel` and every screen and editor is in
  `shared/commonMain`; the phone keeps a thin `MainActivity` and `AndroidPlatform` (photo picker
  links, MediaStore saving, the render service, Android's trash), Windows has `DesktopPlatform`.
  Both implement `AppPlatform`; the few UI parts that differ (pickers, back button, sharing,
  colours) are in `PlatformUi.kt` with a phone and a Windows version. On Windows: photos via
  Skia, turned upright by their EXIF orientation (HEIC through FFmpeg); saving to the real
  Pictures\kk-Squareify (OneDrive-moved folders found through the registry), overwriting the
  earlier file on edit and adding " (1)" for taken names; projects, settings and looks in
  %APPDATA%\kk-Squareify; drag and drop from Explorer.
- **Phase 4 — done.** Videos: FFmpeg decodes (raw frames), the shared renderers draw each frame,
  FFmpeg encodes with `h264_amf` on the Radeon (libx264 if AMF is missing), BT.709 limited range
  and the phone's bitrate rule. Trim, speed (0.25–4×), mute, boomerang and video collages (30 fps,
  as long as the longest clip, freeze or loop, sound from one clip) follow the phone's rules.
  Results go to Videos\kk-Squareify.
- **Phase 5 — done.** Wide windows: settings beside the grid (cards in as many columns as fit), and
  in every editor the preview on the left with the controls on the right. Carousel: the mouse
  wheel over a photo or sticker resizes it, Shift + wheel turns it (touch and pen arrive as one
  mouse pointer on Windows, so there's no pinch). Editors: Ctrl+Z / Ctrl+Y undo and redo, Esc
  closes. "Move originals to trash" uses the Recycle Bin, and "Recently deleted" restores from it
  or deletes for good. No face detector on Windows yet: filled collage cells start centred.
- **Phase 6 — done for the app.** `./gradlew :desktopApp:createDistributable` (with DESKTOP_JDK set
  to a JDK with jpackage) makes desktopApp/build/compose/binaries/main/app/kk-Squareify: the
  .exe, its own Java runtime and FFmpeg (copied in from tools/ffmpeg, not kept in git), with the
  kk icon (tools/make-windows-icon.ps1). Not yet: an installer, and GitHub building the Windows app.
- **Tests on Windows** (`./gradlew :desktopApp:test`): photos (EXIF, downscaling), FFmpeg and AMF,
  every video edit and video collages, the app end to end (saving, renaming, collages, carousels,
  panoramas, videos, restart), the Recycle Bin, screenshots of every screen
  (desktopApp/build/screens) and the carousel's mouse wheel. On the phone, the shared tests'
  test app also opens and closes every screen (`ScreensTest`).

## What's the same, what differs

| Feature | Windows |
|---|---|
| Formats, backgrounds, blur, gradient direction, border, frames, shapes | Same code |
| Looks, adjustments, textures, text, watermark | Same code |
| Collages (photo and video), panorama slides, carousel canvas, templates, stickers | Same code |
| Undo / redo, swipe preview, saved projects, unsaved-changes question | Same code |
| Photos in | File picker, drag and drop; JPEG/PNG/WebP via Skia, HEIC via FFmpeg |
| Video | FFmpeg + AMD AMF instead of MediaCodec; same frames drawn, same timing rules |
| Saving | Pictures\kk-Squareify, same file names, overwrite on edit |
| Move originals to the trash | Windows Recycle Bin |
| Share / Post to Instagram | Share shows the saved file in Explorer; no "Post to Instagram" (Instagram has no Windows app to share to) |
| Smart crop (faces) | Not yet: Android's face detector isn't on Windows; filled cells start centred |
| Two-finger resize / turn | Mouse wheel resizes, Shift + wheel turns; touch and pen work like a mouse |

## Downloads this needs (each only with your OK)

- Compose Multiplatform + Skia for Windows (Gradle libraries, roughly 150 MB)
- FFmpeg for Windows with AMD AMF (gyan.dev "essentials" build, about 110 MB)
- A full JDK with jpackage, e.g. Eclipse Temurin 21 via winget (about 190 MB), to make the .exe
- Fonts for captions, open licences (Roboto, Roboto Condensed, Noto Serif, Cutive Mono, Dancing
  Script, Coming Soon; about 2 MB), bundled in both apps
- Later, optional: a face-detection model and runtime for smart crop on Windows

## Risks

- **The big refactor touches the working phone app.** Small steps, all 64 phone tests after each.
- **Touch on Windows**: desktop Compose turns touch into mouse input; two-finger gestures need
  checking in phase 0, handles are the plan either way.
- **AMF** depends on AMD's driver; software encoding is the fallback (slower, same result).
- **HEIC** photos (Samsung can save those) need FFmpeg on Windows.
