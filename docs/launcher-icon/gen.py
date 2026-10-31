"""Bake Control Switch concept B (Pro-style mirror, emerald) into flat 108x108 paths and export
Android adaptive-icon layers, SVG, CSS, Compose ImageVector and PNGs."""
import base64, os, sys
import skia, cairosvg

OUT = sys.argv[1]
K = 0.5522847498

TOP, BOTTOM = "#43C78B", "#11935F"
GLYPH = "#FFFFFF"
GHOST_ALPHA, SHADOW_ALPHA, SHADOW_DY = 0.5, 0.14, 2.2
SPREAD, SCALE, KNOCK_W = 6, 0.7, 5.0
FIT = 0.965  # pulls the front grip tip inside the 66dp safe circle (canvas design reached 33.9)


def circle(p, cx, cy, r):
    k = K * r
    p.moveTo(cx - r, cy)
    p.cubicTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    p.cubicTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    p.cubicTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    p.cubicTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    p.close()


def poly(p, pts):
    p.moveTo(*pts[0])
    for q in pts[1:]:
        p.lineTo(*q)
    p.close()


def rel(x, y, steps):
    pts = [(x, y)]
    for dx, dy in steps:
        x, y = x + dx, y + dy
        pts.append((x, y))
    return pts[:-1]


def outer():
    p = skia.Path()
    p.moveTo(36, 38)
    p.cubicTo(42, 36.5, 48, 37.5, 54, 37.5)
    p.cubicTo(60, 37.5, 66, 36.5, 72, 38)
    p.cubicTo(80, 40, 84, 45, 85.5, 53)
    p.lineTo(88, 68)
    p.cubicTo(89, 75, 82, 79, 77, 74)
    p.lineTo(70.5, 66.5)
    p.cubicTo(66, 64.5, 60, 64, 54, 64)
    p.cubicTo(48, 64, 42, 64.5, 37.5, 66.5)
    p.lineTo(31, 74)
    p.cubicTo(26, 79, 19, 75, 20, 68)
    p.lineTo(22.5, 53)
    p.cubicTo(24, 45, 28, 40, 36, 38)
    p.close()
    return p


def controller():
    p = outer()
    circle(p, 36, 48, 5); circle(p, 36, 48, 2.6)            # left stick (ring + cap)
    poly(p, rel(42.4, 51, [(3.2, 0), (0, 3.4), (3.4, 0), (0, 3.2), (-3.4, 0), (0, 3.4),
                          (-3.2, 0), (0, -3.4), (-3.4, 0), (0, -3.2), (3.4, 0), (0, -3.4)]))  # d-pad
    for cx, cy in ((72, 44), (72, 52), (68, 48), (76, 48)):  # face buttons
        circle(p, cx, cy, 1.8)
    circle(p, 64, 56, 5); circle(p, 64, 56, 2.6)            # right stick
    poly(p, rel(46.5, 45, [(3.2, 0), (0, 1.4), (-3.2, 0), (0, -1.4)]))  # minus
    poly(p, rel(59.2, 43.4, [(1.4, 0), (0, 1.6), (1.6, 0), (0, 1.4), (-1.6, 0), (0, 1.6),
                            (-1.4, 0), (0, -1.6), (-1.6, 0), (0, -1.4), (1.6, 0), (0, -1.6)]))  # plus
    p.setFillType(skia.PathFillType.kEvenOdd)
    return p


def placed(path, cx, cy):
    m = skia.Matrix()
    m.setTranslate(cx, cy); m.preScale(SCALE, SCALE); m.preTranslate(-54, -57); m.postScale(FIT, FIT, 54, 54)
    q = skia.Path(path); q.transform(m)
    return q


front = placed(controller(), 54 + SPREAD, 54 + SPREAD)
back_full = placed(controller(), 54 - SPREAD, 54 - SPREAD)

# Knockout: the front's silhouette grown by half the gap, cut out of the ghost so the two read apart
# on any background (no background-coloured stroke needed, so the foreground layer is transparent).
paint = skia.Paint(Style=skia.Paint.kStroke_Style, StrokeWidth=KNOCK_W,
                   StrokeJoin=skia.Paint.kRound_Join)
