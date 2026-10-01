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

## Next
1. **Video collages** (photo collages are done; see below for the design)
   - Pick 2-9 items via selection mode, then "Make collage".
   - Layouts: 2 side by side, 2 stacked, 1 big + 2, 2x2, 3x2, 3x3; any of the four formats.
   - Per cell: Fill (crop) or Fit (padding); tap a cell to pan/zoom its crop; drag to swap cells.
   - Spacing between cells, rounded cells, existing backgrounds and adjustments.
   - Videos: all play at once; length = longest clip, shorter ones hold their last frame or
     loop (user's choice); sound from one chosen clip or muted. Needs several decoders at once.
3. **Filters / presets**: one-tap looks (warm, film, B&W, faded) and saving your own.
4. **Panorama carousel**: split one wide photo into 2-10 seamless slides.
5. **Text**: captions/titles with a few fonts.
6. **Frame styles**: Polaroid, film strip, thin white border.
7. **Smart crop**: keep faces in view when cropping (collages).
8. **Video tools**: trim, mute, speed (slow-mo / timelapse), boomerang.
9. Watermark (kk logo).

Not wanted: profile-grid splitter.

## Open
- GitHub: user signs in (`gh auth login`), then a private repo `kk-Squareify` and push.
- Windows app (Compose Desktop + FFmpeg with AMD AMF encoding): needs OK for the FFmpeg
  download (gyan.dev ffmpeg-release-essentials.zip, ~110 MB).
- Optional: brand colour theme (teal on navy from the logo) instead of Material You colours.
