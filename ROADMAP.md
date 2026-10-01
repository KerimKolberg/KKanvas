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

## Next
1. **Panorama carousel**: split one wide photo into 2-10 seamless slides.
2. **Text**: captions/titles with a few fonts.
3. **Frame styles**: Polaroid, film strip, thin white border.
4. **Smart crop**: keep faces in view when cropping (collages).
5. **Video tools**: trim, mute, speed (slow-mo / timelapse), boomerang.
6. Watermark (kk logo).
7. Maybe: faster video rendering by drawing frames on the GPU instead of the CPU
   (now ~1.3x real time for a 1080 px collage; encoding the frames is the slowest step).

Not wanted: profile-grid splitter.

## Open
- GitHub: user signs in (`gh auth login`), then a private repo `kk-Squareify` and push.
- Windows app (Compose Desktop + FFmpeg with AMD AMF encoding): needs OK for the FFmpeg
  download (gyan.dev ffmpeg-release-essentials.zip, ~110 MB).
- Optional: brand colour theme (teal on navy from the logo) instead of Material You colours.