o = outer()
grown = skia.Path()
paint.getFillPath(o, grown)
knock = skia.Op(grown, o, skia.PathOp.kUnion_PathOp)
knock = placed(knock, 54 + SPREAD, 54 + SPREAD)
ghost = skia.Op(back_full, knock, skia.PathOp.kDifference_PathOp)

shadow = skia.Path(front); shadow.offset(0, SHADOW_DY)


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def d(path):
    out = []
    V = skia.Path.Verb
    for verb, pts in path:
        if verb == V.kMove_Verb:
            out.append(f"M{fmt(pts[0].x())},{fmt(pts[0].y())}")
        elif verb == V.kLine_Verb:
            out.append(f"L{fmt(pts[1].x())},{fmt(pts[1].y())}")
        elif verb == V.kQuad_Verb:
            out.append("Q" + " ".join(f"{fmt(q.x())},{fmt(q.y())}" for q in pts[1:]))
        elif verb == V.kCubic_Verb:
            out.append("C" + " ".join(f"{fmt(q.x())},{fmt(q.y())}" for q in pts[1:]))
        elif verb == V.kConic_Verb:
            raise SystemExit("conic in output")
        elif verb == V.kClose_Verb:
            out.append("Z")
    return "".join(out)


def rule(path):
    return "evenodd" if path.getFillType() in (skia.PathFillType.kEvenOdd, skia.PathFillType.kInverseEvenOdd) else "nonzero"


L = {
    "ghost": (d(ghost), rule(ghost)),
    "shadow": (d(shadow), rule(shadow)),
    "front": (d(front), rule(front)),
}


