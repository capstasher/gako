/* GAKO — GIMP Android Kanvas Operator
 * JNI entry points for io.github.capstasher.gako.engine.Engine
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include <glib.h>
#include "gako_core.h"
#include "gako_modes.h"
#include <stdlib.h>

#define TAG "GAKO"
#define JNI_FN(name) Java_io_github_capstasher_gako_engine_Engine_##name

static volatile int cancel_flag = 0;
static char *last_error = NULL;

/* ---- logging: GLib and stderr both go to logcat ------------------------- */

static GLogWriterOutput
logcat_writer (GLogLevelFlags   level,
               const GLogField *fields,
               gsize            n_fields,
               gpointer         user_data)
{
  const char *domain = NULL, *message = NULL;
  int prio = ANDROID_LOG_INFO;

  for (gsize i = 0; i < n_fields; i++)
    {
      if (g_strcmp0 (fields[i].key, "GLIB_DOMAIN") == 0)
        domain = fields[i].value;
      else if (g_strcmp0 (fields[i].key, "MESSAGE") == 0)
        message = fields[i].value;
    }

  if (level & (G_LOG_LEVEL_ERROR | G_LOG_FLAG_FATAL)) prio = ANDROID_LOG_FATAL;
  else if (level & G_LOG_LEVEL_CRITICAL)              prio = ANDROID_LOG_ERROR;
  else if (level & G_LOG_LEVEL_WARNING)               prio = ANDROID_LOG_WARN;
  else if (level & G_LOG_LEVEL_DEBUG)                 prio = ANDROID_LOG_DEBUG;

  __android_log_print (prio, domain ? domain : TAG, "%s", message ? message : "");
  return G_LOG_WRITER_HANDLED;
}

static int stderr_pipe[2];

static void *
stderr_pump (void *unused)
{
  char buf[1024];
  ssize_t n;
  (void) unused;
  while ((n = read (stderr_pipe[0], buf, sizeof buf - 1)) > 0)
    {
      if (buf[n - 1] == '\n')
        n--;
      buf[n] = 0;
      __android_log_write (ANDROID_LOG_WARN, TAG "-stderr", buf);
    }
  return NULL;
}

static void
redirect_stderr (void)
{
  pthread_t thread;
  setvbuf (stderr, NULL, _IONBF, 0);
  if (pipe (stderr_pipe) == 0)
    {
      dup2 (stderr_pipe[1], STDERR_FILENO);
      if (pthread_create (&thread, NULL, stderr_pump, NULL) == 0)
        pthread_detach (thread);
    }
}

/* ---- helpers ------------------------------------------------------------ */

static char *
dup_jstring (JNIEnv *env, jstring s)
{
  const char *utf;
  char *copy;
  if (!s)
    return NULL;
  utf = (*env)->GetStringUTFChars (env, s, NULL);
  copy = g_strdup (utf);
  (*env)->ReleaseStringUTFChars (env, s, utf);
  return copy;
}

static jstring
take_jstring (JNIEnv *env, char *s)
{
  jstring result;
  if (!s)
    return NULL;
  result = (*env)->NewStringUTF (env, s);
  gako_free (s);
  return result;
}

static void
set_last_error (const char *message)
{
  g_free (last_error);
  last_error = g_strdup (message);
}

/* ---- entry points ------------------------------------------------------- */

JNIEXPORT jstring JNICALL
JNI_FN (nativeInit) (JNIEnv *env, jclass klass,
                     jstring ops_dir, jstring babl_dir, jstring swap_dir,
                     jint threads, jint cache_mb)
{
  static gsize logging_ready = 0;
  char *ops, *babl, *swap, *err;
  (void) klass;

  if (g_once_init_enter (&logging_ready))
    {
      redirect_stderr ();
      g_log_set_writer_func (logcat_writer, NULL, NULL);
      g_once_init_leave (&logging_ready, 1);
    }

  ops  = dup_jstring (env, ops_dir);
  babl = dup_jstring (env, babl_dir);
  swap = dup_jstring (env, swap_dir);
  err  = gako_core_init (ops, babl, swap, threads, cache_mb);
  g_free (ops);
  g_free (babl);
  g_free (swap);
  return take_jstring (env, err);
}

