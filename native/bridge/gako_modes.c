/* GAKO — GIMP Android Kanvas Operator
 * GIMP's layer modes, ported from GIMP 3 (app/operations/layer-modes and layer-modes-legacy,
 * GPL-3.0-or-later), so a layer stack composites exactly as GIMP draws it: the 2.10+ modes blend
 * in their own space (linear light, perceptual or LCh) and composite in linear light, clipped to
 * the backdrop; the legacy modes work in sRGB; the bottom layer of a stack is drawn as if Normal.
 * A mask's value is its opacity as is (GIMP keeps masks of 8-bit images as linear values).
 *
 * Pixels are processed in 64×64 tiles, in floating point (straight alpha), on several threads.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "gako_modes.h"

#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define TILE 64
#define PX (TILE * TILE)
#define EPSILON 1e-6f
#define SAFE_DIV_MAX (1.0f / EPSILON)
#define MAX_THREADS 16
#define MAX_DEPTH 64        /* nested groups; beyond this the caller falls back to its own drawing */

/* ---- colour spaces --------------------------------------------------------------------- */

enum { SP_LIN, SP_PER, SP_LAB };

#define LUT_N 4096
static float to_lin_lut[LUT_N + 1], to_per_lut[LUT_N + 1], u8_lin[256];
static pthread_once_t lut_once = PTHREAD_ONCE_INIT;

/* In double, so 1 maps to exactly 1 (Hard mix's threshold lies there). */
static float srgb_to_linear_exact (float v)
{ return (float) (v > 0.04045f ? pow ((v + 0.055) / 1.055, 2.4) : v / 12.92); }
static float linear_to_srgb_exact (float v)
{ return (float) (v > 0.0031308f ? 1.055 * pow (v, 1.0 / 2.4) - 0.055 : v * 12.92); }

static void
init_luts (void)
{
  for (int i = 0; i <= LUT_N; i++)
    {
      double v = (double) i / LUT_N;
      to_lin_lut[i] = (float) (v > 0.04045 ? pow ((v + 0.055) / 1.055, 2.4) : v / 12.92);
      to_per_lut[i] = (float) (v > 0.0031308 ? 1.055 * pow (v, 1.0 / 2.4) - 0.055 : v * 12.92);
    }
  for (int i = 0; i < 256; i++)
    {
      double v = i / 255.0;
      u8_lin[i] = (float) (v > 0.04045 ? pow ((v + 0.055) / 1.055, 2.4) : v / 12.92);
    }
}

static inline float
lut (const float *t, float v)
{
  float f = v * LUT_N;
  int i = (int) f;
  return t[i] + (t[i + 1] - t[i]) * (f - i);
}

static inline float to_lin (float v)
{ return v >= 0.0f && v < 1.0f ? lut (to_lin_lut, v) : srgb_to_linear_exact (v); }
static inline float to_per (float v)
{ return v >= 0.0f && v < 1.0f ? lut (to_per_lut, v) : linear_to_srgb_exact (v); }

/* babl's sRGB → XYZ (D50, Bradford-adapted) and its inverse, read from babl itself (its rounding
 * decides which way GIMP's LCh modes turn near-grey colours), and the D50 white. */
static const float RGB_XYZ[9] = { 0.436034858f, 0.385116667f, 0.143051371f,
                                  0.222487941f, 0.716903865f, 0.060608257f,
                                  0.013915884f, 0.097060509f, 0.713928938f };
static const float XYZ_RGB[9] = {  3.134275675f, -1.617276669f, -0.490724057f,
                                  -0.978793621f,  1.916160464f,  0.033452377f,
                                   0.071976326f, -0.228983104f,  1.405716896f };
#define WHITE_X 0.964202880f
#define WHITE_Z 0.824905400f
#define LAB_EPS (216.0f / 24389.0f)
#define LAB_KAPPA (24389.0f / 27.0f)

static inline float lab_f (float t)
{ return t > LAB_EPS ? cbrtf (t) : (LAB_KAPPA * t + 16.0f) / 116.0f; }

static void
lin_to_lab (const float *c, float *o)
{
  float x = (RGB_XYZ[0] * c[0] + RGB_XYZ[1] * c[1] + RGB_XYZ[2] * c[2]) / WHITE_X;
  float y =  RGB_XYZ[3] * c[0] + RGB_XYZ[4] * c[1] + RGB_XYZ[5] * c[2];
  float z = (RGB_XYZ[6] * c[0] + RGB_XYZ[7] * c[1] + RGB_XYZ[8] * c[2]) / WHITE_Z;
  float fx = lab_f (x), fy = lab_f (y), fz = lab_f (z);
  o[0] = 116.0f * fy - 16.0f;
  o[1] = 500.0f * (fx - fy);
  o[2] = 200.0f * (fy - fz);
}

static void
lab_to_lin (const float *c, float *o)
{
  float fy = (c[0] + 16.0f) / 116.0f, fx = fy + c[1] / 500.0f, fz = fy - c[2] / 200.0f;
  float x3 = fx * fx * fx, z3 = fz * fz * fz;
  float x = (x3 > LAB_EPS ? x3 : (116.0f * fx - 16.0f) / LAB_KAPPA) * WHITE_X;
  float y = c[0] > LAB_KAPPA * LAB_EPS ? fy * fy * fy : c[0] / LAB_KAPPA;
  float z = (z3 > LAB_EPS ? z3 : (116.0f * fz - 16.0f) / LAB_KAPPA) * WHITE_Z;
  o[0] = XYZ_RGB[0] * x + XYZ_RGB[1] * y + XYZ_RGB[2] * z;
  o[1] = XYZ_RGB[3] * x + XYZ_RGB[4] * y + XYZ_RGB[5] * z;
  o[2] = XYZ_RGB[6] * x + XYZ_RGB[7] * y + XYZ_RGB[8] * z;
}

