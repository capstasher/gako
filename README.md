# GAKO

**GIMP Android Kanvas Operator**: a phone-first image editor built on GIMP's own engine.

GAKO doesn't run desktop GIMP behind an X server. It cross-compiles the parts of GIMP that do
the actual image work (**babl** for pixel formats and colour, **GEGL** for every filter and
operation) for Android, and puts a touch interface on top designed for one hand (a stylus is welcome, not needed).

## Status: stage 3 (layers and tools)

**Engine**
- babl 0.1.128 + GEGL 0.4.72 + glib, cross-compiled for Android 12+ (API 31), arm64 (and x86_64 for the emulator)
- ~200 GEGL operations with auto-generated controls (sliders, toggles, choices, colours)
- Live preview on a downscaled proxy, then full-resolution apply with progress and cancel

**Images and layers**
- Layered documents: new, duplicate, delete, reorder, merge down, merge visible, flatten, new from visible
- All of GIMP's layer modes (33: multiply, screen, overlay, soft/hard/vivid/pin/linear light, dodge, burn, grain
  extract/merge, divide, HSV/HSL, LCh, luminance…), each in GIMP 2.10+'s linear-light form or its legacy sRGB one,
  composited with GIMP's own formulas: images look as they do in GIMP 3 (checked against GIMP 3.2, within 1/255)
- Layers can be any size and sit anywhere; scale layer, layer to image size, crop to selection or content,
  flip or rotate just the layer (or a whole group), raise to top / lower to bottom, alpha to selection
- Layer groups: nest, collapse, pass-through or isolated with a blend mode and opacity; the arrows move a layer
  into and out of groups; merge a group; groups survive XCF, PSD and ORA in both directions
- Opacity, visibility, lock alpha; layer masks (white, black, from alpha, selection or a grey copy): paint, fill, filter or curve the mask;
  disable, apply or delete it. Masks survive XCF in both directions
- Undo/redo for everything, with GIMP's Undo History; painting only stores the pixels a stroke touched
- Image: canvas size (with anchor), fit canvas to layers, scale image, crop to selection / content, rotate (90° steps or any angle,
  optionally cropping the empty corners away), flip

**Tools** (one finger uses the tool, two fingers always pan and zoom)
- Paint: paintbrush, pencil, airbrush, eraser, clone, heal, smudge, blur, dodge/burn, bucket fill, gradient
  (linear/radial/mirrored, FG→BG or FG→transparent), text (re-editable, any installed or imported font, outline), colour picker;
  round, square or calligraphy brushes with angle, stroke smoothing, mirror symmetry (↔ ↕ ✚), stylus pressure;
  ★ Brushes: built-in brush presets plus your own saved ones;
  long-press with a brush, fill, gradient or text picks the colour under the finger
- Paths: Bézier paths by tapping (corners) and dragging (curves); several named paths per image, each
  with one or more strokes (holes, pieces); selection from path, stroke, fill;
  saved in XCF, so they open as paths in GIMP and back
