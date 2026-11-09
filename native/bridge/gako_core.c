/* GAKO — GIMP Android Kanvas Operator
 * Engine bridge over babl/GEGL.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "gako_core.h"

#include <stdio.h>
#include <string.h>
#include <gegl.h>
#include <gegl-paramspecs.h>
#include <json-glib/json-glib.h>

#define GAKO_PIXEL_FORMAT "R'aG'aB'aA u8"   /* Android ARGB_8888, premultiplied */

static gsize initialised = 0;

void
gako_free (void *p)
{
  g_free (p);
}

char *
gako_core_init (const char *ops_dir,
                const char *babl_dir,
                const char *swap_dir,
                int         threads,
                int         cache_mb)
{
  static char *init_error = NULL;

  if (g_once_init_enter (&initialised))
    {
      if (ops_dir)  g_setenv ("GEGL_PATH", ops_dir, TRUE);
      if (babl_dir) g_setenv ("BABL_PATH", babl_dir, TRUE);
      if (swap_dir) g_setenv ("GEGL_SWAP", swap_dir, TRUE);
      if (threads > 0)
        {
          char buf[16];
          snprintf (buf, sizeof buf, "%d", threads);
          g_setenv ("GEGL_THREADS", buf, TRUE);
        }
      if (cache_mb > 0)
        {
          char buf[16];
          snprintf (buf, sizeof buf, "%d", cache_mb);
          g_setenv ("GEGL_CACHE_SIZE", buf, TRUE);
        }
      g_setenv ("GEGL_USE_OPENCL", "no", TRUE);
      /* GEGL's default chunk is 1 Mpx, and progress/cancel are only seen between chunks:
       * on a phone a heavy filter can spend a minute inside one. 128 kpx keeps both live. */
      g_setenv ("GEGL_CHUNK_SIZE", "131072", FALSE);

      /* GAKO is GPLv3+, which lets GEGL load its GPL3-only operations
       * (emboss, cartoon, color-to-alpha, lens flare, ...). It skips them otherwise. */
      g_object_set (gegl_config (), "application-license", "GPL3", NULL);

      gegl_init (NULL, NULL);

      if (!gegl_has_operation ("gegl:gaussian-blur"))
        init_error = g_strdup_printf ("GEGL started but found no operations in %s",
                                      ops_dir ? ops_dir : "(default path)");

      g_once_init_leave (&initialised, 1);
    }

  return init_error ? g_strdup (init_error) : NULL;
}

/* ------------------------------------------------------------------------ */
/* Operation listing                                                        */

static gboolean
category_is_hidden (const char *categories)
{
  static const char *hidden[] = { "hidden", "programming", "core", "input", "output",
                                  "debug", "video", "meta", NULL };
  gboolean result = FALSE;
  gchar **parts;

  if (!categories)
    return FALSE;

  parts = g_strsplit (categories, ":", -1);
  for (int i = 0; parts[i] && !result; i++)
    for (int j = 0; hidden[j]; j++)
      if (g_strcmp0 (parts[i], hidden[j]) == 0)
        {
          result = TRUE;
          break;
        }
  g_strfreev (parts);
  return result;
}

static const char *
operation_kind (GeglNode *node)
{
  gboolean in  = gegl_node_has_pad (node, "input");
  gboolean out = gegl_node_has_pad (node, "output");
  gboolean aux = gegl_node_has_pad (node, "aux");

  if (!out)
    return NULL;
  if (in && aux)
    return "composer";
  if (in)
    return "filter";
  return "source";
}

static void
add_string_array (JsonBuilder *b, const char *member, const char *colon_list)
{
  json_builder_set_member_name (b, member);
  json_builder_begin_array (b);
  if (colon_list)
    {
      gchar **parts = g_strsplit (colon_list, ":", -1);
      for (int i = 0; parts[i]; i++)
        if (*parts[i])
          json_builder_add_string_value (b, parts[i]);
      g_strfreev (parts);
    }
  json_builder_end_array (b);
}

static void
add_string (JsonBuilder *b, const char *member, const char *value)
{
  json_builder_set_member_name (b, member);
  if (value)
    json_builder_add_string_value (b, value);
  else
    json_builder_add_null_value (b);
}

static char *
builder_to_string (JsonBuilder *b)
{
  JsonNode *root = json_builder_get_root (b);
  char *s = json_to_string (root, FALSE);
  json_node_unref (root);
  g_object_unref (b);
  return s;
}