/* Converts one colour (3 floats) between spaces. */
static void
convert (const float *c, int from, int to, float *o)
{
  float lin[3];
  if (from == to) { o[0] = c[0]; o[1] = c[1]; o[2] = c[2]; return; }
  switch (from)
    {
    case SP_LIN: lin[0] = c[0]; lin[1] = c[1]; lin[2] = c[2]; break;
    case SP_PER: lin[0] = to_lin (c[0]); lin[1] = to_lin (c[1]); lin[2] = to_lin (c[2]); break;
    default:     lab_to_lin (c, lin); break;
    }
  switch (to)
    {
    case SP_LIN: o[0] = lin[0]; o[1] = lin[1]; o[2] = lin[2]; break;
    case SP_PER: o[0] = to_per (lin[0]); o[1] = to_per (lin[1]); o[2] = to_per (lin[2]); break;
    default:     lin_to_lab (lin, o); break;
    }
}

/* ---- the modes --------------------------------------------------------------------------- */

enum
{
  B_NORMAL, B_MULTIPLY, B_SCREEN, B_OVERLAY, B_DIFFERENCE, B_ADDITION, B_SUBTRACT, B_DARKEN,
  B_LIGHTEN, B_HSV_HUE, B_HSV_SATURATION, B_HSL_COLOR, B_HSV_VALUE, B_DIVIDE, B_DODGE, B_BURN,
  B_HARDLIGHT, B_SOFTLIGHT, B_GRAIN_EXTRACT, B_GRAIN_MERGE, B_VIVID_LIGHT, B_PIN_LIGHT,
  B_LINEAR_LIGHT, B_HARD_MIX, B_EXCLUSION, B_LINEAR_BURN, B_LUMA_DARKEN, B_LUMA_LIGHTEN,
  B_LUMINANCE, B_LCH_HUE, B_LCH_CHROMA, B_LCH_COLOR, B_LCH_LIGHTNESS,
};

enum { K_GENERIC, K_LEGACY, K_REPLACE };

typedef struct
{
  int kind, blend, composite_mode, composite_space, blend_space;
  int blend_mutable;   /* GIMP lets the blend space be changed */
} ModeInfo;

#define LEG(b) { K_LEGACY, b, GAKO_COMPOSITE_CLIP_TO_BACKDROP, SP_PER, SP_PER, 0 }
#define NEW(b, bs, m) { K_GENERIC, b, GAKO_COMPOSITE_CLIP_TO_BACKDROP, SP_LIN, bs, m }

/* GIMP's layer_mode_infos (gimp-layer-modes.c), by mode number. Modes GAKO doesn't draw
 * (dissolve, behind, colour erase, erase, merge, split, anti-erase) show as Normal. */
static const ModeInfo MODES[64] = {
  [0]  = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_PER, SP_PER, 0 },
  [1]  = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_PER, SP_PER, 0 },
  [2]  = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_PER, SP_PER, 0 },
  [3]  = LEG (B_MULTIPLY),  [4]  = LEG (B_SCREEN),        [5]  = LEG (B_SOFTLIGHT),
  [6]  = LEG (B_DIFFERENCE), [7] = LEG (B_ADDITION),      [8]  = LEG (B_SUBTRACT),
  [9]  = LEG (B_DARKEN),    [10] = LEG (B_LIGHTEN),       [11] = LEG (B_HSV_HUE),
  [12] = LEG (B_HSV_SATURATION), [13] = LEG (B_HSL_COLOR), [14] = LEG (B_HSV_VALUE),
  [15] = LEG (B_DIVIDE),    [16] = LEG (B_DODGE),         [17] = LEG (B_BURN),
  [18] = LEG (B_HARDLIGHT), [19] = LEG (B_SOFTLIGHT),     [20] = LEG (B_GRAIN_EXTRACT),
  [21] = LEG (B_GRAIN_MERGE),
  [22] = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_PER, SP_PER, 0 },
  [23] = NEW (B_OVERLAY, SP_PER, 1),
  [24] = NEW (B_LCH_HUE, SP_LAB, 0),  [25] = NEW (B_LCH_CHROMA, SP_LAB, 0),
  [26] = NEW (B_LCH_COLOR, SP_LAB, 0), [27] = NEW (B_LCH_LIGHTNESS, SP_LAB, 0),
  [28] = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_LIN, SP_LIN, 0 },
  [29] = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_LIN, SP_LIN, 0 },
  [30] = NEW (B_MULTIPLY, SP_LIN, 1), [31] = NEW (B_SCREEN, SP_PER, 1),
  [32] = NEW (B_DIFFERENCE, SP_PER, 1), [33] = NEW (B_ADDITION, SP_LIN, 1),
  [34] = NEW (B_SUBTRACT, SP_LIN, 1), [35] = NEW (B_DARKEN, SP_LIN, 0),
  [36] = NEW (B_LIGHTEN, SP_LIN, 0),  [37] = NEW (B_HSV_HUE, SP_PER, 0),
  [38] = NEW (B_HSV_SATURATION, SP_PER, 0), [39] = NEW (B_HSL_COLOR, SP_PER, 0),
  [40] = NEW (B_HSV_VALUE, SP_PER, 0), [41] = NEW (B_DIVIDE, SP_LIN, 1),
  [42] = NEW (B_DODGE, SP_PER, 1),    [43] = NEW (B_BURN, SP_PER, 1),
  [44] = NEW (B_HARDLIGHT, SP_PER, 1), [45] = NEW (B_SOFTLIGHT, SP_PER, 1),
  [46] = NEW (B_GRAIN_EXTRACT, SP_PER, 1), [47] = NEW (B_GRAIN_MERGE, SP_PER, 1),
  [48] = NEW (B_VIVID_LIGHT, SP_PER, 1), [49] = NEW (B_PIN_LIGHT, SP_PER, 1),
  [50] = NEW (B_LINEAR_LIGHT, SP_PER, 1), [51] = NEW (B_HARD_MIX, SP_PER, 1),
  [52] = NEW (B_EXCLUSION, SP_PER, 1), [53] = NEW (B_LINEAR_BURN, SP_PER, 1),
  [54] = NEW (B_LUMA_DARKEN, SP_LIN, 1), [55] = NEW (B_LUMA_LIGHTEN, SP_LIN, 1),
  [56] = NEW (B_LUMINANCE, SP_LIN, 0),
  [57] = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_LIN, SP_LIN, 0 },
  [58] = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_LIN, SP_LIN, 0 },
  [59] = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_LIN, SP_LIN, 0 },
  [60] = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_LIN, SP_LIN, 0 },
  [61] = { K_REPLACE, B_NORMAL, GAKO_COMPOSITE_UNION, SP_LIN, SP_LIN, 0 },
  [62] = { K_REPLACE, B_NORMAL, GAKO_COMPOSITE_UNION, SP_LIN, SP_LIN, 0 },
  [63] = { K_GENERIC, B_NORMAL, GAKO_COMPOSITE_UNION, SP_LIN, SP_LIN, 0 },
};

