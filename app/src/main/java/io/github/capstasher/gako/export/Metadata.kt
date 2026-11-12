package io.github.capstasher.gako.export

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * Photo metadata (EXIF) carried from an opened photo to exported files, as GIMP does. Location is
 * kept only when asked for: a shared edit shouldn't give away where the photo was taken by accident.
 */
object Metadata {
    private val tags = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
    )
    private val location = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    )

    /** The metadata worth keeping from a photo, or null if it has none. */
    fun read(input: InputStream): Map<String, String>? = runCatching {
        val exif = ExifInterface(input)
        (tags + location).mapNotNull { t -> exif.getAttribute(t)?.let { t to it } }.toMap().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun hasLocation(meta: Map<String, String>) = meta.containsKey(ExifInterface.TAG_GPS_LATITUDE)

    /** Formats ExifInterface can write into. */
    fun supports(format: ExportFormat) = format == ExportFormat.Jpeg || format == ExportFormat.Png || format == ExportFormat.Webp

    /** Writes [meta] into an already encoded image file. The pixels are upright now, and GAKO made them. */
    fun write(file: File, meta: Map<String, String>, withLocation: Boolean, width: Int, height: Int) {
        val exif = ExifInterface(file)
        for ((t, v) in meta) if (withLocation || t !in location) exif.setAttribute(t, v)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "GAKO")
        exif.saveAttributes()
    }
}
