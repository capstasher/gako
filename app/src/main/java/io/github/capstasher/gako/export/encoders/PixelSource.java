package io.github.capstasher.gako.export.encoders;

/**
 * A read-only image, one row at a time, as non-premultiplied 0xAARRGGBB.
 * Encoders only depend on this, so they're plain Java and testable off-device.
 */
public interface PixelSource {
    int width();

    int height();

    /** Fills {@code argb} (at least {@link #width()} long) with row {@code y}. */
    void readRow(int y, int[] argb);

    /** True if any pixel is less than fully opaque. */
    static boolean hasTransparency(PixelSource src) {
        int[] row = new int[src.width()];
        for (int y = 0; y < src.height(); y++) {
            src.readRow(y, row);
            for (int x = 0; x < src.width(); x++) {
                if ((row[x] >>> 24) != 0xFF) return true;
            }
        }
        return false;
    }

    /** Composites a pixel over white, for formats without alpha. Returns 0xRRGGBB. */
    static int overWhite(int argb) {
        int a = argb >>> 24;
        if (a == 0xFF) return argb & 0xFFFFFF;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int inv = 255 - a;
        r = (r * a + 255 * inv + 127) / 255;
        g = (g * a + 255 * inv + 127) / 255;
        b = (b * a + 255 * inv + 127) / 255;
        return (r << 16) | (g << 8) | b;
    }
}
