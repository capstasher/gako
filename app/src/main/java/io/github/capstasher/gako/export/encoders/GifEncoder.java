package io.github.capstasher.gako.export.encoders;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * GIF89a: median-cut palette (up to 256 colours, one reserved for transparency when needed),
 * optional Floyd–Steinberg dithering, LZW-compressed. Pixels under 50% alpha become transparent.
 */
public final class GifEncoder {
    private GifEncoder() {}

    private static final int BINS = 32 * 32 * 32;   // 5 bits per channel

    public static void write(PixelSource src, boolean dither, OutputStream out) throws IOException {
        int w = src.width(), h = src.height();
        if (w > 0xFFFF || h > 0xFFFF) throw new IOException("GIF images can't be larger than 65535 px");

        // 1. histogram
        long[] count = new long[BINS], sr = new long[BINS], sg = new long[BINS], sb = new long[BINS];
        boolean transparent = false;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.readRow(y, row);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                if ((p >>> 24) < 128) { transparent = true; continue; }
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int bin = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
                count[bin]++; sr[bin] += r; sg[bin] += g; sb[bin] += b;
            }
        }

        // 2. median cut
        int maxColours = transparent ? 255 : 256;
        int[] palette = medianCut(count, sr, sg, sb, maxColours);
        int nColours = palette.length;
        int transparentIndex = transparent ? nColours : -1;
        int tableColours = transparent ? nColours + 1 : nColours;
        int tableBits = 1;
        while ((1 << tableBits) < Math.max(2, tableColours)) tableBits++;

        // 3. map pixels to indices
        int[] lut = new int[BINS];
        java.util.Arrays.fill(lut, -1);
        byte[] indices = new byte[w * h];
        float[] errCur = dither ? new float[(w + 2) * 3] : null;
        float[] errNext = dither ? new float[(w + 2) * 3] : null;
        for (int y = 0; y < h; y++) {
            src.readRow(y, row);
            if (dither) {
                float[] t = errCur; errCur = errNext; errNext = t;
                java.util.Arrays.fill(errNext, 0f);
            }
            for (int x = 0; x < w; x++) {
                int p = row[x];
                int at = y * w + x;
                if ((p >>> 24) < 128) { indices[at] = (byte) transparentIndex; continue; }
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (dither) {
                    int e = (x + 1) * 3;
                    r = clamp(Math.round(r + errCur[e]));
                    g = clamp(Math.round(g + errCur[e + 1]));
                    b = clamp(Math.round(b + errCur[e + 2]));
                }
                int bin = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
                int idx = lut[bin];
                if (idx < 0) idx = lut[bin] = nearest(palette, r | 4, g | 4, b | 4);
                indices[at] = (byte) idx;
                if (dither) {
                    int c = palette[idx];
                    float er = r - ((c >> 16) & 0xFF), eg = g - ((c >> 8) & 0xFF), eb = b - (c & 0xFF);
                    int e = (x + 1) * 3;
                    errCur[e + 3] += er * 7 / 16f; errCur[e + 4] += eg * 7 / 16f; errCur[e + 5] += eb * 7 / 16f;
                    errNext[e - 3] += er * 3 / 16f; errNext[e - 2] += eg * 3 / 16f; errNext[e - 1] += eb * 3 / 16f;
                    errNext[e] += er * 5 / 16f; errNext[e + 1] += eg * 5 / 16f; errNext[e + 2] += eb * 5 / 16f;
                    errNext[e + 3] += er / 16f; errNext[e + 4] += eg / 16f; errNext[e + 5] += eb / 16f;
                }
            }
        }

        // 4. file
        out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
        LE.u16(out, w);
        LE.u16(out, h);
        out.write(0x80 | 0x70 | (tableBits - 1));   // global table, 8-bit resolution
        out.write(0);
        out.write(0);
        for (int i = 0; i < (1 << tableBits); i++) {
            int c = i < nColours ? palette[i] : 0;
            out.write((c >> 16) & 0xFF);
            out.write((c >> 8) & 0xFF);
            out.write(c & 0xFF);
        }
        if (transparent) {
            out.write(new byte[] {0x21, (byte) 0xF9, 4, 1, 0, 0, (byte) transparentIndex, 0});
        }
        out.write(0x2C);
        LE.u16(out, 0);
        LE.u16(out, 0);
        LE.u16(out, w);
        LE.u16(out, h);
        out.write(0);
        int minCodeSize = Math.max(2, tableBits);
        out.write(minCodeSize);
        new Lzw(out, minCodeSize).encode(indices);
        out.write(0);      // end of image data
        out.write(0x3B);   // trailer
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static int nearest(int[] palette, int r, int g, int b) {
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int c = palette[i];
            int dr = ((c >> 16) & 0xFF) - r, dg = ((c >> 8) & 0xFF) - g, db = (c & 0xFF) - b;
            int d = dr * dr * 2 + dg * dg * 4 + db * db * 3;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    // ---- median cut over the 5-bit histogram

    private static final class Box {
        int r0, r1, g0, g1, b0, b1;   // inclusive bin ranges
        long pixels;
    }

    private static int[] medianCut(long[] count, long[] sr, long[] sg, long[] sb, int max) {
        List<Box> boxes = new ArrayList<>();
        Box all = new Box();
        all.r0 = 0; all.r1 = 31; all.g0 = 0; all.g1 = 31; all.b0 = 0; all.b1 = 31;
        shrink(all, count);
        if (all.pixels == 0) return new int[] {0};
        boxes.add(all);

        while (boxes.size() < max) {
            Box target = null;
            long bestScore = -1;
            for (Box bx : boxes) {
                int span = Math.max(bx.r1 - bx.r0, Math.max(bx.g1 - bx.g0, bx.b1 - bx.b0));
                if (span == 0) continue;
                long score = bx.pixels * (span + 1);
                if (score > bestScore) { bestScore = score; target = bx; }
            }
            if (target == null) break;
            Box other = split(target, count);
            if (other == null) break;
            boxes.add(other);
        }

        int[] palette = new int[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            Box bx = boxes.get(i);
            long n = 0, r = 0, g = 0, b = 0;
            for (int ri = bx.r0; ri <= bx.r1; ri++)
                for (int gi = bx.g0; gi <= bx.g1; gi++)
                    for (int bi = bx.b0; bi <= bx.b1; bi++) {
                        int bin = (ri << 10) | (gi << 5) | bi;
                        n += count[bin]; r += sr[bin]; g += sg[bin]; b += sb[bin];
                    }
            if (n == 0) n = 1;
            palette[i] = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
        }
        return palette;
    }

    private static void shrink(Box bx, long[] count) {
        int r0 = 31, r1 = 0, g0 = 31, g1 = 0, b0 = 31, b1 = 0;
        long n = 0;
        for (int ri = bx.r0; ri <= bx.r1; ri++)
            for (int gi = bx.g0; gi <= bx.g1; gi++)
                for (int bi = bx.b0; bi <= bx.b1; bi++) {
                    long c = count[(ri << 10) | (gi << 5) | bi];
                    if (c == 0) continue;
                    n += c;
                    r0 = Math.min(r0, ri); r1 = Math.max(r1, ri);
                    g0 = Math.min(g0, gi); g1 = Math.max(g1, gi);
                    b0 = Math.min(b0, bi); b1 = Math.max(b1, bi);
                }
        bx.pixels = n;
        if (n > 0) { bx.r0 = r0; bx.r1 = r1; bx.g0 = g0; bx.g1 = g1; bx.b0 = b0; bx.b1 = b1; }
    }

    /** Splits along the longest axis at the pixel median; returns the new upper half. */
    private static Box split(Box bx, long[] count) {
        int dr = bx.r1 - bx.r0, dg = bx.g1 - bx.g0, db = bx.b1 - bx.b0;
        int axis = dr >= dg && dr >= db ? 0 : (dg >= db ? 1 : 2);
        int lo = axis == 0 ? bx.r0 : axis == 1 ? bx.g0 : bx.b0;
        int hi = axis == 0 ? bx.r1 : axis == 1 ? bx.g1 : bx.b1;
        long[] slice = new long[32];
        for (int ri = bx.r0; ri <= bx.r1; ri++)
            for (int gi = bx.g0; gi <= bx.g1; gi++)
                for (int bi = bx.b0; bi <= bx.b1; bi++) {
                    long c = count[(ri << 10) | (gi << 5) | bi];
                    slice[axis == 0 ? ri : axis == 1 ? gi : bi] += c;
                }
        long half = bx.pixels / 2, acc = 0;
        int cut = lo;
        for (int i = lo; i < hi; i++) {
            acc += slice[i];
            cut = i;
            if (acc >= half) break;
        }
        Box upper = new Box();
        upper.r0 = bx.r0; upper.r1 = bx.r1; upper.g0 = bx.g0; upper.g1 = bx.g1; upper.b0 = bx.b0; upper.b1 = bx.b1;
        if (axis == 0) { bx.r1 = cut; upper.r0 = cut + 1; }
        else if (axis == 1) { bx.g1 = cut; upper.g0 = cut + 1; }
        else { bx.b1 = cut; upper.b0 = cut + 1; }
        shrink(bx, count);
        shrink(upper, count);
        return upper.pixels > 0 ? upper : null;
    }

    // ---- LZW (variable code width, 12-bit max, open-addressed string table)

    private static final class Lzw {
        private static final int BITS = 12, HSIZE = 5003, MAXMAX = 1 << BITS;
        private final OutputStream out;
        private final int initBits, clearCode, eofCode;
        private final int[] htab = new int[HSIZE], codetab = new int[HSIZE];
        private int nBits, maxCode, freeEnt;
        private boolean clearFlag;
        private int accum, accBits;
        private final byte[] packet = new byte[255];
        private int packetLen;

        Lzw(OutputStream out, int minCodeSize) {
            this.out = out;
            this.initBits = minCodeSize + 1;
            this.clearCode = 1 << minCodeSize;
            this.eofCode = clearCode + 1;
        }

        void encode(byte[] pixels) throws IOException {
            nBits = initBits;
            maxCode = (1 << nBits) - 1;
            freeEnt = clearCode + 2;
            java.util.Arrays.fill(htab, -1);
            output(clearCode);

            int ent = pixels[0] & 0xFF;
            for (int i = 1; i < pixels.length; i++) {
                int c = pixels[i] & 0xFF;
                int fcode = (c << BITS) + ent;
                int h = (c << 4) ^ ent;
                if (htab[h] == fcode) { ent = codetab[h]; continue; }
                boolean found = false;
                if (htab[h] >= 0) {
                    int disp = h == 0 ? 1 : HSIZE - h;
                    do {
                        h -= disp;
                        if (h < 0) h += HSIZE;
                        if (htab[h] == fcode) { found = true; break; }
                    } while (htab[h] >= 0);
                }
                if (found) { ent = codetab[h]; continue; }
                output(ent);
                ent = c;
                if (freeEnt < MAXMAX) {
                    codetab[h] = freeEnt++;
                    htab[h] = fcode;
                } else {
                    java.util.Arrays.fill(htab, -1);
                    freeEnt = clearCode + 2;
                    clearFlag = true;
                    output(clearCode);
                }
            }
            output(ent);
            output(eofCode);
            while (accBits > 0) {
                byteOut(accum & 0xFF);
                accum >>>= 8;
                accBits = Math.max(0, accBits - 8);
            }
            flushPacket();
        }

        private void output(int code) throws IOException {
            accum &= (1 << accBits) - 1;
            accum |= code << accBits;
            accBits += nBits;
            while (accBits >= 8) {
                byteOut(accum & 0xFF);
                accum >>>= 8;
                accBits -= 8;
            }
            if (freeEnt > maxCode || clearFlag) {
                if (clearFlag) {
                    nBits = initBits;
                    maxCode = (1 << nBits) - 1;
                    clearFlag = false;
                } else {
                    nBits++;
                    maxCode = nBits == BITS ? MAXMAX : (1 << nBits) - 1;
                }
            }
        }

        private void byteOut(int b) throws IOException {
            packet[packetLen++] = (byte) b;
            if (packetLen == 255) flushPacket();
        }

        private void flushPacket() throws IOException {
            if (packetLen > 0) {
                out.write(packetLen);
                out.write(packet, 0, packetLen);
                packetLen = 0;
            }
        }
    }
}
