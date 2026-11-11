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
    Xcf.kt         GIMP XCF writer (v10, zlib) and reader (v0–v20+, all precisions, RLE/zlib, masks)
  editor/
    EditorViewModel.kt  the hub: document + history, files, export/save, layers, selection,
                        edit, text, curves, filters, masks, keyboard, autosave/recovery
    ToolInput.kt        gestures → tools (strokes, selections, fill, gradient, crop, transform)
    TextSession.kt      text tool state and rendering (StaticLayout)
    Curves.kt           Curves/Levels state, LUTs, histogram
  tools/
    Tools.kt            the Tool enum and ToolOptions
    PaintStroke.kt      one brush stroke: paintbrush, pencil, airbrush, eraser, clone, heal,
                        dodge/burn, smudge, blur
  engine/               JNI wrapper around libgako.so (GEGL)
  export/               flat formats (PNG, JPEG, WebP, HEIF, TIFF, GIF, BMP, TGA, ICO, PPM) + ORA/XCF
  ui/                   Compose: EditorScreen (layout, dock), ImageCanvas (GPU drawing + gestures),
                        MenuBar/Menus, ToolOptionsBar (+ ToolPalette), LayersPanel, ParamPanel,
                        CurvesPanel, TextPanel, ExportPanel, Dialogs (incl. colour picker)
native/bridge/          C bridge over GEGL: list/describe/apply operations, bounds, apply_rect
```

## The ideas

- **DocState is a value.** Canvas size, layers (bottom → top), active layer id, selection.
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
`gradle :app:testDebugUnitTest` runs the JVM tests (curves, levels, fuzzy select, by colour).

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

- Layer groups are flattened into their children on XCF import; no group UI.
- Filters that grow a layer (drop shadow…) grow it only within the canvas.
- Very large layers re-upload a whole texture per brush move; tiling the display would help.
- No paths tool, no guides, no brush shapes beyond round.
- ORA bakes masks (the format has none); XCF keeps them.
