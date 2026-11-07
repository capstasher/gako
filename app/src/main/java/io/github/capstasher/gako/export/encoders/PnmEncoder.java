package io.github.capstasher.gako.export.encoders;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Binary PPM (P6). No alpha in the format, so transparency is flattened over white. */
public final class PnmEncoder {
    private PnmEncoder() {}

    public static void write(PixelSource src, OutputStream out) throws IOException {
        int w = src.width(), h = src.height();
        out.write(("P6\n# Created by GAKO\n" + w + " " + h + "\n255\n").getBytes(StandardCharsets.US_ASCII));
        int[] row = new int[w];
        byte[] line = new byte[w * 3];
        for (int y = 0; y < h; y++) {
            src.readRow(y, row);
            for (int x = 0, o = 0; x < w; x++) {
                int p = PixelSource.overWhite(row[x]);
                line[o++] = (byte) (p >> 16);
                line[o++] = (byte) (p >> 8);
                line[o++] = (byte) p;
            }
            out.write(line);
        }
    }
}
