/* GAKO — GIMP Android Kanvas Operator
 * Engine bridge: a small C API over babl/GEGL that the Android layer calls.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef GAKO_CORE_H
#define GAKO_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*GakoProgressFunc) (double progress, void *user_data);

/* Initialise babl + GEGL once. Paths may be NULL to use GEGL's defaults.
 * Returns NULL on success, or a newly allocated error string (free with gako_free). */
char *gako_core_init (const char *ops_dir,
                      const char *babl_dir,
                      const char *swap_dir,
                      int         threads,
                      int         cache_mb);

/* JSON array describing every user-facing operation:
 * [{ "name", "title", "description", "categories": [...], "kind": "filter"|"source"|"composer" }] */
char *gako_core_list_operations (void);

/* JSON object describing one operation and its editable properties. */
char *gako_core_describe (const char *operation);

/* Run `operation` over a premultiplied RGBA u8 image (Android ARGB_8888 memory layout)
 * and write the result, cropped to the same size, into dst.
 * params_json is an object of { property: value }.
 * Returns 0 on success, 1 if cancelled, -1 on error (see *error, free with gako_free). */
int gako_core_apply (const char       *operation,
                     const char       *params_json,
                     const uint8_t    *src,
                     int               width,
                     int               height,
                     int               src_stride,
                     uint8_t          *dst,
                     int               dst_stride,
                     volatile int     *cancel,
                     GakoProgressFunc  progress,
                     void             *progress_data,
                     char            **error);

/* Like gako_core_apply, but renders the output rectangle (out_x, out_y, dst's size) of the result,
 * in the input's coordinates, so filters that reach past the image (drop shadow, glow…) aren't cut off. */
int gako_core_apply_rect (const char       *operation,
                          const char       *params_json,
                          const uint8_t    *src,
                          int               width,
                          int               height,
                          int               src_stride,
                          int               out_x,
                          int               out_y,
                          int               out_w,
                          int               out_h,
                          uint8_t          *dst,
                          int               dst_stride,
                          volatile int     *cancel,
                          GakoProgressFunc  progress,
                          void             *progress_data,
                          char            **error);

/* Where `operation` would draw, given a width×height input. Returns 0 and fills x/y/w/h, or -1. */
int gako_core_bounds (const char *operation,
                      const char *params_json,
                      int         width,
                      int         height,
                      int        *x,
                      int        *y,
                      int        *w,
                      int        *h,
                      char      **error);

/* Foreground matting, as GIMP's Foreground Select does it (gegl:matting-global).
 * src: premultiplied RGBA u8 (as for gako_core_apply). trimap: width×height bytes, 0 = background,
 * 255 = foreground, anything else = unknown. Writes the foreground's alpha (0–255) into alpha_out.
 * There must be some foreground and some background next to the unknown area, or the result is all 0.
 * Returns 0 on success, 1 if cancelled, -1 on error (see *error, free with gako_free). */
int gako_core_matting (const uint8_t    *src,
                       int               width,
                       int               height,
                       int               src_stride,
                       const uint8_t    *trimap,
                       int               trimap_stride,
                       int               iterations,
                       uint8_t          *alpha_out,
                       int               alpha_stride,
                       volatile int     *cancel,
                       GakoProgressFunc  progress,
                       void             *progress_data,
                       char            **error);

void gako_free (void *p);

#ifdef __cplusplus
}
#endif

#endif