def w(rel_path, text):
    full = os.path.join(OUT, rel_path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        f.write(text)
    return full


# ---------- SVG ----------
def svg(rx=None, bg=True, shadow_on=True, size=None, mono=False):
    attrs = f' width="{size}" height="{size}"' if size else ""
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"{attrs}>']
    if bg:
        parts.append(f'<defs><linearGradient id="csBg" x1="0" y1="0" x2="0" y2="108" gradientUnits="userSpaceOnUse">'
                     f'<stop offset="0" stop-color="{TOP}"/><stop offset="1" stop-color="{BOTTOM}"/></linearGradient></defs>')
        r = f' rx="{rx}"' if rx else ""
        parts.append(f'<rect width="108" height="108"{r} fill="url(#csBg)"/>')
    col = "#000000" if mono else GLYPH
    parts.append(f'<path fill="{col}" fill-opacity="{GHOST_ALPHA}" fill-rule="{L["ghost"][1]}" d="{L["ghost"][0]}"/>')
    if shadow_on:
        parts.append(f'<path fill="#000000" fill-opacity="{SHADOW_ALPHA}" fill-rule="{L["shadow"][1]}" d="{L["shadow"][0]}"/>')
    parts.append(f'<path fill="{col}" fill-rule="{L["front"][1]}" d="{L["front"][0]}"/>')
    parts.append("</svg>")
    return "".join(parts)


w("svg/control-switch-icon.svg", svg())                       # full-bleed, matches Android layers
w("svg/control-switch-icon-rounded.svg", svg(rx=26))           # Cocoon-style tile for web/docs
w("svg/control-switch-glyph.svg", svg(bg=False))               # glyph only, transparent
w("svg/control-switch-glyph-mono.svg", svg(bg=False, shadow_on=False, mono=True))

# ---------- Android adaptive icon ----------
A = 'xmlns:android="http://schemas.android.com/apk/res/android"'
HDR = '<?xml version="1.0" encoding="utf-8"?>\n'
VEC = '    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108"'

def vd_path(name, data, ftype, color="#FFFFFFFF", alpha=None):
    a = f'\n        android:fillAlpha="{alpha}"' if alpha is not None else ""
    ft = "evenOdd" if ftype == "evenodd" else "nonZero"
    return (f'    <path\n        android:name="{name}"\n        android:fillColor="{color}"{a}\n'
            f'        android:fillType="{ft}"\n        android:pathData="{data}" />\n')

w("android/res/drawable/ic_launcher_background.xml",
  HDR + "<!-- Launcher icon ground: Cocoon-style emerald, lighter at the top like Cocoon's folder tiles.\n"
  "     The launcher's mask supplies the shape. -->\n"
  f'<vector {A}\n    xmlns:aapt="http://schemas.android.com/aapt"\n{VEC}>\n'
  '    <path android:pathData="M0,0h108v108h-108z">\n'
  '        <aapt:attr name="android:fillColor">\n'
  '            <gradient\n                android:type="linear"\n                android:startX="54"\n'
  '                android:startY="0"\n                android:endX="54"\n                android:endY="108"\n'
  f'                android:startColor="#FF{TOP[1:]}"\n                android:endColor="#FF{BOTTOM[1:]}" />\n'
  '        </aapt:attr>\n    </path>\n</vector>\n')

w("android/res/drawable/ic_launcher_foreground.xml",
  HDR + "<!-- Launcher icon: a Pro-style controller mirrored onto a translucent copy of itself (input\n"
  "     mirroring). The ghost has the front's grown silhouette cut out of it, so the layer is\n"
  "     transparent and reads on any ground. Everything sits inside the 66dp safe circle.\n"
  "     Generated from docs/launcher-icon; edit there, not here. -->\n"
  f'<vector {A}\n    xmlns:tools="http://schemas.android.com/tools"\n{VEC}\n    tools:ignore="VectorPath">\n'
  "    <!-- VectorPath: long paths are fine here; the launcher rasterises this once per size. -->\n"
  + vd_path("ghost", L["ghost"][0], L["ghost"][1], alpha=GHOST_ALPHA)
  + vd_path("shadow", L["shadow"][0], L["shadow"][1], color="#FF000000", alpha=SHADOW_ALPHA)
  + vd_path("front", L["front"][0], L["front"][1])
  + "</vector>\n")

w("android/res/drawable/ic_launcher_monochrome.xml",
  HDR + "<!-- Themed-icon layer: the launcher uses only the alpha, so no shadow; the ghost keeps half alpha. -->\n"
  f'<vector {A}\n    xmlns:tools="http://schemas.android.com/tools"\n{VEC}\n    tools:ignore="VectorPath">\n'
  + vd_path("ghost", L["ghost"][0], L["ghost"][1], alpha=GHOST_ALPHA)
  + vd_path("front", L["front"][0], L["front"][1])
  + "</vector>\n")

ADAPT = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
         '  <background android:drawable="@drawable/ic_launcher_background"/>\n'
         '  <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
         '  <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n</adaptive-icon>\n')
w("android/res/mipmap-anydpi/ic_launcher.xml", ADAPT)
w("android/res/mipmap-anydpi/ic_launcher_round.xml", ADAPT)

# ---------- PNGs ----------
# Legacy fallbacks crop the 108 canvas to its middle 72dp-ish area like Android Studio does (viewBox 9..99).
def legacy(shape, px):
    clip = '<clipPath id="m"><rect x="9" y="9" width="90" height="90" rx="20"/></clipPath>' if shape == "square" \
        else '<clipPath id="m"><circle cx="54" cy="54" r="45"/></clipPath>'
    inner = svg()[svg().index(">") + 1:-len("</svg>")]
    s = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="9 9 90 90" width="{px}" height="{px}">'
         f'<defs>{clip}</defs><g clip-path="url(#m)">{inner}</g></svg>')
    return s

# minSdk 28 always uses the adaptive icon; the repo keeps only an xxxhdpi fallback, so match that.
for dens, px in (("xxxhdpi", 192),):
    for name, shape in (("ic_launcher", "square"), ("ic_launcher_round", "round")):
        dst = os.path.join(OUT, f"android/res/mipmap-{dens}/{name}.png")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        cairosvg.svg2png(bytestring=legacy(shape, px).encode(), write_to=dst)

os.makedirs(os.path.join(OUT, "png"), exist_ok=True)
for px in (512, 1024):
    cairosvg.svg2png(bytestring=svg(rx=26, size=px).encode(), write_to=os.path.join(OUT, f"png/control-switch-icon-{px}.png"))
