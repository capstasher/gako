/* Host-side smoke test for the GAKO bridge: lists ops, describes a few,
 * and runs real filters over a generated image.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <glib.h>
#include <json-glib/json-glib.h>
#include "gako_core.h"

#define W 640
#define H 400

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf ("FAIL: " __VA_ARGS__); printf ("\n"); } \
                              else { printf ("ok:   " __VA_ARGS__); printf ("\n"); } } while (0)

static void
write_ppm (const char *path, const guint8 *px)
{
  FILE *f = fopen (path, "wb");
  fprintf (f, "P6\n%d %d\n255\n", W, H);
  for (int i = 0; i < W * H; i++)
    fwrite (px + i * 4, 1, 3, f);
  fclose (f);
}

int
main (int argc, char **argv)
{
  const char *ops_dir  = argc > 1 ? argv[1] : NULL;
  const char *babl_dir = argc > 2 ? argv[2] : NULL;
  guint8 *src = g_malloc (W * H * 4), *dst = g_malloc (W * H * 4);
  char *err, *json;
  int rc;

  err = gako_core_init (ops_dir, babl_dir, "/tmp", 4, 256);
  CHECK (err == NULL, "init (%s)", err ? err : "no error");
  if (err)
    return 1;

  /* --- list --- */
  json = gako_core_list_operations ();
  {
    JsonParser *p = json_parser_new ();
    json_parser_load_from_data (p, json, -1, NULL);
    JsonArray *arr = json_node_get_array (json_parser_get_root (p));
    guint n = json_array_get_length (arr), filters = 0;
    GHashTable *cats = g_hash_table_new (g_str_hash, g_str_equal);
    for (guint i = 0; i < n; i++)
      {
        JsonObject *o = json_array_get_object_element (arr, i);
        if (g_strcmp0 (json_object_get_string_member (o, "kind"), "filter") == 0)
          filters++;
        JsonArray *c = json_object_get_array_member (o, "categories");
        for (guint k = 0; k < json_array_get_length (c); k++)
          {
            const char *cat = json_array_get_string_element (c, k);
            g_hash_table_insert (cats, (gpointer) cat,
                                 GINT_TO_POINTER (GPOINTER_TO_INT (g_hash_table_lookup (cats, cat)) + 1));
          }
      }
    CHECK (filters > 100, "listed %u user-facing ops, %u of them filters", n, filters);
    printf ("      categories:");
    GHashTableIter it; gpointer k, v;
    g_hash_table_iter_init (&it, cats);
    while (g_hash_table_iter_next (&it, &k, &v))
      printf (" %s(%d)", (char *) k, GPOINTER_TO_INT (v));
    printf ("\n");
    g_hash_table_unref (cats);
    g_object_unref (p);
  }
  gako_free (json);

  /* --- describe --- */
  json = gako_core_describe ("gegl:gaussian-blur");
  CHECK (json && strstr (json, "\"std-dev-x\""), "describe gaussian-blur");
  printf ("      %.300s...\n", json ? json : "(null)");
  gako_free (json);

  json = gako_core_describe ("gegl:noise-hsv");
  CHECK (json && strstr (json, "\"seed\""), "describe noise-hsv has a seed");
  gako_free (json);

  json = gako_core_describe ("gegl:emboss");
  CHECK (json && strstr (json, "\"enum\""), "describe emboss has an enum");
  gako_free (json);

  /* --- apply --- */
  for (int y = 0; y < H; y++)
    for (int x = 0; x < W; x++)
      {
        guint8 *p = src + (y * W + x) * 4;
        gboolean square = (x / 40 + y / 40) % 2;
        p[0] = square ? 230 : 20;
        p[1] = (guint8) (x * 255 / W);
        p[2] = (guint8) (y * 255 / H);
        p[3] = 255;
      }
  write_ppm ("/tmp/gako-src.ppm", src);

  rc = gako_core_apply ("gegl:invert-gamma", NULL, src, W, H, W * 4, dst, W * 4, NULL, NULL, NULL, &err);
  CHECK (rc == 0 && dst[0] == 255 - src[0] && dst[1] == 255 - src[1], "invert-gamma (%d %d -> %d %d)",
         src[0], src[1], dst[0], dst[1]);

  rc = gako_core_apply ("gegl:gaussian-blur", "{\"std-dev-x\": 12.0, \"std-dev-y\": 12.0}",
                        src, W, H, W * 4, dst, W * 4, NULL, NULL, NULL, &err);
  {
    /* at a square edge, the blur should produce an intermediate red value */
    guint8 r = dst[(20 * W + 40) * 4];
    CHECK (rc == 0 && r > 40 && r < 210, "gaussian-blur softened an edge (r=%d)", r);
  }
  write_ppm ("/tmp/gako-blur.ppm", dst);

  rc = gako_core_apply ("gegl:emboss", "{\"type\": \"bumpmap\", \"azimuth\": 45.0}",
                        src, W, H, W * 4, dst, W * 4, NULL, NULL, NULL, &err);
  CHECK (rc == 0, "emboss with enum param (%s)", err ? err : "");
  write_ppm ("/tmp/gako-emboss.ppm", dst);

  rc = gako_core_apply ("gegl:color-to-alpha", "{\"color\": \"#ffffff\"}",
                        src, W, H, W * 4, dst, W * 4, NULL, NULL, NULL, &err);
  CHECK (rc == 0, "color-to-alpha with color param");

  rc = gako_core_apply ("gegl:does-not-exist", NULL, src, W, H, W * 4, dst, W * 4, NULL, NULL, NULL, &err);
  CHECK (rc == -1 && err, "unknown op is an error (%s)", err ? err : "");
  gako_free (err);
  err = NULL;

  volatile int cancel = 1;
  rc = gako_core_apply ("gegl:gaussian-blur", "{\"std-dev-x\": 50.0}", src, W, H, W * 4, dst, W * 4,
                        &cancel, NULL, NULL, &err);
  CHECK (rc == 1, "cancel flag honoured (rc=%d)", rc);

  printf ("\n%s (%d failures)\n", failures ? "FAILED" : "PASSED", failures);
  g_free (src);
  g_free (dst);
  return failures ? 1 : 0;
}
