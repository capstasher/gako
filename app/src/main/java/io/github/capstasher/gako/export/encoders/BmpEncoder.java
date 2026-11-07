package io.github.capstasher.gako.export.encoders;

import java.io.IOException;
import java.io.OutputStream;

/** Windows BMP: 24-bit when opaque, 32-bit with a V5 header (real alpha) otherwise. */
public final class BmpEncoder {
    private BmpEncoder() {}

    public static void write(PixelSource src, boolean alpha, OutputStream out) throws IOException {
        int w = src.width(), h = src.height();
        int bpp = alpha ? 32 : 24;
        int rowSize = ((w * bpp / 8) + 3) & ~3;
        int infoSize = alpha ? 124 : 40;
        int dataOffset = 14 + infoSize;
        long imageSize = (long) rowSize * h;

        // BITMAPFILEHEADER
        out.write('B');
        out.write('M');
        LE.u32(out, dataOffset + imageSize);
        LE.u16(out, 0);
        LE.u16(out, 0);
        LE.u32(out, dataOffset);

        // BITMAPINFOHEADER / BITMAPV5HEADER
        LE.u32(out, infoSize);
        LE.u32(out, w);
        LE.u32(out, h);                 // positive: rows stored bottom-up
        LE.u16(out, 1);                 // planes
        LE.u16(out, bpp);
        LE.u32(out, alpha ? 3 : 0);     // BI_BITFIELDS : BI_RGB
        LE.u32(out, imageSize);
        LE.u32(out, 2835);              // 72 dpi
        LE.u32(out, 2835);
        LE.u32(out, 0);
        LE.u32(out, 0);
        if (alpha) {
            LE.u32(out, 0x00FF0000L);   // red mask
            LE.u32(out, 0x0000FF00L);
            LE.u32(out, 0x000000FFL);
            LE.u32(out, 0xFF000000L);   // alpha mask
            LE.u32(out, 0x73524742L);   // 'sRGB'
            for (int i = 0; i < 9; i++) LE.u32(out, 0);  // endpoints
            for (int i = 0; i < 3; i++) LE.u32(out, 0);  // gamma
            LE.u32(out, 4);             // LCS_GM_IMAGES
            LE.u32(out, 0);
            LE.u32(out, 0);
            LE.u32(out, 0);
        }

        int[] row = new int[w];
        byte[] line = new byte[rowSize];
        for (int y = h - 1; y >= 0; y--) {
            src.readRow(y, row);
            int o = 0;
            for (int x = 0; x < w; x++) {
                int p = alpha ? row[x] : PixelSource.overWhite(row[x]);
                line[o++] = (byte) p;           // B
                line[o++] = (byte) (p >> 8);    // G
                line[o++] = (byte) (p >> 16);   // R
                if (alpha) line[o++] = (byte) (p >>> 24);
            }
            out.write(line);
        }
    }
}