static int
space_of (int gimp_space, int fallback)
{
  switch (gimp_space)
    {
    case GAKO_SPACE_LINEAR:     return SP_LIN;
    case GAKO_SPACE_NON_LINEAR:
    case GAKO_SPACE_PERCEPTUAL: return SP_PER;
    case GAKO_SPACE_LAB:        return SP_LAB;
    default:                    return fallback;
    }
}

/* The mode as it applies to one layer: GIMP's defaults, with the layer's own settings on top. */
static ModeInfo
resolve (const GakoLayer *l)
{
  ModeInfo m = MODES[l->mode >= 0 && l->mode < 64 ? l->mode : 0];
  if (l->mode < 0 || l->mode >= 64)
    m = MODES[0];
  if (m.kind == K_LEGACY || l->mode <= 2 || l->mode == 22)
    return m;   /* legacy modes can't be changed */
  m.composite_space = space_of (l->composite_space, m.composite_space);
  if (m.composite_space == SP_LAB)
    m.composite_space = SP_LIN;
  if (l->composite_mode >= GAKO_COMPOSITE_UNION && l->composite_mode <= GAKO_COMPOSITE_INTERSECTION)
    m.composite_mode = l->composite_mode;
  if (m.blend_mutable)
    m.blend_space = space_of (l->blend_space, m.blend_space);
  else if (m.blend == B_DARKEN || m.blend == B_LIGHTEN || m.blend == B_NORMAL)
    m.blend_space = m.composite_space;   /* no blend space of their own */
  return m;
}

static inline float
safe_div (float a, float b)
{
  float r = 0.0f;
  if (fabsf (a) > EPSILON)
    {
      r = a / b;
      r = r < -SAFE_DIV_MAX ? -SAFE_DIV_MAX : r > SAFE_DIV_MAX ? SAFE_DIV_MAX : r;
    }
  return r;
}

static inline float minf (float a, float b) { return a < b ? a : b; }
static inline float maxf (float a, float b) { return a > b ? a : b; }
static inline float clamp01 (float v) { return v < 0.0f ? 0.0f : v > 1.0f ? 1.0f : v; }