JNIEXPORT jstring JNICALL
JNI_FN (nativeListOperations) (JNIEnv *env, jclass klass)
{
  (void) klass;
  return take_jstring (env, gako_core_list_operations ());
}

JNIEXPORT jstring JNICALL
JNI_FN (nativeDescribe) (JNIEnv *env, jclass klass, jstring operation)
{
  char *op = dup_jstring (env, operation);
  jstring result = take_jstring (env, gako_core_describe (op));
  (void) klass;
  g_free (op);
  return result;
}

JNIEXPORT void JNICALL
JNI_FN (nativeCancel) (JNIEnv *env, jclass klass)
{
  (void) env; (void) klass;
  cancel_flag = 1;
}

JNIEXPORT jstring JNICALL
JNI_FN (nativeLastError) (JNIEnv *env, jclass klass)
{
  (void) klass;
  return last_error ? (*env)->NewStringUTF (env, last_error) : NULL;
}

typedef struct
{
  JNIEnv   *env;
  jobject   listener;
  jmethodID method;
  int       last_percent;
} ProgressCtx;

static void
report_progress (double fraction, void *data)
{
  ProgressCtx *ctx = data;
  int percent = (int) (fraction * 100.0);
  if (!ctx->listener || percent == ctx->last_percent)
    return;
  ctx->last_percent = percent;
  (*ctx->env)->CallVoidMethod (ctx->env, ctx->listener, ctx->method, (jfloat) fraction);
  if ((*ctx->env)->ExceptionCheck (ctx->env))
    {
      (*ctx->env)->ExceptionClear (ctx->env);
      ctx->listener = NULL;
    }
}

/* [x, y, w, h] of what the operation draws for a w×h input, or null on error. */
JNIEXPORT jintArray JNICALL
JNI_FN (nativeBounds) (JNIEnv *env, jclass klass,
                       jstring operation, jstring params, jint width, jint height)
{
  char *op = dup_jstring (env, operation);
  char *json = dup_jstring (env, params);
  char *err = NULL;
  int b[4] = { 0, 0, 0, 0 };
  jintArray result = NULL;
  (void) klass;

  if (gako_core_bounds (op, json, width, height, &b[0], &b[1], &b[2], &b[3], &err) == 0)
    {
      result = (*env)->NewIntArray (env, 4);
      if (result)
        (*env)->SetIntArrayRegion (env, result, 0, 4, (const jint *) b);
    }
  else
    set_last_error (err ? err : "unknown error");
  gako_free (err);
  g_free (op);
  g_free (json);
  return result;
}

/* Renders the operation over src and writes the output rectangle starting at (out_x, out_y),
 * dst-sized, into dst. Returns 0 = ok, 1 = cancelled, -1 = error (see nativeLastError). */
