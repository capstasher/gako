package io.github.capstasher.gako.export.encoders;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Baseline little-endian TIFF, 8 bits per sample, RGB or RGBA (unassociated alpha),
 * uncompressed or Deflate with the horizontal predictor. Strips are written as they're
 * encoded and the directory goes at the end, so memory stays at one strip.
 */
public final class TiffEncoder {
    private TiffEncoder() {}

    public static final int COMPRESSION_NONE = 1;
    public static final int COMPRESSION_DEFLATE = 8;

    private static final int SHORT = 3, LONG = 4, RATIONAL = 5, ASCII = 2;

    public static void write(PixelSource src, boolean alpha, int compression, File file) throws IOException {
        int w = src.width(), h = src.height();
        int spp = alpha ? 4 : 3;
        int rowBytes = w * spp;
        int rowsPerStrip = Math.max(1, Math.min(h, (64 * 1024) / Math.max(1, rowBytes)));
        boolean deflate = compression == COMPRESSION_DEFLATE;

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(0);
            raf.write(new byte[] {'I', 'I', 42, 0, 0, 0, 0, 0});   // IFD offset patched later

            List<long[]> strips = new ArrayList<>();   // {offset, length}
            int[] row = new int[w];
            byte[] stripBuf = new byte[rowBytes * rowsPerStrip];
            Deflater deflater = deflate ? new Deflater(6) : null;
            byte[] zbuf = new byte[64 * 1024];
            ByteArrayOutputStream zout = new ByteArrayOutputStream();

            for (int y0 = 0; y0 < h; y0 += rowsPerStrip) {
                int rows = Math.min(rowsPerStrip, h - y0);
                int o = 0;
                for (int r = 0; r < rows; r++) {
                    src.readRow(y0 + r, row);
                    int rowStart = o;
                    for (int x = 0; x < w; x++) {
                        int p = alpha ? row[x] : PixelSource.overWhite(row[x]);
                        stripBuf[o++] = (byte) (p >> 16);
                        stripBuf[o++] = (byte) (p >> 8);
                        stripBuf[o++] = (byte) p;
                        if (alpha) stripBuf[o++] = (byte) (p >>> 24);
                    }
                    if (deflate) {   // horizontal differencing, right to left
                        for (int i = rowStart + rowBytes - 1; i >= rowStart + spp; i--) {
                            stripBuf[i] = (byte) (stripBuf[i] - stripBuf[i - spp]);
                        }
                    }
                }
                long offset = raf.getFilePointer();
                if (deflate) {
                    deflater.reset();
                    deflater.setInput(stripBuf, 0, o);
                    deflater.finish();
                    zout.reset();
                    while (!deflater.finished()) {
                        int n = deflater.deflate(zbuf);
                        zout.write(zbuf, 0, n);
                    }
                    byte[] z = zout.toByteArray();
                    raf.write(z);
                    strips.add(new long[] {offset, z.length});
                } else {
                    raf.write(stripBuf, 0, o);
                    strips.add(new long[] {offset, o});
                }
            }
            if (deflater != null) deflater.end();

            // Out-of-line values
            align(raf);
            long bitsAt = raf.getFilePointer();
            for (int i = 0; i < spp; i++) writeShort(raf, 8);
            align(raf);
            long resAt = raf.getFilePointer();
            writeLong(raf, 72); writeLong(raf, 1);
            writeLong(raf, 72); writeLong(raf, 1);
            byte[] software = "GAKO\0".getBytes(StandardCharsets.US_ASCII);
            long softwareAt = raf.getFilePointer();
            raf.write(software);
            align(raf);
            int n = strips.size();
            long offsetsAt = 0, countsAt = 0;
            if (n > 1) {
                offsetsAt = raf.getFilePointer();
                for (long[] s : strips) writeLong(raf, s[0]);
                countsAt = raf.getFilePointer();
                for (long[] s : strips) writeLong(raf, s[1]);
            }

            // IFD
            align(raf);
            long ifdAt = raf.getFilePointer();
            List<long[]> e = new ArrayList<>();   // tag, type, count, value
            e.add(new long[] {256, LONG, 1, w});
            e.add(new long[] {257, LONG, 1, h});
            e.add(new long[] {258, SHORT, spp, bitsAt});
            e.add(new long[] {259, SHORT, 1, compression});
            e.add(new long[] {262, SHORT, 1, 2});                       // RGB
            e.add(new long[] {273, LONG, n, n > 1 ? offsetsAt : strips.get(0)[0]});
            e.add(new long[] {277, SHORT, 1, spp});
            e.add(new long[] {278, LONG, 1, rowsPerStrip});
            e.add(new long[] {279, LONG, n, n > 1 ? countsAt : strips.get(0)[1]});
            e.add(new long[] {282, RATIONAL, 1, resAt});
            e.add(new long[] {283, RATIONAL, 1, resAt + 8});
            e.add(new long[] {284, SHORT, 1, 1});                       // chunky
            e.add(new long[] {296, SHORT, 1, 2});                       // inch
            e.add(new long[] {305, ASCII, software.length, softwareAt});
            if (deflate) e.add(new long[] {317, SHORT, 1, 2});          // horizontal predictor
            if (alpha) e.add(new long[] {338, SHORT, 1, 2});            // unassociated alpha

            writeShort(raf, e.size());
            for (long[] t : e) {
                writeShort(raf, (int) t[0]);
                writeShort(raf, (int) t[1]);
                writeLong(raf, t[2]);
                boolean inline = (t[1] == SHORT && t[2] <= 2) || (t[1] == LONG && t[2] == 1);
                if (t[1] == SHORT && inline) {
                    writeShort(raf, (int) t[3]);
                    writeShort(raf, 0);
                } else {
                    writeLong(raf, t[3]);   // LONG value, or offset to the data
                }
            }
            writeLong(raf, 0);   // no next IFD

            raf.seek(4);
            writeLong(raf, ifdAt);
        }
    }

    private static void align(RandomAccessFile raf) throws IOException {
        if ((raf.getFilePointer() & 1) != 0) raf.write(0);
    }

    private static void writeShort(RandomAccessFile raf, int v) throws IOException {
        raf.write(v & 0xFF);
        raf.write((v >>> 8) & 0xFF);
    }

    private static void writeLong(RandomAccessFile raf, long v) throws IOException {
        raf.write((int) (v & 0xFF));
        raf.write((int) ((v >>> 8) & 0xFF));
        raf.write((int) ((v >>> 16) & 0xFF));
        raf.write((int) ((v >>> 24) & 0xFF));
    }
}