/* GIMP 2.10+'s blend functions (gimpoperationlayermode-blend.c): in, layer → comp. */
static void
blend_new (int b, const float *in, const float *ly, float *o)
{
  int c;
  switch (b)
    {
    case B_NORMAL:        for (c = 0; c < 3; c++) o[c] = ly[c]; break;
    case B_ADDITION:      for (c = 0; c < 3; c++) o[c] = in[c] + ly[c]; break;
    case B_BURN:          for (c = 0; c < 3; c++) o[c] = 1.0f - safe_div (1.0f - in[c], ly[c]); break;
    case B_DARKEN:        for (c = 0; c < 3; c++) o[c] = minf (in[c], ly[c]); break;
    case B_DIFFERENCE:    for (c = 0; c < 3; c++) o[c] = fabsf (in[c] - ly[c]); break;
    case B_DIVIDE:        for (c = 0; c < 3; c++) o[c] = safe_div (in[c], ly[c]); break;
    case B_DODGE:         for (c = 0; c < 3; c++) o[c] = safe_div (in[c], 1.0f - ly[c]); break;
    case B_EXCLUSION:     for (c = 0; c < 3; c++) o[c] = 0.5f - 2.0f * (in[c] - 0.5f) * (ly[c] - 0.5f); break;
    case B_GRAIN_EXTRACT: for (c = 0; c < 3; c++) o[c] = in[c] - ly[c] + 0.5f; break;
    case B_GRAIN_MERGE:   for (c = 0; c < 3; c++) o[c] = in[c] + ly[c] - 0.5f; break;
    case B_HARD_MIX:      for (c = 0; c < 3; c++) o[c] = in[c] + ly[c] < 1.0f ? 0.0f : 1.0f; break;
    case B_HARDLIGHT:
      for (c = 0; c < 3; c++)
        {
          float v;
          if (ly[c] > 0.5f) { v = (1.0f - in[c]) * (1.0f - (ly[c] - 0.5f) * 2.0f); v = minf (1.0f - v, 1.0f); }
          else              { v = in[c] * (ly[c] * 2.0f); v = minf (v, 1.0f); }
          o[c] = v;
        }
      break;
    case B_LIGHTEN:       for (c = 0; c < 3; c++) o[c] = maxf (in[c], ly[c]); break;
    case B_LINEAR_BURN:   for (c = 0; c < 3; c++) o[c] = in[c] + ly[c] - 1.0f; break;
    case B_LINEAR_LIGHT:
      for (c = 0; c < 3; c++)
        o[c] = ly[c] <= 0.5f ? in[c] + 2.0f * ly[c] - 1.0f : in[c] + 2.0f * (ly[c] - 0.5f);
      break;
    case B_MULTIPLY:      for (c = 0; c < 3; c++) o[c] = in[c] * ly[c]; break;
    case B_OVERLAY:
      for (c = 0; c < 3; c++)
        o[c] = in[c] < 0.5f ? 2.0f * in[c] * ly[c] : 1.0f - 2.0f * (1.0f - ly[c]) * (1.0f - in[c]);
      break;
    case B_PIN_LIGHT:
      for (c = 0; c < 3; c++)
        o[c] = ly[c] > 0.5f ? maxf (in[c], 2.0f * (ly[c] - 0.5f)) : minf (in[c], 2.0f * ly[c]);
      break;
    case B_SCREEN:        for (c = 0; c < 3; c++) o[c] = 1.0f - (1.0f - in[c]) * (1.0f - ly[c]); break;
    case B_SOFTLIGHT:
      for (c = 0; c < 3; c++)
        {
          float mul = in[c] * ly[c], scr = 1.0f - (1.0f - in[c]) * (1.0f - ly[c]);
          o[c] = (1.0f - in[c]) * mul + in[c] * scr;
        }
      break;
    case B_SUBTRACT:      for (c = 0; c < 3; c++) o[c] = in[c] - ly[c]; break;
    case B_VIVID_LIGHT:
      for (c = 0; c < 3; c++)
        {
          float v;
          if (ly[c] <= 0.5f) { v = 1.0f - safe_div (1.0f - in[c], 2.0f * ly[c]); v = maxf (v, 0.0f); }
          else               { v = safe_div (in[c], 2.0f * (1.0f - ly[c])); v = minf (v, 1.0f); }
          o[c] = v;
        }
      break;
    case B_HSL_COLOR:
      {
        float dmin = minf (minf (in[0], in[1]), in[2]), dmax = maxf (maxf (in[0], in[1]), in[2]);
        float smin = minf (minf (ly[0], ly[1]), ly[2]), smax = maxf (maxf (ly[0], ly[1]), ly[2]);
        float dl = (dmin + dmax) / 2.0f, sl = (smin + smax) / 2.0f;
        if (fabsf (sl) > EPSILON && fabsf (1.0f - sl) > EPSILON)
          {
            int dh = dl > 0.5f, sh = sl > 0.5f;
            float ratio, offset = 0.0f;
            dl = minf (dl, 1.0f - dl);
            sl = minf (sl, 1.0f - sl);
            ratio = dl / sl;
            if (dh) offset += 1.0f - 2.0f * dl;
            if (sh) offset += 2.0f * dl - ratio;
            for (c = 0; c < 3; c++) o[c] = ly[c] * ratio + offset;
          }
        else
          o[0] = o[1] = o[2] = dl;
      }
      break;
    case B_HSV_HUE:
      {
        float smin = minf (minf (ly[0], ly[1]), ly[2]), smax = maxf (maxf (ly[0], ly[1]), ly[2]);
        float sd = smax - smin;
        if (sd > EPSILON)
          {
            float dmin = minf (minf (in[0], in[1]), in[2]), dmax = maxf (maxf (in[0], in[1]), in[2]);
            float dd = dmax - dmin, ds = dmax != 0.0f ? dd / dmax : 0.0f;
            float ratio = ds * dmax / sd, offset = dmax - smax * ratio;
            for (c = 0; c < 3; c++) o[c] = ly[c] * ratio + offset;
          }
        else
          for (c = 0; c < 3; c++) o[c] = in[c];
      }
      break;
    case B_HSV_SATURATION:
      {
        float dmin = minf (minf (in[0], in[1]), in[2]), dmax = maxf (maxf (in[0], in[1]), in[2]);
        float dd = dmax - dmin;
        if (dd > EPSILON)
          {
            float smin = minf (minf (ly[0], ly[1]), ly[2]), smax = maxf (maxf (ly[0], ly[1]), ly[2]);
            float ss = smax != 0.0f ? (smax - smin) / smax : 0.0f;
            float ratio = ss * dmax / dd, offset = (1.0f - ratio) * dmax;
            for (c = 0; c < 3; c++) o[c] = in[c] * ratio + offset;
          }
        else
          o[0] = o[1] = o[2] = dmax;
      }
      break;
    case B_HSV_VALUE:
      {
        float dv = maxf (maxf (in[0], in[1]), in[2]), sv = maxf (maxf (ly[0], ly[1]), ly[2]);
        if (fabsf (dv) > EPSILON)
          for (c = 0; c < 3; c++) o[c] = in[c] * (sv / dv);
        else
          o[0] = o[1] = o[2] = sv;
      }
      break;
    case B_LCH_CHROMA:
      {
        float c1 = hypotf (in[1], in[2]);
        o[0] = in[0];
        if (c1 > EPSILON)
          {
            float c2 = hypotf (ly[1], ly[2]);
            o[1] = c2 * in[1] / c1; o[2] = c2 * in[2] / c1;
          }
        else { o[1] = in[1]; o[2] = in[2]; }
      }
      break;
    case B_LCH_COLOR:     o[0] = in[0]; o[1] = ly[1]; o[2] = ly[2]; break;
    case B_LCH_HUE:
      {
        float c2 = hypotf (ly[1], ly[2]);
        o[0] = in[0];
        if (c2 > EPSILON)
          {
            float c1 = hypotf (in[1], in[2]);
            o[1] = c1 * ly[1] / c2; o[2] = c1 * ly[2] / c2;
          }
        else { o[1] = in[1]; o[2] = in[2]; }
      }
      break;
    case B_LCH_LIGHTNESS: o[0] = ly[0]; o[1] = in[1]; o[2] = in[2]; break;
    case B_LUMA_DARKEN:
    case B_LUMA_LIGHTEN:
      {
        float dy = RGB_XYZ[3] * in[0] + RGB_XYZ[4] * in[1] + RGB_XYZ[5] * in[2];
        float sy = RGB_XYZ[3] * ly[0] + RGB_XYZ[4] * ly[1] + RGB_XYZ[5] * ly[2];
        int keep = b == B_LUMA_DARKEN ? dy <= sy : dy >= sy;
        for (c = 0; c < 3; c++) o[c] = keep ? in[c] : ly[c];
      }
      break;
    case B_LUMINANCE:
      {
        float dy = RGB_XYZ[3] * in[0] + RGB_XYZ[4] * in[1] + RGB_XYZ[5] * in[2];
        float sy = RGB_XYZ[3] * ly[0] + RGB_XYZ[4] * ly[1] + RGB_XYZ[5] * ly[2];
        float ratio = safe_div (sy, dy);
        for (c = 0; c < 3; c++) o[c] = in[c] * ratio;
      }
      break;
    default:              for (c = 0; c < 3; c++) o[c] = ly[c]; break;
    }
}

/* ---- the legacy modes' colour models (gimpcolor-legacy.c) ---- */

static void
rgb_to_hsv (const double *rgb, double *hsv)
{
  double max = fmax (rgb[0], fmax (rgb[1], rgb[2])), min = fmin (rgb[0], fmin (rgb[1], rgb[2]));
  double delta = max - min;
  hsv[2] = max;
  if (delta > 0.0001)
    {
      hsv[1] = delta / max;
      if (rgb[0] == max)      { hsv[0] = (rgb[1] - rgb[2]) / delta; if (hsv[0] < 0.0) hsv[0] += 6.0; }
      else if (rgb[1] == max) hsv[0] = 2.0 + (rgb[2] - rgb[0]) / delta;
      else                    hsv[0] = 4.0 + (rgb[0] - rgb[1]) / delta;
      hsv[0] /= 6.0;
    }
  else
    hsv[0] = hsv[1] = 0.0;
}

