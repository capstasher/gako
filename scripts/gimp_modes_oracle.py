# Makes the reference images for GimpModesTest with GIMP itself (GIMP 3.x on the host):
#
#   gimp-console -i --quit --batch-interpreter=python-fu-eval \
#     -b "exec(open('scripts/gimp_modes_oracle.py').read())"        # from the repository root
#
# Writes app/src/androidTest/assets/gimp/modes.zip (one small XCF per case) and expected.bin
# (gzipped "name hex-RGBA" lines: what GIMP drew for each). Every case is 12×12: a backdrop layer
# with one colour per row (opaque, see-through and empty) under a layer with one colour per column.
# See-through colours are ones that survive Android's premultiplied 8-bit storage unchanged.
import gi, os, gzip, tempfile, zipfile
gi.require_version('Gimp', '3.0'); gi.require_version('Gegl', '0.4')
from gi.repository import Gimp, Gegl, Gio
ASSETS = os.path.abspath('app/src/androidTest/assets/gimp')
OUT = tempfile.mkdtemp()
B = [(255,255,255,255),(0,0,0,255),(200,120,40,255),(30,90,220,255),(128,128,128,255),(250,10,130,255),
     (64,200,100,255),(180,180,30,255),(199,120,40,128),(28,88,219,64),(255,255,255,200),(0,0,0,0)]
L = [(0,0,0,255),(255,255,255,255),(128,128,128,255),(230,60,20,255),(20,160,240,255),(100,220,90,255),
     (60,30,120,255),(250,200,40,255),(229,60,20,128),(20,159,240,200),(0,0,0,128),(90,90,90,0)]
N = 12
R = Gegl.Rectangle.new(0, 0, N, N)
def px(f):
    out = bytearray()
    for y in range(N):
        for x in range(N): out += bytes(f(x, y))
    return bytes(out)
ROWS = px(lambda x, y: B[y])
COLS = px(lambda x, y: L[x])
TRANS = px(lambda x, y: L[y])
def layer(img, name, data, mode=28, opacity=100.0, parent=None, pos=0):
    l = Gimp.Layer.new(img, name, N, N, Gimp.ImageType.RGBA_IMAGE, opacity, Gimp.LayerMode(mode))
    img.insert_layer(l, parent, pos)
    b = l.get_buffer(); b.set(R, "R'G'B'A u8", data); b.flush()
    return l
def group(img, mode, opacity):
    g = Gimp.GroupLayer.new(img, "group")
    img.insert_layer(g, None, 0)
    g.set_mode(Gimp.LayerMode(mode)); g.set_opacity(opacity)
    return g
lines = []
def finish(img, name):
    Gimp.file_save(Gimp.RunMode.NONINTERACTIVE, img, Gio.File.new_for_path(f"{OUT}/{name}.xcf"), None)
    vis = Gimp.Layer.new_from_visible(img, img, "vis")
    img.insert_layer(vis, None, 0)
    data = vis.get_buffer().get(R, 1.0, "R'G'B'A u8", Gegl.AbyssPolicy.NONE)
    lines.append(f"{name} {bytes(data).hex()}")
    img.delete()
def new():
    return Gimp.Image.new(N, N, Gimp.ImageBaseType.RGB)

modes = [0] + list(range(3, 22)) + [23, 24, 25, 26, 27, 28] + list(range(30, 57))
for m in modes:
    for o in (100, 60):
        img = new(); layer(img, "bg", ROWS); layer(img, "top", COLS, m, float(o)); finish(img, f"m{m}_o{o}")
# The bottom layer of a stack is drawn as if Normal.
for m in (3, 30, 31, 23):
    img = new(); layer(img, "only", COLS, m, 60.0); finish(img, f"bottom_m{m}")
# Groups
img = new(); layer(img, "bg", ROWS); g = group(img, 28, 100.0); layer(img, "c", COLS, 30, 100.0, g); finish(img, "group_iso28_c30")
img = new(); layer(img, "bg", ROWS); g = group(img, 61, 100.0); layer(img, "c", COLS, 30, 100.0, g); finish(img, "group_pass_c30")
img = new(); layer(img, "bg", ROWS); g = group(img, 61, 60.0); layer(img, "c", COLS, 30, 100.0, g); finish(img, "group_pass60_c30")
img = new(); layer(img, "bg", ROWS); g = group(img, 30, 60.0); layer(img, "c1", COLS, 28, 100.0, g); layer(img, "c2", TRANS, 31, 70.0, g); finish(img, "group_m30_two")
img = new(); layer(img, "bg", ROWS); g = group(img, 3, 60.0); layer(img, "c1", COLS, 0, 100.0, g); layer(img, "c2", TRANS, 4, 70.0, g); finish(img, "group_m3_two")
img = new(); layer(img, "bg", ROWS); g = group(img, 61, 100.0); layer(img, "c1", TRANS, 31, 80.0, g); layer(img, "c2", COLS, 30, 70.0, g); finish(img, "group_pass_two")
# Masks: the mask's value goes 0 → 255 down the rows.
MASK = px(lambda x, y: (y * 255 // (N - 1),) * 3 + (255,))
for m in (0, 28, 30, 3):
    img = new(); layer(img, "bg", ROWS); t = layer(img, "top", COLS, m, 80.0)
    mk = t.create_mask(Gimp.AddMaskType.WHITE); t.add_mask(mk)
    b = mk.get_buffer(); b.set(R, "Y' u8", bytes(MASK[i] for i in range(0, len(MASK), 4))); b.flush()
    finish(img, f"mask_m{m}")
# Blend space, composite space and composite mode set by hand.
def custom(name, m, blend=None, comp=None, cmode=None, o=80.0):
    img = new(); layer(img, "bg", ROWS); t = layer(img, "top", COLS, m, o)
    if blend is not None: t.set_blend_space(Gimp.LayerColorSpace(blend))
    if comp is not None: t.set_composite_space(Gimp.LayerColorSpace(comp))
    if cmode is not None: t.set_composite_mode(Gimp.LayerCompositeMode(cmode))
    finish(img, name)
custom("cs_m28_perceptual", 28, comp=4)
custom("bs_m30_perceptual", 30, blend=4)
custom("bs_m31_linear", 31, blend=1)
custom("cm_m30_union", 30, cmode=1)
custom("cm_m31_cliplayer", 31, cmode=3)
custom("cm_m28_intersect", 28, cmode=4)
custom("cm_m23_union_cs_perc", 23, comp=4, cmode=1)
os.makedirs(ASSETS, exist_ok=True)
with gzip.open(f"{ASSETS}/expected.bin", "wt") as f:
    f.write("\n".join(lines) + "\n")
with zipfile.ZipFile(f"{ASSETS}/modes.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for name in sorted(os.listdir(OUT)):
        z.write(os.path.join(OUT, name), name)
print("cases", len(lines), "written to", ASSETS)
