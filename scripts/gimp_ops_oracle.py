# Makes the reference images for GimpModesTest#appliesGimpColourEffects with GIMP itself:
#
#   gimp-console -i --quit --batch-interpreter=python-fu-eval \
#     -b "exec(open('scripts/gimp_ops_oracle.py').read())"          # from the repository root
#
# One small XCF per case: a colour test card with one of GIMP's own colour operations on it as a
# layer effect (GIMP 3 makes filters non-destructive), and what GIMP drew. Writes
# app/src/androidTest/assets/gimp/ops.zip and ops-expected.bin (gzipped "name hex-RGBA" lines).
import gi, os, gzip, tempfile, zipfile, colorsys
gi.require_version('Gimp', '3.0'); gi.require_version('Gegl', '0.4')
from gi.repository import Gimp, Gegl, Gio
ASSETS = os.path.abspath('app/src/androidTest/assets/gimp')
OUT = tempfile.mkdtemp()
W, H = 16, 12
R = Gegl.Rectangle.new(0, 0, W, H)
def card():
    out = bytearray()
    for y in range(H):
        for x in range(W):
            if y == H - 1:                       # greys, and a see-through end (exact in 8 bits)
                v = x * 17 if x < 12 else (203, 221, 237, 255)[x - 12]
                out += bytes((v, v, v, 255 if x < 12 else 128))
            else:
                r, g, b = colorsys.hls_to_rgb(x / W, 0.1 + 0.8 * y / (H - 2), 0.85 if y % 2 else 0.45)
                out += bytes((int(r * 255), int(g * 255), int(b * 255), 255))
    return bytes(out)
CARD = card()
lines = []
def case(name, op, settings):
    img = Gimp.Image.new(W, H, Gimp.ImageBaseType.RGB)
    l = Gimp.Layer.new(img, "card", W, H, Gimp.ImageType.RGBA_IMAGE, 100.0, Gimp.LayerMode.NORMAL)
    img.insert_layer(l, None, 0)
    b = l.get_buffer(); b.set(R, "R'G'B'A u8", CARD); b.flush()
    f = Gimp.DrawableFilter.new(l, op, "")
    c = f.get_config()
    for k, v in settings:
        c.set_property(k, v)
    f.update(); l.append_filter(f)
    Gimp.file_save(Gimp.RunMode.NONINTERACTIVE, img, Gio.File.new_for_path(f"{OUT}/{name}.xcf"), None)
    vis = Gimp.Layer.new_from_visible(img, img, "vis"); img.insert_layer(vis, None, 0)
    lines.append(f"{name} {bytes(vis.get_buffer().get(R, 1.0, chr(82) + chr(39) + 'G' + chr(39) + 'B' + chr(39) + 'A u8', Gegl.AbyssPolicy.NONE)).hex()}")
    img.delete()
HC = Gimp.HistogramChannel
case("bc_up", "gimp:brightness-contrast", [("brightness", 0.2), ("contrast", 0.3)])
case("bc_down", "gimp:brightness-contrast", [("brightness", -0.3), ("contrast", -0.2)])
case("levels", "gimp:levels", [("channel", HC.VALUE), ("low-input", 0.1), ("gamma", 1.5), ("high-output", 0.9),
                               ("channel", HC.RED), ("gamma", 0.7)])
case("levels_perceptual", "gimp:levels", [("trc", Gimp.TRCType.NON_LINEAR), ("channel", HC.VALUE), ("low-input", 0.15), ("gamma", 0.8)])
case("huesat", "gimp:hue-saturation", [("range", Gimp.HueRange.ALL), ("hue", 0.25), ("saturation", -0.3), ("lightness", 0.1),
                                       ("range", Gimp.HueRange.RED), ("hue", -0.2), ("overlap", 0.4)])
# GIMP 3.2 saves (and draws) Colorize with its defaults whatever a script sets: this tests those.
case("colorize", "gimp:colorize", [("hue", 0.6), ("saturation", 0.7), ("lightness", 0.2)])
case("balance", "gimp:color-balance", [("range", Gimp.TransferMode.MIDTONES), ("cyan-red", 0.4), ("magenta-green", -0.3),
                                       ("range", Gimp.TransferMode.SHADOWS), ("yellow-blue", 0.5), ("preserve-luminosity", True)])
case("balance_plain", "gimp:color-balance", [("range", Gimp.TransferMode.HIGHLIGHTS), ("yellow-blue", -0.6), ("preserve-luminosity", False)])
case("threshold", "gimp:threshold", [("channel", HC.VALUE), ("low", 0.4), ("high", 0.8)])
case("threshold_lum", "gimp:threshold", [("channel", HC.LUMINANCE), ("low", 0.3), ("high", 1.0)])
case("posterize", "gimp:posterize", [("levels", 4)])
for m, mode in enumerate(["lightness", "luma", "average", "luminance", "value"]):
    case(f"desaturate_{mode}", "gimp:desaturate", [("mode", Gimp.DesaturateMode(m))])
# No dates or file names in the archives, so the same cases always give the same bytes.
os.makedirs(ASSETS, exist_ok=True)
with open(f"{ASSETS}/ops-expected.bin", "wb") as raw, gzip.GzipFile("", "wb", fileobj=raw, mtime=0) as f:
    f.write(("\n".join(lines) + "\n").encode())
with zipfile.ZipFile(f"{ASSETS}/ops.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for name in sorted(os.listdir(OUT)):
        with open(os.path.join(OUT, name), "rb") as src:
            z.writestr(zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)), src.read(), zipfile.ZIP_DEFLATED, 9)
print("cases", len(lines))