char *
gako_core_list_operations (void)
{
  guint n_ops = 0;
  gchar **ops = gegl_list_operations (&n_ops);
  GeglNode *graph = gegl_node_new ();
  JsonBuilder *b = json_builder_new ();

  json_builder_begin_array (b);
  for (guint i = 0; i < n_ops; i++)
    {
      const char *name = ops[i];
      const char *categories = gegl_operation_get_key (name, "categories");
      GeglNode *node;
      const char *kind;

      if (category_is_hidden (categories))
        continue;

      node = gegl_node_new_child (graph, "operation", name, NULL);
      kind = node ? operation_kind (node) : NULL;
      if (node)
        gegl_node_remove_child (graph, node);
      if (!kind)
        continue;

      json_builder_begin_object (b);
      add_string (b, "name", name);
      add_string (b, "title", gegl_operation_get_key (name, "title"));
      add_string (b, "description", gegl_operation_get_key (name, "description"));
      add_string_array (b, "categories", categories);
      add_string (b, "kind", kind);
      json_builder_end_object (b);
    }
  json_builder_end_array (b);

  g_object_unref (graph);
  g_free (ops);
  return builder_to_string (b);
}

/* ------------------------------------------------------------------------ */
/* Operation description                                                    */

static void
add_double (JsonBuilder *b, const char *member, double v)
{
  json_builder_set_member_name (b, member);
  json_builder_add_double_value (b, v);
}

static void
add_int (JsonBuilder *b, const char *member, gint64 v)
{
  json_builder_set_member_name (b, member);
  json_builder_add_int_value (b, v);
}

static char *
color_to_hex (GeglColor *color)
{
  /* Hex strings are parsed by GEGL as sRGB-encoded, so read them back the same way. */
  gdouble rgba[4] = { 0, 0, 0, 1 };
  if (color)
    gegl_color_get_pixel (color, babl_format ("R'G'B'A double"), rgba);
#define C8(x) ((int) CLAMP ((x) * 255.0 + 0.5, 0, 255))
  return g_strdup_printf ("#%02x%02x%02x%02x", C8 (rgba[0]), C8 (rgba[1]), C8 (rgba[2]), C8 (rgba[3]));
#undef C8
}