cairosvg.svg2png(bytestring=svg(size=512).encode(), write_to=os.path.join(OUT, "png/control-switch-icon-fullbleed-512.png"))

# ---------- CSS ----------
uri = "data:image/svg+xml;base64," + base64.b64encode(svg(rx=26).encode()).decode()
glyph_uri = "data:image/svg+xml;base64," + base64.b64encode(svg(bg=False).encode()).decode()
w("css/control-switch-logo.css", f"""/* Control Switch logo — Cocoon-style tile. Generated from docs/launcher-icon/gen.py. */
:root {{
  --cs-emerald-top: {TOP};
  --cs-emerald-bottom: {BOTTOM};
  --cs-emerald-gradient: linear-gradient(180deg, var(--cs-emerald-top), var(--cs-emerald-bottom));
  --cs-glyph: {GLYPH};
  --cs-tile-radius: 24%;          /* 26/108, Cocoon's tile rounding */
  --cs-logo-size: 96px;
}}

/* Full icon (tile + glyph) as one element: <span class="cs-logo" role="img" aria-label="Control Switch"></span> */
.cs-logo {{
  display: inline-block;
  width: var(--cs-logo-size);
  height: var(--cs-logo-size);
  background: url("{uri}") center / contain no-repeat;
}}

/* Glyph on a CSS-drawn tile, so the ground can follow a theme: <span class="cs-logo-tile"></span> */
.cs-logo-tile {{
  display: inline-block;
  width: var(--cs-logo-size);
  height: var(--cs-logo-size);
  border-radius: var(--cs-tile-radius);
  background: url("{glyph_uri}") center / contain no-repeat, var(--cs-emerald-gradient);
}}
""")

# ---------- Compose ----------
def kpath(name, data, ftype, color, alpha):
    ft = "PathFillType.EvenOdd" if ftype == "evenodd" else "PathFillType.NonZero"
    return (f'        // {name}\n        addPath(\n            pathData = addPathNodes("{data}"),\n'
            f'            pathFillType = {ft},\n            fill = SolidColor(Color({color})),\n'
            f'            fillAlpha = {alpha}f,\n        )\n')

w("compose/ControlSwitchLogo.kt", f"""package com.odininputmirror.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// The launcher icon as a Compose ImageVector, for in-app use (splash, About, header) without the
// launcher's mask. Generated from docs/launcher-icon/gen.py — edit there and regenerate.
object ControlSwitchLogo {{
    val EmeraldTop = Color(0xFF{TOP[1:]})
    val EmeraldBottom = Color(0xFF{BOTTOM[1:]})

    /** Tile (rounded like a Cocoon icon) plus glyph. */
    val Tile: ImageVector by lazy {{ build(withTile = true) }}

    /** Glyph only, transparent ground. */
    val Glyph: ImageVector by lazy {{ build(withTile = false) }}

    private fun build(withTile: Boolean) = ImageVector.Builder(
        name = if (withTile) "ControlSwitchTile" else "ControlSwitchGlyph",
        defaultWidth = 108.dp,
        defaultHeight = 108.dp,
        viewportWidth = 108f,
        viewportHeight = 108f,
    ).apply {{
        if (withTile) {{
            addPath(
                pathData = addPathNodes("M26,0H82A26,26 0 0 1 108,26V82A26,26 0 0 1 82,108H26A26,26 0 0 1 0,82V26A26,26 0 0 1 26,0Z"),
                fill = Brush.linearGradient(listOf(EmeraldTop, EmeraldBottom), Offset(54f, 0f), Offset(54f, 108f)),
            )
        }}
{kpath("ghost", L["ghost"][0], L["ghost"][1], "0xFFFFFFFF", GHOST_ALPHA)}{kpath("shadow", L["shadow"][0], L["shadow"][1], "0xFF000000", SHADOW_ALPHA)}{kpath("front", L["front"][0], L["front"][1], "0xFFFFFFFF", 1.0)}    }}.build()
}}
""")

print("ok", {k: (len(v[0]), v[1]) for k, v in L.items()})
