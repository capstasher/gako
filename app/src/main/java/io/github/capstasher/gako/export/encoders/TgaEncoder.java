package io.github.capstasher.gako.export.encoders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Truevision TGA, 24 or 32-bit, top-left origin, optional RLE (GIMP's default). */
public final class TgaEncoder {
    private TgaEncoder() {}

    public static void write(PixelSource src, boolean alpha, boolean rle, OutputStream out) throws IOException {
        int w = src.width(), h = src.height();
        if (w > 0xFFFF || h > 0xFFFF) throw new IOException("TGA images can't be larger than 65535 px");
        int bytesPP = alpha ? 4 : 3;

        out.write(0);                       // id length
        out.write(0);                       // no colour map
        out.write(rle ? 10 : 2);            // truecolour (RLE)
        for (int i = 0; i < 5; i++) out.write(0);
        LE.u16(out, 0);                     // x origin
        LE.u16(out, 0);                     // y origin
        LE.u16(out, w);
        LE.u16(out, h);
        out.write(alpha ? 32 : 24);
        out.write((alpha ? 8 : 0) | 0x20);  // alpha bits, top-left origin

        int[] row = new int[w];
        byte[] px = new byte[w * bytesPP];
        ByteArrayOutputStream packets = new ByteArrayOutputStream(w * (bytesPP + 1));
        for (int y = 0; y < h; y++) {
            src.readRow(y, row);
            int o = 0;
            for (int x = 0; x < w; x++) {
                int p = alpha ? row[x] : PixelSource.overWhite(row[x]);
                px[o++] = (byte) p;
                px[o++] = (byte) (p >> 8);
                px[o++] = (byte) (p >> 16);
                if (alpha) px[o++] = (byte) (p >>> 24);
            }
            if (!rle) {
                out.write(px);
                continue;
            }
            packets.reset();
            int x = 0;
            while (x < w) {
                int run = 1;
                while (x + run < w && run < 128 && same(px, x, x + run, bytesPP)) run++;
                if (run >= 2) {
                    packets.write(0x80 | (run - 1));
                    packets.write(px, x * bytesPP, bytesPP);
                    x += run;
                } else {
                    int start = x;
                    int count = 0;
                    while (x < w && count < 128) {
                        if (x + 1 < w && same(px, x, x + 1, bytesPP)) break;
                        x++;
                        count++;
                    }
                    if (count == 0) { // next two are equal; emit as a run next loop
                        continue;
                    }
                    packets.write(count - 1);
                    packets.write(px, start * bytesPP, count * bytesPP);
                }
            }
            packets.writeTo(out);
        }

        // TGA 2.0 footer
        LE.u32(out, 0);
        LE.u32(out, 0);
        out.write("TRUEVISION-XFILE.".getBytes(StandardCharsets.US_ASCII));
        out.write(0);
    }

    private static boolean same(byte[] px, int a, int b, int n) {
        int ia = a * n, ib = b * n;
        for (int i = 0; i < n; i++) if (px[ia + i] != px[ib + i]) return false;
        return true;
    }
}