static void
describe_property (JsonBuilder *b, GParamSpec *pspec)
{
  const char *unit = gegl_param_spec_get_property_key (pspec, "unit");
  const char *role = gegl_param_spec_get_property_key (pspec, "role");
  const char *axis = gegl_param_spec_get_property_key (pspec, "axis");

  json_builder_begin_object (b);
  add_string (b, "name", pspec->name);
  add_string (b, "label", g_param_spec_get_nick (pspec));
  add_string (b, "description", g_param_spec_get_blurb (pspec));
  if (unit) add_string (b, "unit", unit);
  if (role) add_string (b, "role", role);
  if (axis) add_string (b, "axis", axis);

  if (GEGL_IS_PARAM_SPEC_SEED (pspec))
    {
      GeglParamSpecSeed *s = GEGL_PARAM_SPEC_SEED (pspec);
      add_string (b, "type", "seed");
      add_int (b, "default", G_PARAM_SPEC_UINT (pspec)->default_value);
      add_int (b, "min", s->ui_minimum);
      add_int (b, "max", s->ui_maximum);
    }
  else if (G_IS_PARAM_SPEC_DOUBLE (pspec))
    {
      GParamSpecDouble *d = G_PARAM_SPEC_DOUBLE (pspec);
      add_string (b, "type", "double");
      add_double (b, "default", d->default_value);
      add_double (b, "min", d->minimum);
      add_double (b, "max", d->maximum);
      if (GEGL_IS_PARAM_SPEC_DOUBLE (pspec))
        {
          GeglParamSpecDouble *g = GEGL_PARAM_SPEC_DOUBLE (pspec);
          add_double (b, "uiMin", g->ui_minimum);
          add_double (b, "uiMax", g->ui_maximum);
          add_double (b, "uiGamma", g->ui_gamma);
          add_double (b, "step", g->ui_step_small);
          add_int (b, "digits", g->ui_digits);
        }
    }
  else if (G_IS_PARAM_SPEC_INT (pspec))
    {
      GParamSpecInt *i = G_PARAM_SPEC_INT (pspec);
      add_string (b, "type", "int");
      add_int (b, "default", i->default_value);
      add_int (b, "min", i->minimum);
      add_int (b, "max", i->maximum);
      if (GEGL_IS_PARAM_SPEC_INT (pspec))
        {
          GeglParamSpecInt *g = GEGL_PARAM_SPEC_INT (pspec);
          add_int (b, "uiMin", g->ui_minimum);
          add_int (b, "uiMax", g->ui_maximum);
          add_double (b, "uiGamma", g->ui_gamma);
          add_int (b, "step", g->ui_step_small);
        }
    }
  else if (G_IS_PARAM_SPEC_UINT (pspec))
    {
      GParamSpecUInt *u = G_PARAM_SPEC_UINT (pspec);
      add_string (b, "type", "int");
      add_int (b, "default", u->default_value);
      add_int (b, "min", u->minimum);
      add_int (b, "max", MIN (u->maximum, G_MAXINT));
    }
  else if (G_IS_PARAM_SPEC_BOOLEAN (pspec))
    {
      add_string (b, "type", "boolean");
      json_builder_set_member_name (b, "default");
      json_builder_add_boolean_value (b, G_PARAM_SPEC_BOOLEAN (pspec)->default_value);
    }
  else if (G_IS_PARAM_SPEC_ENUM (pspec))
    {
      GParamSpecEnum *e = G_PARAM_SPEC_ENUM (pspec);
      GEnumValue *def = g_enum_get_value (e->enum_class, e->default_value);

      add_string (b, "type", "enum");
      add_string (b, "default", def ? def->value_nick : NULL);
      json_builder_set_member_name (b, "values");
      json_builder_begin_array (b);
      for (guint k = 0; k < e->enum_class->n_values; k++)
        {
          GEnumValue *v = &e->enum_class->values[k];
          /* GEGL marks enum values that shouldn't be offered with an empty name. */
          if (GEGL_IS_PARAM_SPEC_ENUM (pspec) &&
              g_slist_find (GEGL_PARAM_SPEC_ENUM (pspec)->excluded_values,
                            GINT_TO_POINTER (v->value)))
            continue;
          json_builder_begin_object (b);
          add_string (b, "nick", v->value_nick);
          add_string (b, "label", v->value_name);
          json_builder_end_object (b);
        }
      json_builder_end_array (b);
    }
  else if (GEGL_IS_PARAM_SPEC_COLOR (pspec))
    {
      GeglColor *def = gegl_param_spec_color_get_default (pspec);
      char *hex = color_to_hex (def);
      add_string (b, "type", "color");
      add_string (b, "default", hex);
      g_free (hex);
    }
  else if (GEGL_IS_PARAM_SPEC_FILE_PATH (pspec) || GEGL_IS_PARAM_SPEC_URI (pspec))
    {
      add_string (b, "type", "unsupported");
      add_string (b, "valueType", "path");
    }
  else if (G_IS_PARAM_SPEC_STRING (pspec))
    {
      add_string (b, "type", "string");
      add_string (b, "default", G_PARAM_SPEC_STRING (pspec)->default_value);
    }
  else
    {
      add_string (b, "type", "unsupported");
      add_string (b, "valueType", g_type_name (pspec->value_type));
    }

  json_builder_end_object (b);
}

char *
gako_core_describe (const char *operation)
{
  JsonBuilder *b;
  GParamSpec **props;
  guint n_props = 0;

  if (!operation || !gegl_has_operation (operation))
    return NULL;

  b = json_builder_new ();
  json_builder_begin_object (b);
  add_string (b, "name", operation);
  add_string (b, "title", gegl_operation_get_key (operation, "title"));
  add_string (b, "description", gegl_operation_get_key (operation, "description"));
  add_string_array (b, "categories", gegl_operation_get_key (operation, "categories"));

  json_builder_set_member_name (b, "properties");
  json_builder_begin_array (b);
  props = gegl_operation_list_properties (operation, &n_props);
  for (guint i = 0; i < n_props; i++)
    {
      GParamSpec *pspec = props[i];
      if (!(pspec->flags & G_PARAM_WRITABLE))
        continue;
      describe_property (b, pspec);
    }
  g_free (props);
  json_builder_end_array (b);
  json_builder_end_object (b);

  return builder_to_string (b);
}

/* ------------------------------------------------------------------------ */
/* Applying an operation                                                    */