static void
hsv_to_rgb (const double *hsv, double *rgb)
{
  if (hsv[1] == 0.0)
    { rgb[0] = rgb[1] = rgb[2] = hsv[2]; return; }
  double hue = hsv[0] == 1.0 ? 0.0 : hsv[0];
  hue *= 6.0;
  int i = (int) hue;
  double f = hue - i, v = hsv[2];
  double w = v * (1.0 - hsv[1]), q = v * (1.0 - hsv[1] * f), t = v * (1.0 - hsv[1] * (1.0 - f));
  switch (i)
    {
    case 0:  rgb[0] = v; rgb[1] = t; rgb[2] = w; break;
    case 1:  rgb[0] = q; rgb[1] = v; rgb[2] = w; break;
    case 2:  rgb[0] = w; rgb[1] = v; rgb[2] = t; break;
    case 3:  rgb[0] = w; rgb[1] = q; rgb[2] = v; break;
    case 4:  rgb[0] = t; rgb[1] = w; rgb[2] = v; break;
    default: rgb[0] = v; rgb[1] = w; rgb[2] = q; break;
    }
}

static void
rgb_to_hsl (const double *rgb, double *hsl)
{
  double max = fmax (rgb[0], fmax (rgb[1], rgb[2])), min = fmin (rgb[0], fmin (rgb[1], rgb[2]));
  hsl[2] = (max + min) / 2.0;
  if (max == min)
    { hsl[1] = 0.0; hsl[0] = -1.0; return; }
  hsl[1] = hsl[2] <= 0.5 ? (max - min) / (max + min) : (max - min) / (2.0 - max - min);
  double delta = max - min;
  if (rgb[0] == max)      hsl[0] = (rgb[1] - rgb[2]) / delta;
  else if (rgb[1] == max) hsl[0] = 2.0 + (rgb[2] - rgb[0]) / delta;
  else                    hsl[0] = 4.0 + (rgb[0] - rgb[1]) / delta;
  hsl[0] /= 6.0;
  if (hsl[0] < 0.0) hsl[0] += 1.0;
}

static double
hsl_value (double n1, double n2, double hue)
{
  if (hue > 6.0) hue -= 6.0; else if (hue < 0.0) hue += 6.0;
  if (hue < 1.0) return n1 + (n2 - n1) * hue;
  if (hue < 3.0) return n2;
  if (hue < 4.0) return n1 + (n2 - n1) * (4.0 - hue);
  return n1;
}

static void
hsl_to_rgb (const double *hsl, double *rgb)
{
  if (hsl[1] == 0)
    { rgb[0] = rgb[1] = rgb[2] = hsl[2]; return; }
  double m2 = hsl[2] <= 0.5 ? hsl[2] * (1.0 + hsl[1]) : hsl[2] + hsl[1] - hsl[2] * hsl[1];
  double m1 = 2.0 * hsl[2] - m2;
  rgb[0] = hsl_value (m1, m2, hsl[0] * 6.0 + 2.0);
  rgb[1] = hsl_value (m1, m2, hsl[0] * 6.0);
  rgb[2] = hsl_value (m1, m2, hsl[0] * 6.0 - 2.0);
}

/* The legacy operations' comp (the files in layer-modes-legacy). */
static void
blend_legacy (int b, const float *in, const float *ly, float *o)
{
  int c;
  switch (b)
    {
    case B_MULTIPLY:      for (c = 0; c < 3; c++) o[c] = ly[c] * in[c]; break;
    case B_SCREEN:        for (c = 0; c < 3; c++) o[c] = 1.0f - (1.0f - in[c]) * (1.0f - ly[c]); break;
    case B_SOFTLIGHT:
      for (c = 0; c < 3; c++)
        {
          float mul = in[c] * ly[c], scr = 1.0f - (1.0f - in[c]) * (1.0f - ly[c]);
          o[c] = (1.0f - in[c]) * mul + in[c] * scr;
        }
      break;
    case B_DIFFERENCE:    for (c = 0; c < 3; c++) o[c] = fabsf (in[c] - ly[c]); break;
    case B_ADDITION:      for (c = 0; c < 3; c++) o[c] = clamp01 (in[c] + ly[c]); break;
    case B_SUBTRACT:      for (c = 0; c < 3; c++) o[c] = clamp01 (in[c] - ly[c]); break;
    case B_DARKEN:        for (c = 0; c < 3; c++) o[c] = minf (in[c], ly[c]); break;
    case B_LIGHTEN:       for (c = 0; c < 3; c++) o[c] = maxf (ly[c], in[c]); break;
    /* GIMP's clamps send 0/0 (NaN) to 0 for divide and dodge, and to 1 for burn. */
    case B_DIVIDE:
      for (c = 0; c < 3; c++) { float v = in[c] / ly[c]; o[c] = isnan (v) ? 0.0f : v > 0.0f ? (v < 1.0f ? v : 1.0f) : 0.0f; }
      break;
    case B_DODGE:
      for (c = 0; c < 3; c++) { float v = in[c] / (1.0f - ly[c]); o[c] = isnan (v) ? 0.0f : v > 0.0f ? (v < 1.0f ? v : 1.0f) : 0.0f; }
      break;
    case B_BURN:
      for (c = 0; c < 3; c++) { float v = 1.0f - (1.0f - in[c]) / ly[c]; o[c] = isnan (v) ? 1.0f : v < 0.0f ? 0.0f : v < 1.0f ? v : 1.0f; }
      break;
    case B_HARDLIGHT:
      for (c = 0; c < 3; c++)
        {
          float v;
          if (ly[c] > 128.0f / 255.0f)
            { v = (1.0f - in[c]) * (1.0f - (ly[c] - 128.0f / 255.0f) * 2.0f); v = minf (1.0f - v, 1.0f); }
          else
            { v = in[c] * (ly[c] * 2.0f); v = minf (v, 1.0f); }
          o[c] = v;
        }
      break;
    case B_GRAIN_EXTRACT: for (c = 0; c < 3; c++) o[c] = clamp01 (in[c] - ly[c] + 128.0f / 255.0f); break;
    case B_GRAIN_MERGE:   for (c = 0; c < 3; c++) o[c] = clamp01 (in[c] + ly[c] - 128.0f / 255.0f); break;
    case B_HSV_HUE: case B_HSV_SATURATION: case B_HSV_VALUE: case B_HSL_COLOR:
      {
        double l[3] = { ly[0], ly[1], ly[2] }, d[3] = { in[0], in[1], in[2] }, lh[3], dh[3], r[3];
        if (b == B_HSL_COLOR)
          {
            rgb_to_hsl (l, lh); rgb_to_hsl (d, dh);
            dh[0] = lh[0]; dh[1] = lh[1];
            hsl_to_rgb (dh, r);
          }
        else
          {
            rgb_to_hsv (l, lh); rgb_to_hsv (d, dh);
            if (b == B_HSV_HUE) { if (lh[1] != 0.0) dh[0] = lh[0]; }
            else if (b == B_HSV_SATURATION) dh[1] = lh[1];
            else dh[2] = lh[2];
            hsv_to_rgb (dh, r);
          }
        for (c = 0; c < 3; c++) o[c] = (float) r[c];
      }
      break;
    default:              for (c = 0; c < 3; c++) o[c] = ly[c]; break;
    }
}

