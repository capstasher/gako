/* GAKO — GIMP Android Kanvas Operator
 * GIMP's layer modes: composites a layer stack exactly the way GIMP 2.10/3 does.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef GAKO_MODES_H
#define GAKO_MODES_H

#include <stdint.h>

/* GimpLayerColorSpace and GimpLayerCompositeMode, as stored in XCF (0 = the mode's default). */
enum { GAKO_SPACE_AUTO = 0, GAKO_SPACE_LINEAR = 1, GAKO_SPACE_NON_LINEAR = 2, GAKO_SPACE_LAB = 3,
       GAKO_SPACE_PERCEPTUAL = 4 };
enum { GAKO_COMPOSITE_AUTO = 0, GAKO_COMPOSITE_UNION = 1, GAKO_COMPOSITE_CLIP_TO_BACKDROP = 2,
       GAKO_COMPOSITE_CLIP_TO_LAYER = 3, GAKO_COMPOSITE_INTERSECTION = 4 };

#define GAKO_MODE_PASS_THROUGH 61

typedef struct
{
  /* Premultiplied RGBA, 8 bits per channel (Android's ARGB_8888), in sRGB. NULL for a group. */
  const uint8_t *pixels;
  int            stride;
  int            x, y, width, height;   /* where the layer sits on the canvas */
  /* Grey layer mask (RGBA, the red channel counts), same size as the layer, or NULL. */
  const uint8_t *mask;
  int            mask_stride;
  float          opacity;
  int            mode;                  /* GIMP layer mode number (GimpLayerMode) */
  int            blend_space;           /* GAKO_SPACE_*, AUTO = the mode's own */
  int            composite_space;
  int            composite_mode;        /* GAKO_COMPOSITE_* */
  int            parent;                /* index of the group it's in, or -1 */
  int            group;
  int            visible;
} GakoLayer;

/*
 * Composites [layers] (bottom to top; a group comes right after its contents) into [out]
 * (premultiplied RGBA 8-bit, out_w × out_h, showing the canvas from out_x, out_y), flattened onto
 * the opaque colour [background] (0xAARRGGBB) if [has_background].
 * Uses up to [threads] threads. Returns 0, or -1 if out of memory.
 */
int gako_composite (const GakoLayer *layers, int n,
                    int out_x, int out_y, int out_w, int out_h,
                    uint8_t *out, int out_stride, int threads,
                    int has_background, uint32_t background);

#endif
