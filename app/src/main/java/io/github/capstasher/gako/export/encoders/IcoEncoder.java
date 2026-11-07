package io.github.capstasher.gako.export.encoders;

import java.io.IOException;
import java.io.OutputStream;

/** Windows icon holding one PNG-compressed image (up to 256 × 256). */
public final class IcoEncoder {
    private IcoEncoder() {}

    public static void write(byte[] png, int w, int h, OutputStream out) throws IOException {
        if (w > 256 || h > 256) throw new IOException("Icons can be at most 256 × 256");
        LE.u16(out, 0);              // reserved
        LE.u16(out, 1);              // icon
        LE.u16(out, 1);              // one image
        out.write(w == 256 ? 0 : w);
        out.write(h == 256 ? 0 : h);
        out.write(0);                // no palette
        out.write(0);
        LE.u16(out, 1);              // planes
        LE.u16(out, 32);             // bpp
        LE.u32(out, png.length);
        LE.u32(out, 6 + 16);         // data offset
        out.write(png);
    }
}
