package io.github.capstasher.gako.export.encoders;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** OpenRaster (.ora): the layered format GIMP, Krita and MyPaint all open. One layer for now. */
public final class OraEncoder {
    private OraEncoder() {}

    public static void write(File layerPng, byte[] thumbnailPng, int w, int h, String layerName,
                             OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);

        // "mimetype" must be first and stored uncompressed.
        byte[] mime = "image/openraster".getBytes(StandardCharsets.US_ASCII);
        ZipEntry m = new ZipEntry("mimetype");
        m.setMethod(ZipEntry.STORED);
        m.setSize(mime.length);
        m.setCompressedSize(mime.length);
        CRC32 crc = new CRC32();
        crc.update(mime);
        m.setCrc(crc.getValue());
        zip.putNextEntry(m);
        zip.write(mime);
        zip.closeEntry();

        String stack = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<image version=\"0.0.3\" w=\"" + w + "\" h=\"" + h + "\" xres=\"72\" yres=\"72\">\n"
            + "  <stack>\n"
            + "    <layer name=\"" + escape(layerName) + "\" src=\"data/layer0.png\" x=\"0\" y=\"0\""
            + " opacity=\"1.0\" visibility=\"visible\" composite-op=\"svg:src-over\"/>\n"
            + "  </stack>\n"
            + "</image>\n";
        zip.putNextEntry(new ZipEntry("stack.xml"));
        zip.write(stack.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        // PNGs are already compressed; deflating them again only costs time.
        zip.setLevel(0);
        copyEntry(zip, "data/layer0.png", layerPng);
        copyEntry(zip, "mergedimage.png", layerPng);
        zip.putNextEntry(new ZipEntry("Thumbnails/thumbnail.png"));
        zip.write(thumbnailPng);
        zip.closeEntry();
        zip.finish();
        zip.flush();
    }

    private static void copyEntry(ZipOutputStream zip, String name, File file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        }
        zip.closeEntry();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