/* ---- compositing --------------------------------------------------------------------------- */

typedef struct
{
  float *px;    /* PX straight RGBA */
  int    space; /* SP_LIN or SP_PER */
} Acc;

typedef struct
{
  const GakoLayer *layers;
  int              n;
  const char      *contributes;
  int              out_x, out_y, out_w, out_h, out_stride;
  uint8_t         *out;
  int              tiles_x, tiles_y, levels;
  int              next;
  int              background;   /* ARGB, used if has_background */
  int              has_background, background_space;
} Job;

typedef struct
{
  float *acc;    /* levels × PX × 4 */
  float *ly;     /* the layer, PX × 4, in the composite space */
  float *mask;   /* PX */
} Scratch;

typedef struct { int x, y, w, h; } Rect;

static int
intersect (Rect a, Rect b, Rect *o)
{
  int x0 = a.x > b.x ? a.x : b.x, y0 = a.y > b.y ? a.y : b.y;
  int x1 = a.x + a.w < b.x + b.w ? a.x + a.w : b.x + b.w;
  int y1 = a.y + a.h < b.y + b.h ? a.y + a.h : b.y + b.h;
  if (x1 <= x0 || y1 <= y0) return 0;
  o->x = x0; o->y = y0; o->w = x1 - x0; o->h = y1 - y0;
  return 1;
}

static void
acc_to_space (Acc *a, int space)
{
  if (a->space == space) return;
  for (int i = 0; i < PX; i++)
    {
      float *p = a->px + i * 4;
      if (p[3] != 0.0f)
        convert (p, a->space, space, p);
    }
  a->space = space;
}

/* Fills s->ly (and s->mask) over [r] (tile coordinates) with a pixel layer, in [space]. */
static void
load_layer (const GakoLayer *l, Scratch *s, Rect tile, Rect r, int space)
{
  for (int y = r.y; y < r.y + r.h; y++)
    {
      const uint8_t *src = l->pixels + (size_t) (y - l->y) * l->stride + (size_t) (r.x - l->x) * 4;
      const uint8_t *msrc = l->mask ? l->mask + (size_t) (y - l->y) * l->mask_stride + (size_t) (r.x - l->x) * 4 : NULL;
      float *d = s->ly + ((y - tile.y) * TILE + (r.x - tile.x)) * 4;
      float *m = s->mask + (y - tile.y) * TILE + (r.x - tile.x);
      for (int x = 0; x < r.w; x++, src += 4, d += 4, m++)
        {
          int a = src[3];
          d[3] = a / 255.0f;
          if (a == 0)
            d[0] = d[1] = d[2] = 0.0f;
          else
            {
              for (int c = 0; c < 3; c++)
                {
                  /* What the layer held before Android premultiplied it. */
                  int v = a == 255 ? src[c] : (src[c] * 255 + a / 2) / a;
                  if (v > 255) v = 255;
                  d[c] = space == SP_LIN ? u8_lin[v] : v / 255.0f;
                }
            }
          *m = msrc ? msrc[0] / 255.0f : 1.0f;
          if (msrc) msrc += 4;
        }
    }
}

