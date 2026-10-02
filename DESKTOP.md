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
| Share / Post to Instagram | Open the folder, copy to clipboard, open instagram.com (Instagram has no Windows app to share to) |
| Smart crop (faces) | Different detector (Android's isn't on Windows) |
| Two-finger resize / turn | Handles and mouse wheel; pen works like a mouse |

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