JNIEXPORT jint JNICALL
JNI_FN (nativeApply) (JNIEnv *env, jclass klass,
                      jstring operation, jstring params,
                      jobject src_bitmap, jobject dst_bitmap,
                      jint out_x, jint out_y,
                      jobject listener)
{
  AndroidBitmapInfo src_info, dst_info;
  void *src_px = NULL, *dst_px = NULL;
  ProgressCtx ctx = { env, listener, NULL, -1 };
  char *op = NULL, *json = NULL, *err = NULL;
  int rc = -1;
  (void) klass;

  cancel_flag = 0;
  set_last_error (NULL);

  if (AndroidBitmap_getInfo (env, src_bitmap, &src_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_getInfo (env, dst_bitmap, &dst_info) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
      set_last_error ("Couldn't read bitmap info");
      return -1;
    }
  if (src_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
      dst_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
    {
      set_last_error ("Bitmaps must both be ARGB_8888");
      return -1;
    }

  if (listener)
    {
      jclass lk = (*env)->GetObjectClass (env, listener);
      ctx.method = (*env)->GetMethodID (env, lk, "onProgress", "(F)V");
      if (!ctx.method)
        {
          (*env)->ExceptionClear (env);
          ctx.listener = NULL;
        }
    }

  if (AndroidBitmap_lockPixels (env, src_bitmap, &src_px) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
      set_last_error ("Couldn't lock source pixels");
      return -1;
    }
  if (AndroidBitmap_lockPixels (env, dst_bitmap, &dst_px) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
      AndroidBitmap_unlockPixels (env, src_bitmap);
      set_last_error ("Couldn't lock destination pixels");
      return -1;
    }

  op   = dup_jstring (env, operation);
  json = dup_jstring (env, params);
  rc = gako_core_apply_rect (op, json,
                             src_px, (int) src_info.width, (int) src_info.height, (int) src_info.stride,
                             out_x, out_y, (int) dst_info.width, (int) dst_info.height,
                             dst_px, (int) dst_info.stride,
                             &cancel_flag, report_progress, &ctx, &err);

  AndroidBitmap_unlockPixels (env, dst_bitmap);
  AndroidBitmap_unlockPixels (env, src_bitmap);

  if (rc < 0)
    set_last_error (err ? err : "unknown error");
  gako_free (err);
  g_free (op);
  g_free (json);
  return rc;
}

/* Foreground matting: alpha (w×h bytes) from the image and a trimap (0 bg, 255 fg, else unknown). */
JNIEXPORT jint JNICALL
JNI_FN (nativeMatting) (JNIEnv *env, jclass klass,
                        jobject src_bitmap, jbyteArray trimap, jint iterations, jbyteArray alpha_out)
{
  AndroidBitmapInfo info;
  void *px = NULL;
  jbyte *tri = NULL, *alpha = NULL;
  char *err = NULL;
  int rc;
  (void) klass;

  cancel_flag = 0;
  set_last_error (NULL);
  if (AndroidBitmap_getInfo (env, src_bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
    {
      set_last_error ("The image must be ARGB_8888");
      return -1;
    }
  if ((*env)->GetArrayLength (env, trimap) < (jsize) (info.width * info.height) ||
      (*env)->GetArrayLength (env, alpha_out) < (jsize) (info.width * info.height))
    {
      set_last_error ("The trimap or the output is too small");
      return -1;
    }
  if (AndroidBitmap_lockPixels (env, src_bitmap, &px) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
      set_last_error ("Couldn't lock the image's pixels");
      return -1;
    }
  tri = (*env)->GetByteArrayElements (env, trimap, NULL);
  alpha = (*env)->GetByteArrayElements (env, alpha_out, NULL);
  if (!tri || !alpha)
    {
      if (tri) (*env)->ReleaseByteArrayElements (env, trimap, tri, JNI_ABORT);
      if (alpha) (*env)->ReleaseByteArrayElements (env, alpha_out, alpha, JNI_ABORT);
      AndroidBitmap_unlockPixels (env, src_bitmap);
      set_last_error ("Out of memory");
      return -1;
    }

  rc = gako_core_matting (px, (int) info.width, (int) info.height, (int) info.stride,
                          (const uint8_t *) tri, (int) info.width, iterations,
                          (uint8_t *) alpha, (int) info.width,
                          &cancel_flag, NULL, NULL, &err);

  (*env)->ReleaseByteArrayElements (env, trimap, tri, JNI_ABORT);
  (*env)->ReleaseByteArrayElements (env, alpha_out, alpha, rc == 0 ? 0 : JNI_ABORT);
  AndroidBitmap_unlockPixels (env, src_bitmap);
  if (rc < 0)
    set_last_error (err ? err : "unknown error");
  gako_free (err);
  return rc;
}

/* ---- GIMP layer modes ----------------------------------------------------------------------- */

/*
 * Composites a layer stack the way GIMP does. [pixels]/[masks]: one bitmap (or null) per layer;
 * [ints]: per layer x, y, parent index, flags (1 visible, 2 group), mode, blend space,
 * composite space, composite mode; [opacity]: per layer. [out] shows the canvas from out_x, out_y.
 */
#define COMPOSITE_INTS 8
JNIEXPORT jint JNICALL
JNI_FN (nativeComposite) (JNIEnv *env, jclass klass,
                          jobjectArray pixels, jobjectArray masks, jintArray ints, jfloatArray opacity,
                          jobject out_bitmap, jint out_x, jint out_y, jint threads,
                          jboolean has_background, jint background)
{
  AndroidBitmapInfo info;
  jsize n = (*env)->GetArrayLength (env, pixels);
  GakoLayer *layers = calloc ((size_t) (n > 0 ? n : 1), sizeof (GakoLayer));
  jobject *locked = calloc ((size_t) (2 * n + 1), sizeof (jobject));
  int n_locked = 0, rc = -1;
  void *out_px = NULL;
  jint *iv = NULL;
  jfloat *ov = NULL;
  (void) klass;

  set_last_error (NULL);
  if (!layers || !locked) goto done;
  if ((*env)->GetArrayLength (env, ints) < n * COMPOSITE_INTS || (*env)->GetArrayLength (env, opacity) < n)
    { set_last_error ("Bad layer description"); goto done; }
  iv = (*env)->GetIntArrayElements (env, ints, NULL);
  ov = (*env)->GetFloatArrayElements (env, opacity, NULL);
  if (!iv || !ov) goto done;

  for (jsize i = 0; i < n; i++)
    {
      GakoLayer *l = &layers[i];
      const jint *p = iv + i * COMPOSITE_INTS;
      jobject bmp = (*env)->GetObjectArrayElement (env, pixels, i);
      jobject mask = (*env)->GetObjectArrayElement (env, masks, i);
      l->x = p[0]; l->y = p[1]; l->parent = p[2];
      l->visible = (p[3] & 1) != 0; l->group = (p[3] & 2) != 0;
      l->mode = p[4]; l->blend_space = p[5]; l->composite_space = p[6]; l->composite_mode = p[7];
      l->opacity = ov[i];
      if (l->parent < -1 || l->parent >= n) l->parent = -1;
      if (bmp && !l->group)
        {
          void *px = NULL;
          if (AndroidBitmap_getInfo (env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
              info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
              AndroidBitmap_lockPixels (env, bmp, &px) != ANDROID_BITMAP_RESULT_SUCCESS)
            { set_last_error ("A layer isn't an ARGB_8888 bitmap"); goto done; }
          locked[n_locked++] = bmp;
          l->pixels = px; l->stride = (int) info.stride;
          l->width = (int) info.width; l->height = (int) info.height;
          if (mask)
            {
              void *mpx = NULL;
              if (AndroidBitmap_getInfo (env, mask, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
                  info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
                  (int) info.width == l->width && (int) info.height == l->height &&
                  AndroidBitmap_lockPixels (env, mask, &mpx) == ANDROID_BITMAP_RESULT_SUCCESS)
                {
                  locked[n_locked++] = mask;
                  l->mask = mpx; l->mask_stride = (int) info.stride;
                }
            }
        }
      else if (!l->group)
        l->visible = 0;
    }

  if (AndroidBitmap_getInfo (env, out_bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
      AndroidBitmap_lockPixels (env, out_bitmap, &out_px) != ANDROID_BITMAP_RESULT_SUCCESS)
    { set_last_error ("The output isn't an ARGB_8888 bitmap"); goto done; }
  rc = gako_composite (layers, (int) n, out_x, out_y, (int) info.width, (int) info.height,
                       out_px, (int) info.stride, threads, has_background, (uint32_t) background);
  AndroidBitmap_unlockPixels (env, out_bitmap);
  if (rc < 0) set_last_error ("Out of memory while compositing");

done:
  for (int k = 0; k < n_locked; k++)
    AndroidBitmap_unlockPixels (env, locked[k]);
  if (iv) (*env)->ReleaseIntArrayElements (env, ints, iv, JNI_ABORT);
  if (ov) (*env)->ReleaseFloatArrayElements (env, opacity, ov, JNI_ABORT);
  free (layers);
  free (locked);
  return rc;
}