/* Composites s->ly (in m.composite_space, over [r]) onto [a] with mode [m]. */
static void
composite (Acc *a, Scratch *s, Rect tile, Rect r, const ModeInfo *m, float opacity, int bottom)
{
  const int cs = m->composite_space;
  acc_to_space (a, cs);
  for (int y = r.y; y < r.y + r.h; y++)
    for (int x = r.x; x < r.x + r.w; x++)
      {
        const int i = (y - tile.y) * TILE + (x - tile.x);
        float *in = a->px + i * 4;
        const float *ly = s->ly + i * 4;
        const float mask = s->mask[i];
        float comp[3], out[4];

        if (bottom)
          {
            /* Nothing below: GIMP draws the layer as is (its "last node"). */
            in[0] = ly[0]; in[1] = ly[1]; in[2] = ly[2]; in[3] = ly[3] * opacity * mask;
            continue;
          }

        if (m->kind == K_LEGACY)
          {
            float ca = minf (in[3], ly[3]) * opacity * mask;
            float na = in[3] + (1.0f - in[3]) * ca;
            if (ca != 0.0f && na != 0.0f)
              {
                float ratio = ca / na;
                blend_legacy (m->blend, in, ly, comp);
                for (int c = 0; c < 3; c++) in[c] = comp[c] * ratio + in[c] * (1.0f - ratio);
              }
            continue;
          }

        if (m->kind == K_REPLACE)
          {
            float o = opacity * mask;
            float na = (ly[3] - in[3]) * o + in[3];
            float ratio = o;
            if (na != 0.0f) ratio *= ly[3] / na;
            for (int c = 0; c < 3; c++) in[c] = (ly[c] - in[c]) * ratio + in[c];
            in[3] = na;
            continue;
          }

        /* GIMP 2.10+: blend in the blend space, composite in the composite space. */
        if (in[3] != 0.0f && ly[3] != 0.0f)
          {
            if (m->blend == B_NORMAL)
              { comp[0] = ly[0]; comp[1] = ly[1]; comp[2] = ly[2]; }
            else if (m->blend_space == cs)
              blend_new (m->blend, in, ly, comp);
            else
              {
                float bi[3], bl[3], bc[3];
                convert (in, cs, m->blend_space, bi);
                convert (ly, cs, m->blend_space, bl);
                blend_new (m->blend, bi, bl, bc);
                convert (bc, m->blend_space, cs, comp);
              }
          }
        else
          comp[0] = comp[1] = comp[2] = 0.0f;

        switch (m->composite_mode)
          {
          case GAKO_COMPOSITE_UNION:
            {
              float la = ly[3] * opacity * mask;
              float na = la + (1.0f - la) * in[3];
              if (la == 0.0f || na == 0.0f)
                { out[0] = in[0]; out[1] = in[1]; out[2] = in[2]; }
              else if (in[3] == 0.0f)
                { out[0] = ly[0]; out[1] = ly[1]; out[2] = ly[2]; }
              else
                {
                  float ratio = la / na;
                  for (int c = 0; c < 3; c++)
                    out[c] = ratio * (in[3] * (comp[c] - ly[c]) + ly[c] - in[c]) + in[c];
                }
              out[3] = na;
            }
            break;
          case GAKO_COMPOSITE_CLIP_TO_LAYER:
            {
              float la = ly[3] * opacity * mask;
              if (la == 0.0f)
                { out[0] = in[0]; out[1] = in[1]; out[2] = in[2]; }
              else if (in[3] == 0.0f)
                { out[0] = ly[0]; out[1] = ly[1]; out[2] = ly[2]; }
              else
                for (int c = 0; c < 3; c++) out[c] = comp[c] * in[3] + ly[c] * (1.0f - in[3]);
              out[3] = la;
            }
            break;
          case GAKO_COMPOSITE_INTERSECTION:
            {
              float na = in[3] * ly[3] * opacity * mask;
              if (na == 0.0f)
                { out[0] = in[0]; out[1] = in[1]; out[2] = in[2]; }
              else
                { out[0] = comp[0]; out[1] = comp[1]; out[2] = comp[2]; }
              out[3] = na;
            }
            break;
          default: /* clip to backdrop */
            {
              float la = ly[3] * opacity * mask;
              if (in[3] == 0.0f || la == 0.0f)
                { out[0] = in[0]; out[1] = in[1]; out[2] = in[2]; }
              else
                for (int c = 0; c < 3; c++) out[c] = comp[c] * la + in[c] * (1.0f - la);
              out[3] = in[3];
            }
            break;
          }
        in[0] = out[0]; in[1] = out[1]; in[2] = out[2]; in[3] = out[3];
      }
}

/* Whether [m] changes pixels outside the layer (clip to layer and intersection clear them). */
static int
clears_outside (const ModeInfo *m)
{
  return m->kind == K_GENERIC &&
         (m->composite_mode == GAKO_COMPOSITE_CLIP_TO_LAYER || m->composite_mode == GAKO_COMPOSITE_INTERSECTION);
}

static void
clear_outside (Acc *a, Rect tile, const Rect *keep)
{
  for (int y = 0; y < TILE; y++)
    for (int x = 0; x < TILE; x++)
      {
        int cx = tile.x + x, cy = tile.y + y;
        if (keep && cx >= keep->x && cx < keep->x + keep->w && cy >= keep->y && cy < keep->y + keep->h)
          continue;
        a->px[(y * TILE + x) * 4 + 3] = 0.0f;
      }
}

static void stack (const Job *job, Scratch *s, int parent, int level, Acc *a, Rect tile, int *empty);

/* Composites one item (a layer or a whole group) of a stack onto [a]. */
static void
item (const Job *job, Scratch *s, int i, int level, Acc *a, Rect tile, int *empty)
{
  const GakoLayer *l = &job->layers[i];
  ModeInfo m = resolve (l);
  Rect r;

  if (l->group)
    {
      if (l->mode == GAKO_MODE_PASS_THROUGH && l->opacity >= 1.0f)
        {
          stack (job, s, i, level, a, tile, empty);
          return;
        }
      /* The group's own pixels: its contents on transparency, or for a pass-through group on
       * a copy of what's below (then mixed back by its opacity). */
      if (level + 1 >= job->levels)
        return;                                   /* can't happen: the depth was checked */
      Acc g = { s->acc + (size_t) (level + 1) * PX * 4, SP_LIN };
      int gempty = 1;
      if (l->mode == GAKO_MODE_PASS_THROUGH)
        {
          memcpy (g.px, a->px, sizeof (float) * PX * 4);
          g.space = a->space;
          gempty = *empty;
        }
      else
        memset (g.px, 0, sizeof (float) * PX * 4);
      stack (job, s, i, level + 1, &g, tile, &gempty);
      acc_to_space (&g, m.composite_space);
      memcpy (s->ly, g.px, sizeof (float) * PX * 4);
      for (int k = 0; k < PX; k++) s->mask[k] = 1.0f;
      composite (a, s, tile, (Rect) { tile.x, tile.y, TILE, TILE }, &m, l->opacity, *empty && l->mode != GAKO_MODE_PASS_THROUGH);
      *empty = 0;
      return;
    }

  Rect lr = { l->x, l->y, l->width, l->height };
  int overlaps = intersect (lr, tile, &r);
  int bottom = *empty;
  *empty = 0;
  if (!bottom && clears_outside (&m))
    clear_outside (a, tile, overlaps ? &r : NULL);
  if (!overlaps)
    return;
  load_layer (l, s, tile, r, m.composite_space);
  if (bottom)
    a->space = m.composite_space;   /* everything here is still transparent */
  composite (a, s, tile, r, &m, l->opacity, bottom);
}

static void
stack (const Job *job, Scratch *s, int parent, int level, Acc *a, Rect tile, int *empty)
{
  for (int i = 0; i < job->n; i++)
    if (job->layers[i].parent == parent && job->contributes[i])
      item (job, s, i, level, a, tile, empty);
}

