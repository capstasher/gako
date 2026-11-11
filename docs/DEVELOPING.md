# Developing GAKO

A map of the code after stage 3 (layers and tools), the ideas it rests on, and how it was tested.

## Where things are

```
app/src/main/java/io/github/capstasher/gako/
  doc/        the document model, no UI
    Document.kt    Layer, DocState, Selection, Step (undo), Bitmaps helpers (maskIn!)
    Compositor.kt  software compositing (flatten, drawLayer with masks, mergeThroughMask)
    DocOps.kt      whole-image operations: rotate, flip, crop, canvas size, scale, merge, flatten
    Selections.kt  masks: from paths, combine, invert, outline (marching ants), flood fill, by colour
    OraFile.kt     OpenRaster read/write (layers; masks are baked in)
    Xcf.kt         GIMP XCF writer (v10, zlib) and reader (v0–v20+, all precisions, RLE/zlib, masks, paths)
    PsdReader.kt   Photoshop PSD/PSB reader (layers, masks, modes; groups flattened)
    PsdWriter.kt   layered PSD export (RLE, masks, modes, Unicode names)
  editor/
    EditorViewModel.kt  the hub: document + history, files, export/save, layers, selection,
                        edit, text, curves, filters, masks, keyboard, autosave/recovery
    ToolInput.kt        gestures → tools (strokes, selections, fill, gradient, crop, transform)
    TextSession.kt      text tool state and rendering (StaticLayout)
    Curves.kt           Curves/Levels state, LUTs, histogram
  tools/
    Tools.kt            the Tool enum and ToolOptions
    PaintStroke.kt      one brush stroke: paintbrush, pencil, airbrush, eraser, clone, heal,
                        dodge/burn, smudge, blur; also the tiles the canvas draws mid-stroke
    PathEditor.kt       the Paths tool's Bézier path and its gestures
    Presets.kt          brush presets (built-in + saved in SharedPreferences as JSON)
    Warp.kt             Warp Transform: a mesh the brush pushes, the layer redrawn through it with
                        drawVertices; lasts across strokes until the layer changes otherwise
  engine/               JNI wrapper around libgako.so (GEGL)
  export/               flat formats (PNG, JPEG, WebP, HEIF, TIFF, GIF, BMP, TGA, ICO, PPM) + ORA/XCF
  ui/                   Compose: EditorScreen (layout, dock), ImageCanvas (GPU drawing + gestures),
                        MenuBar/Menus, ToolOptionsBar (+ ToolPalette), LayersPanel, ParamPanel,
                        CurvesPanel, TextPanel, ExportPanel, PathsDialog, Dialogs (incl. colour picker)
native/bridge/          C bridge over GEGL: list/describe/apply operations, bounds, apply_rect, matting
                        (gegl:matting-global with a trimap, for Foreground Select)
```

## The ideas

