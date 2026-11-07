# GAKO

**GIMP Android Kanvas Operator**: a phone-first image editor built on GIMP's own engine.

GAKO doesn't run desktop GIMP behind an X server. It cross-compiles the parts of GIMP that do
the actual image work (**babl** for pixel formats and colour, **GEGL** for every filter and
operation) for Android, and puts a touch interface on top designed for one hand and no stylus.

## Status: stage 1 (engine bring-up)

- babl 0.1.128 + GEGL 0.4.72 + glib, cross-compiled for arm64 Android 12+ (API 31)
- ~200 GEGL operations available, with auto-generated controls (sliders, toggles, choices, colours)
- Live preview on a downscaled proxy, then full-resolution apply with progress and cancel
- GIMP-style menus (File, Image, Colours, Filters), nested along the bottom edge for thumbs;
  they become a side rail on landscape screens, tablets and unfolded foldables
- Search across all operations; tap-to-place for filters with a centre point (lens flare, supernova…)
- Undo/redo, rotate/flip, export PNG/JPEG/WebP to `Pictures/GAKO`, share, "Open with GAKO"

### Roadmap
1. ✅ **Engine**: GEGL on Android, filters on photos
2. **Core**: GIMP's core (images, layers, undo, `.xcf`) built headless for Android; documents become tiled GEGL buffers (no size cap)
3. **Tools**: paint, erase, select, move, transform, crop, curves, text, built for touch
4. **More**: paths, masks, plug-ins (Script-Fu / Python), stylus pressure, tablet layout

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

`native/build-native.sh host` builds the same engine for your Linux machine and a smoke test
(`gako-host-test`) that exercises the bridge without a phone.

### How it fits together

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