static void
write_tile (const Job *job, Acc *a, Rect tile)
{
  Rect out = { job->out_x, job->out_y, job->out_w, job->out_h }, r;
  if (!intersect (out, tile, &r)) return;
  for (int y = r.y; y < r.y + r.h; y++)
    {
      uint8_t *d = job->out + (size_t) (y - job->out_y) * job->out_stride + (size_t) (r.x - job->out_x) * 4;
      float *p = a->px + ((y - tile.y) * TILE + (r.x - tile.x)) * 4;
      for (int x = 0; x < r.w; x++, d += 4, p += 4)
        {
          if (job->has_background)
            {
              /* Flattening: everything over an opaque colour, in the bottom layer's composite space. */
              float bg[3], al0 = clamp01 (p[3]);
              for (int c = 0; c < 3; c++)
                {
                  float v = ((job->background >> (16 - 8 * c)) & 255) / 255.0f;
                  bg[c] = job->background_space == SP_LIN ? to_lin (v) : v;
                }
              if (a->space != job->background_space)
                convert (p, a->space, job->background_space, p);
              for (int c = 0; c < 3; c++)
                {
                  float v = al0 > 0.0f ? p[c] * al0 + bg[c] * (1.0f - al0) : bg[c];
                  p[c] = job->background_space == SP_LIN ? to_per (v) : v;
                }
              p[3] = 1.0f;
              for (int c = 0; c < 3; c++) d[c] = (uint8_t) (clamp01 (p[c]) * 255.0f + 0.5f);
              d[3] = 255;
              continue;
            }
          float al = clamp01 (p[3]);
          int a8 = (int) (al * 255.0f + 0.5f);
          if (a8 == 0) { d[0] = d[1] = d[2] = d[3] = 0; continue; }
          for (int c = 0; c < 3; c++)
            {
              float v = clamp01 (a->space == SP_LIN ? to_per (p[c]) : p[c]);
              int pv = (int) (v * al * 255.0f + 0.5f);
              d[c] = (uint8_t) (pv > a8 ? a8 : pv);
            }
          d[3] = (uint8_t) a8;
        }
    }
}

static void *
worker (void *data)
{
  Job *job = data;
  Scratch s;
  s.acc  = malloc (sizeof (float) * PX * 4 * (size_t) job->levels);
  s.ly   = malloc (sizeof (float) * PX * 4);
  s.mask = malloc (sizeof (float) * PX);
  if (!s.acc || !s.ly || !s.mask)
    {
      free (s.acc); free (s.ly); free (s.mask);
      return (void *) 1;
    }
  for (;;)
    {
      int t = __atomic_fetch_add (&job->next, 1, __ATOMIC_RELAXED);
      if (t >= job->tiles_x * job->tiles_y) break;
      Rect tile = { job->out_x + (t % job->tiles_x) * TILE, job->out_y + (t / job->tiles_x) * TILE, TILE, TILE };
      Acc a = { s.acc, SP_PER };
      int empty = 1;
      memset (a.px, 0, sizeof (float) * PX * 4);
      stack (job, &s, -1, 0, &a, tile, &empty);
      write_tile (job, &a, tile);
    }
  free (s.acc); free (s.ly); free (s.mask);
  return NULL;
}

/* Whether a layer adds anything to the stack (GIMP: its node has a non-empty extent). */
static int
mark (const GakoLayer *layers, int n, char *contributes, int i)
{
  const GakoLayer *l = &layers[i];
  if (!l->visible || l->opacity <= 0.0f) return 0;
  if (!l->group) return l->width > 0 && l->height > 0;
  int any = 0;
  for (int k = 0; k < n; k++)
    if (layers[k].parent == i && contributes[k]) any = 1;
  return any;
}

/* How deep [i] is in groups; more than any real file has (or a loop) reads as too deep. */
static int
depth_of (const GakoLayer *layers, int n, int i)
{
  int d = 0;
  for (int p = layers[i].parent; p >= 0 && p < n; p = layers[p].parent)
    if (++d > MAX_DEPTH) return MAX_DEPTH + 1;
  return d;
}

int
gako_composite (const GakoLayer *layers, int n,
                int out_x, int out_y, int out_w, int out_h,
                uint8_t *out, int out_stride, int threads,
                int has_background, uint32_t background)
{
  pthread_t tids[MAX_THREADS];
  int started = 0, failed = 0, levels = 1;
  char *contributes;
  Job job;

  pthread_once (&lut_once, init_luts);
  if (out_w <= 0 || out_h <= 0) return 0;
  contributes = calloc ((size_t) (n > 0 ? n : 1), 1);
  if (!contributes) return -1;
  /* Groups come after their contents, so one pass bottom to top decides every item. */
  for (int i = 0; i < n; i++)
    {
      int depth = depth_of (layers, n, i);
      if (depth > MAX_DEPTH)
        {
          free (contributes);
          return -1;
        }
      contributes[i] = (char) mark (layers, n, contributes, i);
      if (depth + 2 > levels) levels = depth + 2;
    }

  job.layers = layers; job.n = n; job.contributes = contributes;
  job.out_x = out_x; job.out_y = out_y; job.out_w = out_w; job.out_h = out_h;
  job.out = out; job.out_stride = out_stride;
  job.tiles_x = (out_w + TILE - 1) / TILE; job.tiles_y = (out_h + TILE - 1) / TILE;
  job.levels = levels; job.next = 0;
  job.has_background = has_background; job.background = (int) background; job.background_space = SP_PER;
  for (int i = 0; i < n; i++)
    if (contributes[i] && layers[i].parent < 0)
      {
        /* GIMP flattens onto the background colour in the bottom layer's composite space. */
        ModeInfo m = resolve (&layers[i]);
        job.background_space = layers[i].group && layers[i].mode == GAKO_MODE_PASS_THROUGH ? SP_LIN : m.composite_space;
        break;
      }

  if (threads < 1) threads = 1;
  if (threads > MAX_THREADS) threads = MAX_THREADS;
  if (threads > job.tiles_x * job.tiles_y) threads = job.tiles_x * job.tiles_y;
  for (int t = 1; t < threads; t++)
    if (pthread_create (&tids[started], NULL, worker, &job) == 0) started++;
  if (worker (&job)) failed = 1;
  for (int t = 0; t < started; t++)
    {
      void *rc = NULL;
      pthread_join (tids[t], &rc);
      if (rc) failed = 1;
    }
  free (contributes);
  return failed ? -1 : 0;
}