- **DocState is a value.** Canvas size, layers (bottom → top), active layer id, selection, paths.
  Groups are entries in the same flat list (`group = true`, a 1×1 placeholder bitmap); a layer's
  `parent` is its group's id, and each group sits directly above its contents, as in a PSD
  (`Tree.range` gives a group's block; `Tree.move` moves items in and out of groups and is unit-tested).
  `Compositor.drawTree` composites recursively (pass-through groups at full opacity aren't isolated).
  `Layer` is an immutable data class holding a *mutable* ARGB_8888 bitmap (premultiplied),
  an offset, opacity, mode, lock-alpha, optional text info and optional mask.
- **Two kinds of undo step.** `Step.Snapshot(before, after)` for anything structural or for
  filters/transforms that produce a new bitmap. `Step.Patch(layerId, rect, before, after, onMask)`
  for painting, which mutates the bitmap in place and stores only the touched rectangle.
  Undo is strictly linear, so patches always land on the bitmap they were recorded against.
- **Everything edits "the target".** `vm.targetOf(doc)` is the active layer, or its mask dressed up
  as an alpha-locked layer when the mask is being edited; `withTarget` puts a new bitmap back.
- **Painting** (`PaintStroke`): dabs build a coverage mask for the whole stroke; the layer is
  re-composited from its untouched copy wherever the mask grew, so opacity doesn't stack within a
  stroke. Clone/Heal/Blur/Dodge reveal a source bitmap through that mask instead of a colour.
- **The canvas is GPU-composited** every frame (`ImageCanvas`), with each layer drawn through
  `Compositor.drawLayer`. In-place pixel edits bump `vm.revision` to trigger a redraw.
- **Long work** goes through `vm.docJob` (background, busy pill, one undo step) or the filter
  session (`startFilter` → previews on proxies → `runCommit`). Filters may grow the layer:
  `gako_core_bounds` reports where an operation draws and `gako_core_apply_rect` renders that area.

## Gotchas

- **ALPHA_8 + DST_IN on a software Canvas doesn't work**: a drawn A8 bitmap is treated as
  *coverage*, so DST_IN leaves pixels alone where the mask is 0. Use `Bitmaps.maskIn(canvas, mask)`
  (a DECAL BitmapShader). The GPU canvas is fine, which is why previews looked right while the
  applied result was wrong.
- Layer masks are opaque grey ARGB bitmaps; `Compositor.maskPaint` turns red into alpha.
- `Bitmap.createBitmap(src, rect)` may return `src` itself for immutable full-size crops.
- Jobs that suspend must re-read `vm.doc` before committing; layer property changes are blocked
  while `busy` is set.

## Building and testing

`native/build-native.sh android-x86_64` + `native/stage-jnilibs.sh android-x86_64 x86_64` build the
engine for the emulator; then `gradle :app:assembleDebug -Pgako.abis=x86_64`.
`gradle :app:testDebugUnitTest` runs the JVM tests (curves, levels, fuzzy select, by colour, layer-tree moves).
Instrumented tests (`app/src/androidTest`) run on a device or emulator: XCF/PSD/ORA round trips
(structure, pixels, masks, paths, kept GIMP modes), Warp, Straighten, layer transforms, and `UiTest`,
which drives the real screens (buttons found by label, the canvas touched at image pixels through
`EditorViewModel.docToView`, the editor's state checked directly). Use a host-GPU emulator
(`-gpu host`); the SwiftShader one can crash.

### The fast loop

Measured on one fast desktop, idle; every script picks what the machine it runs on
supports, so other machines get their own best setup.

- **`scripts/emulator.sh start`** starts the test emulators and keeps them running (`stop`, `status`).
  It finds the SDK and an AVD (or creates one for the host's CPU), tries GPU modes fastest first
  (`host`, the real GPU, needs a desktop session on Linux; then the software `swangle_indirect` and
  `swiftshader_indirect`) and remembers which one boots and draws, boots from a snapshot it saves the
  first time (three emulators in ~8 s instead of ~30 s), runs as many as the CPU and free memory allow
  (3 here, at most 4, 1 without hardware virtualisation), and restarts any that dies (the host-GPU
  emulator can lose its GL context when the desktop is busy). Overrides: `GAKO_AVD`, `GAKO_EMU_GPU`,
  `GAKO_EMULATORS`.
- **`scripts/devtest.sh [Class[#test] …]`** runs the on-device tests across every connected device:
  ~4 s for all 19 on three emulators (plus the 16 JVM unit tests alongside, on a full run), against
  ~8 s on one. It splits the tests so each device gets about the same total time, from per-test
  durations it records on every run (`app/build/devtest/timings.tsv`; without python3 it falls back
  to Android's `numShards`). It builds only the ABIs the devices need (warm daemon, configuration
  cache), installs with plain `adb` only when an APK changed (file hash plus the device's install
  time, so reinstalls elsewhere and emulator snapshots are noticed), never uninstalls, and waits for
  an emulator that's restarting.
- **`scripts/ui.py`** drives the app over adb by what's on screen: `tap "Layers"`, `wait "Saved"`,
  `text`, `ls`, `state`, `img 120 80` / `drag` / `path` at image pixels, `open photo.jpg`,
  `shot name`; join commands with `+`; `-s SERIAL` (or `$ANDROID_SERIAL`) picks a device. Debug builds
  answer from an inspector inside the app (`app/src/debug/…/debug/Inspector.kt`, abstract socket
  `gako-inspector`): ~30 ms a look instead of uiautomator's ~2.3 s, image pixels mapped at any zoom
  or turn, and `state` prints the document, layers, selection, paths, tool and history. Release
  builds don't contain it (ui.py then falls back to uiautomator, view at Fit).
- **`scripts/stress.sh [events] [seed]`** runs the monkey (touch, drag and pinch only) on every
  device at once, each with its own seed and the full number of events, and prints any crash or
  freeze with its stack: 3 × 3000 events in ~28 s.
- **UI tests** (`UiTest`) start each test from a clean app (the previous test's autosave is
  removed first). Most of their ~0.8 s each is the test framework launching and closing the activity;
  sharing one activity between tests would be faster but would let tests affect each other.

Stage 3 was tested on an Android 15 x86_64 emulator (Pixel 7 profile), driving it with
`adb shell input` and screenshots, plus:

- **XCF against real GIMP 3.2** (`gimp-console --batch-interpreter=python-fu-eval`): files GAKO
  writes open in GIMP with layers, offsets, modes, masks and the text parasite intact, and GIMP's
  flattened render matches GAKO's export within 0.1% RMSE. Files GIMP writes (8-bit and 16-bit
  linear, masks, groups, hidden layers, multiply at 80%) open in GAKO looking the same.
- A quick way to open any XCF in the emulator: copy it to the app's `files/recovery.xcf`
  (`adb shell run-as io.github.capstasher.gako ...`) and launch; GAKO restores it.
- Painting on a 12 MP image runs at ~30 ms/frame on the emulator's software GPU.

## Known limits / next steps

- GIMP 2.10+'s default ("non-legacy") modes blend in linear light; GAKO blends in sRGB like GIMP's
  legacy modes (which it writes). The difference is large for semi-transparency: black at 50% over
  white is 188 in GIMP 3.2's Normal, 127 in GAKO (and in GIMP's Normal (legacy)). Doing it right needs
  a linear compositor for the screen too (AGSL on Android 13+, or tiled F16 compositing on the CPU).
  Meanwhile `Layer.xcfMode` keeps each layer's GIMP mode number from the file and writes it back while
  the mode is unchanged, so GIMP → GAKO → GIMP keeps the look in GIMP (and modes GAKO shows as Normal,
  like Grain merge or Dissolve, survive).
- Group masks aren't supported (groups have no mask); filters and painting work on layers, not groups.
- Filters that grow a layer (drop shadow…) grow it only within the canvas.
- During a stroke the canvas draws the pre-stroke copy plus 256 px tiles; other edits on huge layers
  still re-upload the whole texture once per change.
- Paths: any number per image, each with several strokes (`EditorViewModel.pathIndex`/`strokeIndex` pick the
  one the tool edits; one past the end means "new"). Path edits aren't undo steps of their own (the tool
  has Undo point); rename/duplicate/reorder/delete are. No guides.
- ORA bakes masks (the format has none); XCF keeps them.