static gboolean
set_property_from_json (GeglNode    *node,
                        const char  *operation,
                        const char  *name,
                        JsonNode    *value,
                        GError     **error)
{
  GParamSpec *pspec = gegl_operation_find_property (operation, name);
  GValue v = G_VALUE_INIT;
  gboolean ok = TRUE;

  if (!pspec)
    return TRUE;   /* ignore unknown keys rather than failing the whole run */

  if (JSON_NODE_HOLDS_NULL (value))
    return TRUE;

  g_value_init (&v, pspec->value_type);

  if (G_IS_PARAM_SPEC_DOUBLE (pspec))
    g_value_set_double (&v, json_node_get_double (value));
  else if (G_IS_PARAM_SPEC_INT (pspec))
    g_value_set_int (&v, (gint) json_node_get_int (value));
  else if (G_IS_PARAM_SPEC_UINT (pspec))
    g_value_set_uint (&v, (guint) json_node_get_int (value));
  else if (G_IS_PARAM_SPEC_BOOLEAN (pspec))
    g_value_set_boolean (&v, json_node_get_boolean (value));
  else if (G_IS_PARAM_SPEC_ENUM (pspec))
    {
      GEnumClass *klass = G_PARAM_SPEC_ENUM (pspec)->enum_class;
      if (json_node_get_value_type (value) == G_TYPE_STRING)
        {
          GEnumValue *ev = g_enum_get_value_by_nick (klass, json_node_get_string (value));
          if (ev)
            g_value_set_enum (&v, ev->value);
          else
            ok = FALSE;
        }
      else
        g_value_set_enum (&v, (gint) json_node_get_int (value));
    }
  else if (GEGL_IS_PARAM_SPEC_COLOR (pspec))
    {
      GeglColor *c = gegl_color_new (json_node_get_string (value));
      g_value_take_object (&v, c);
    }
  else if (G_IS_PARAM_SPEC_STRING (pspec))
    g_value_set_string (&v, json_node_get_string (value));
  else
    ok = FALSE;

  if (ok)
    gegl_node_set_property (node, name, &v);
  else
    g_set_error (error, G_IO_ERROR, G_IO_ERROR_INVALID_ARGUMENT,
                 "Can't set %s.%s from the given value", operation, name);

  g_value_unset (&v);
  return ok;
}

int
gako_core_apply (const char       *operation,
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
                 char            **error_out)
{
  const Babl *format = babl_format (GAKO_PIXEL_FORMAT);
  GeglRectangle rect = { 0, 0, width, height };
  GeglBuffer *in_buf = NULL, *out_buf = NULL;
  GeglNode *graph = NULL, *source = NULL, *op = NULL, *sink;
  GeglProcessor *processor;
  GError *error = NULL;
  double fraction = 0.0;
  int result = 0;

  if (!operation || !gegl_has_operation (operation))
    {
      if (error_out)
        *error_out = g_strdup_printf ("Unknown operation %s", operation ? operation : "(null)");
      return -1;
    }

  graph = gegl_node_new ();
  op = gegl_node_new_child (graph, "operation", operation, NULL);

  if (params_json && *params_json)
    {
      JsonParser *parser = json_parser_new ();
      if (!json_parser_load_from_data (parser, params_json, -1, &error))
        {
          g_object_unref (parser);
          goto fail;
        }
      JsonNode *root = json_parser_get_root (parser);
      if (root && JSON_NODE_HOLDS_OBJECT (root))
        {
          JsonObject *obj = json_node_get_object (root);
          GList *members = json_object_get_members (obj);
          for (GList *l = members; l; l = l->next)
            if (!set_property_from_json (op, operation, l->data,
                                         json_object_get_member (obj, l->data), &error))
              break;
          g_list_free (members);
        }
      g_object_unref (parser);
      if (error)
        goto fail;
    }

  if (gegl_node_has_pad (op, "input"))
    {
      in_buf = gegl_buffer_new (&rect, format);
      gegl_buffer_set (in_buf, &rect, 0, format, src, src_stride);
      source = gegl_node_new_child (graph, "operation", "gegl:buffer-source",
                                    "buffer", in_buf, NULL);
      gegl_node_link (source, op);
    }

  out_buf = gegl_buffer_new (&rect, format);
  sink = gegl_node_new_child (graph, "operation", "gegl:write-buffer",
                              "buffer", out_buf, NULL);
  gegl_node_link (op, sink);

  processor = gegl_node_new_processor (sink, &rect);
  while (gegl_processor_work (processor, &fraction))
    {
      if (cancel && *cancel)
        {
          result = 1;
          break;
        }
      if (progress)
        progress (fraction, progress_data);
    }
  g_object_unref (processor);

  if (result == 0)
    {
      gegl_buffer_get (out_buf, &rect, 1.0, format, dst, dst_stride, GEGL_ABYSS_NONE);
      if (progress)
        progress (1.0, progress_data);
    }

  g_clear_object (&in_buf);
  g_clear_object (&out_buf);
  g_object_unref (graph);
  return result;

fail:
  if (error_out)
    *error_out = g_strdup (error ? error->message : "unknown error");
  g_clear_error (&error);
  g_clear_object (&in_buf);
  g_clear_object (&out_buf);
  g_clear_object (&graph);
  return -1;
}
