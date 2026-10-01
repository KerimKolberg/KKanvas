# kk-Squareify roadmap

## Done
- 1.1: formats (1:1, 4:5, 3:4, 9:16), remembered settings, share in/out, retry, render all,
  "No sound" badge, new name and icon, current toolchain.
- Previews (live, full-screen, hold for original), gradient/custom/photo colours/eyedropper,
  blur strength, border (margin, corners, shadow), grid survives rotation, saves overwrite.
- Video jitter fix (OpenGL frame reader) with on-device render tests.
- Dark mode, settings tabs (Background / Border / Adjust), settings open at launch.
- Move originals to the phone's trash (30 days) + Recently deleted (restore / delete now).

- Selection mode (long-press): trash originals / share / remove the selection, make a collage.
- Photo collages: 13 layouts for 2-9 photos, Fill/Fit, zoom, drag to pan, reorder, spacing;
  backgrounds, border (margin, rounded cells, shadow) and adjustments apply. Saved 2160 px wide.
- Video collages: photos and clips mixed; all clips play at once for the longest clip's length,
  shorter ones freeze or loop; sound from one chosen clip or off. 1080 px wide, 30 fps, rendered
  in the background right after "Create collage".
- Video rendering ~3x faster (encoder no longer waits per frame, faster RGB→YUV), and video
  colours fixed: shadows were crushed and highlights clipped (full-range values read as
  video range). On-device tests for sound, colours and collage timing.
- Collage editor round 2 (user feedback): "Collage" button next to Add (picked media go
  straight into the collage, nothing else saved); move a photo to the cell left/up/down/right
  or hold and drag it onto another cell; dragging and zooming a filled photo follow the finger
  (drawn live, full render after); Blurred fitted cells use a blur of their own photo.
- Looks: 8 one-tap looks (Original, Warm, Cool, Vivid, Film, Faded, B&W, Noir) previewed on
  your photo, and saving your own; new Contrast, Warmth, Fade and Vignette sliders.
- Panorama carousel: one wide photo split into 2-10 slides (auto count from its shape), Fill
  with position or Fit with background, 1:1 / 4:5 / 3:4, looks across the whole panorama;
  slides saved 1440 px wide as carousel_<name>_N and shared in order.
- Text: a caption or title per item (photos, videos, collages, panoramas) in 6 built-in
  fonts, with size, colour, shadow/box backdrop, alignment and height; drawn after the look.
- Frame styles for photos and videos: thin white border, Polaroid (deep bottom edge) and
  film strip (dark bands with sprocket holes along the long sides); corners and shadow apply
  to the frame, and the canvas grows so the photo keeps its resolution.
- Smart crop: the collage editor finds faces (Android's built-in detector) and filled cells
  keep them in view through layout and zoom changes, until the user drags the cell.
- Video tools (single videos, in the edit sheet): trim, speed 0.25x-4x (frames dropped when
  sped up to keep the frame rate), sound on/off, boomerang (cached JPEG frames, max 10 s).
  Trimmed sound is cut to match; sound is left out at other speeds and in boomerangs.
- Watermark: the kk logo (KK letters or the full logo, cut from the logo by
  tools/make-watermark.ps1) in a chosen corner, white / black / teal, size and opacity; on
  everything saved once switched on (Logo tab).
- Carousel canvas (SCRL-style): up to 20 photos placed freely across 2-10 slides, dragged
  across seams, two-finger resize and turn, snapping to slide edges and middles, fit /
  straighten / front / back / remove; "Create" menu (Collage, Carousel, Panorama slides).
- Swipe preview for carousels and panoramas: Instagram-style pager with dots, plus a seam
  check (slivers of a photo on the next slide, slides with nothing on them).
- Saved projects: the grid (every item, its edits, collages, panoramas, carousels) is kept as
  JSON in the app's files and restored at launch; pictures rebuild from the originals, and
  picked media keep read access across restarts (persistable photo-picker permission).

## Next
1. Photo shapes (circle, arch, pill, torn edge); stickers / overlays drawn in-app; per-photo
   edits inside collages; gradient across all slides; undo/redo; "Post to Instagram" button.
2. Maybe: faster video rendering by drawing frames on the GPU instead of the CPU
   (now ~1.3x real time for a 1080 px collage; encoding the frames is the slowest step).

Not wanted: profile-grid splitter.

## Open
- GitHub: user signs in (`gh auth login`), then a private repo `kk-Squareify` and push.
- Windows app (Compose Desktop + FFmpeg with AMD AMF encoding): needs OK for the FFmpeg
  download (gyan.dev ffmpeg-release-essentials.zip, ~110 MB).
- Optional: brand colour theme (teal on navy from the logo) instead of Material You colours.