- Select: rectangle (optionally rounded), ellipse, free (lasso), selection brush (paint it on), fuzzy, by colour,
  **foreground select** (draw roughly around an object, paint over it, and GEGL's matting finds its edge, as in GIMP);
  replace/add/subtract/intersect, feather;
  Select menu: all, none, invert, feather, grow, shrink, border, from layer alpha
- Transform: move, unified transform (scale/rotate/flip with handles, or perspective by dragging corners),
  crop (with aspect presets), warp (push, grow, shrink, swirl, erase, smooth: GIMP's Warp Transform),
  measure (length and angle; Straighten levels a tilted photo, cropping away the corners if you like)
- Filters, Curves and Levels respect the selection; Filters > Repeat Last and Recently Used
- **Filter Brush**: any of the ~200 filters can be painted on where you brush instead of applied everywhere
- Edit: cut/copy/paste (also via Android's clipboard), Layer via Copy/Cut (then drag it away), clear, fill,
  stroke selection (outline in the FG colour at the brush size)

**Files**
- Save projects as **XCF** (GIMP's own format; GIMP 2.10/3.x open them with layers and masks) or OpenRaster;
  File > Save overwrites the project file, and New/Open ask before discarding unsaved work
- Open XCF from any GIMP version (all precisions, RLE/zlib, masks, groups, paths), Photoshop PSD/PSB with layers
  (8/16-bit RGB or grey, masks, blend modes), ORA, and every image Android decodes; an XCF saved back keeps
  each layer's GIMP mode, blend space and composite mode, and everything GAKO doesn't edit: channels, the
  selection, guides, sample points, resolution, comments, ICC profile and metadata, colour tags, locks,
  GIMP text layers (Image > Guides adds and removes guides)
- Export PNG, JPEG, WebP, HEIF, TIFF, GIF, BMP, TGA, ICO, PPM, ORA and layered PSD, at full size or smaller;
  share; "Open with GAKO"
- Take a photo straight into GAKO; a photo's EXIF (camera, date, exposure) carries over to JPEG/PNG/WebP
  exports, its location only if you ask
- Unsaved work is kept in a recovery file and restored if Android closes the app; the start screen lists
  recent images with thumbnails

**Phone-first UI**
- GIMP's menus (File, Edit, Select, Image, Layers, Colours, Filters) along the bottom edge, nested for thumbs;
  a side rail on landscape screens; tablets and unfolded foldables also keep a Layers dock open
- A floating dock with the current tool, FG/BG colours, active layer and undo/redo; tool options in one strip
  above the menus; a pixel grid when zoomed to 800%+, and an optional thirds or square grid (Image menu)
- Two-finger tap undoes, three-finger tap redoes; twist with two fingers to turn the view (snaps back to
  straight); the size slider shows the brush's real size on the image; pens: pressure, a hover outline, the eraser end erases,
  and once a pen is used fingers only navigate (palm rejection)
- Hardware keyboards get GIMP's shortcuts (Ctrl+Z/S/C/V/A, tool letters, [ ] brush size, Enter/Esc)

### Roadmap
1. ✅ **Engine**: GEGL on Android, filters on photos
2. ✅ **Layers**: layered documents, undo, XCF/ORA
3. ✅ **Tools**: paint, select, transform, crop, curves, levels, text
4. **More**: ✅ GIMP's layer modes exactly (linear light, legacy, LCh), then tiled GEGL buffers for huge images,
   plug-ins (Script-Fu / Python)

## Install

Grab the APK from the latest **dev** pre-release (or the `gako-apk` artifact of a workflow run)
and sideload it. Android 12 or newer, 64-bit ARM.

## Building

Everything builds in GitHub Actions (`.github/workflows/build.yml`). Locally you need the Android
SDK + NDK, Python with `meson`, `ninja`, `pkg-config` and Gradle 8.11:

```sh
ANDROID_NDK_HOME=/path/to/ndk native/build-native.sh android-arm64   # engine + bridge
ANDROID_NDK_HOME=/path/to/ndk native/stage-jnilibs.sh android-arm64 arm64-v8a
gradle :app:assembleRelease
```

For the x86_64 emulator, build and stage `android-x86_64` / `x86_64` too and pass the ABIs to Gradle:

```sh
ANDROID_NDK_HOME=/path/to/ndk native/build-native.sh android-x86_64
ANDROID_NDK_HOME=/path/to/ndk native/stage-jnilibs.sh android-x86_64 x86_64
gradle :app:assembleDebug -Pgako.abis=x86_64        # or arm64-v8a,x86_64
gradle :app:testDebugUnitTest                         # JVM tests (curves, flood fill, layer-tree moves)
scripts/emulator.sh start                             # test emulators: as many as this machine runs well
scripts/devtest.sh                                    # on-device tests across them, + JVM tests (~4 s)
```

See [docs/DEVELOPING.md](docs/DEVELOPING.md) for a map of the code and how it was tested.

`native/build-native.sh host` builds the same engine for your Linux machine and a smoke test
(`gako-host-test`) that exercises the bridge without a phone.

### How it fits together

The editor keeps a `DocState` (canvas size, layers bottom to top, active layer, selection). Layers
are immutable values holding mutable ARGB bitmaps; an edit either swaps a layer's bitmap (filters,
transforms) or paints into it and records a patch of just the changed rectangle. Layer modes are
GIMP's own code (`native/bridge/gako_modes.c`, ported from GIMP 3): exports, merges and the canvas
all use it, so what you see is what GIMP shows. Images with only sRGB Normal layers are composited
on the GPU every frame; others show GIMP's composite, refreshed in the background after each edit,
with the layer being edited drawn live on top of what's below it. During a stroke or warp only the
256 px tiles that changed are re-uploaded.


```
app (Kotlin, Jetpack Compose)  ──JNI──▶  libgako.so (native/bridge)
                                              │
                                   GEGL ── babl ── glib / json-glib
                                     │
                       GEGL operations (libgeglop_*.so), babl extensions (libbablext_*.so)
```

Android only packages `lib*.so` files, so GEGL's operation modules and babl's extensions are
shipped with a prefix and symlinked back into the directories GEGL and babl scan at startup.
GEGL's GPL3 operations are enabled because GAKO is GPLv3+.

## Licence

GAKO is free software under the **GNU GPL v3 or later** (see `LICENSE`).
GEGL and babl are © their authors (LGPLv3+/GPLv3+). GAKO is an independent project and is not
made or endorsed by the GIMP team.
