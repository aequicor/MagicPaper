#!/usr/bin/env python3
"""MagicPaper — генератор иконок всех платформ из одного исходника.

Единственный источник истины — векторная геометрия в координатах 108x108
(сетка адаптивной иконки Android). Из неё выводятся:
  - SVG: master / round / square / adaptive fg+bg / monochrome / maskable
  - Android: vector XML (bg/fg/monochrome) + PNG для всех mipmap-*
  - Web: favicon.svg/.png, apple-touch-icon, PWA manifest + иконки
  - Desktop: PNG для иконки окна, .icns (iconutil), .ico, 512png (deb)
  - iOS: png для AppIcon.appiconset (на будущее)

Требует: resvg (brew install resvg), macOS iconutil.
Запуск из корня репозитория: python3 assets/icon/gen_icons.py
"""
import math
import os
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

# ---------- палитра бренда (совпадает с MagicTheme) ----------
BG_TOP = "#9586C8"
BG_MID = "#7B69AB"
BG_BOT = "#5E4E88"
PAPER = "#F8F2E4"
FOLD = "#E3D6BA"
LINE = "#4C4368"
STAR = "#FFF6E3"
SAGE = "#C7D9B7"
BLUSH = "#F0BFB1"


def f2(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def sparkle(cx, cy, r, rot=0.0, waist=0.16):
    """Четырёхлучевая «магическая» звезда с вогнутыми сторонами."""
    c, s = math.cos(rot), math.sin(rot)

    def pt(a, rr):
        x, y = rr * math.cos(a), rr * math.sin(a)
        return f2(cx + x * c - y * s), f2(cy + x * s + y * c)

    tip = math.radians(90)
    pts = []
    for k in range(4):
        a = tip + k * math.pi / 2
        pts.append((pt(a, r), pt(a + math.pi / 4, r * waist)))
    d = f"M {pts[0][0][0]},{pts[0][0][1]} "
    for k in range(4):
        mid = pts[k][1]
        nxt = pts[(k + 1) % 4][0]
        d += f"Q {mid[0]},{mid[1]} {nxt[0]},{nxt[1]} "
    return d + "Z"


def circle(cx, cy, r):
    dd = f2(2 * r)
    r = f2(r)
    return (f"M {f2(cx)},{f2(cy - float(r))} a {r},{r} 0 1,0 {dd},0 "
            f"a {r},{r} 0 1,0 -{dd},0 Z")


# ---------- геометрия (108-сетка) ----------
# Лист бумаги: скруглённые углы, надорванный правый верхний угол (dog-ear).
SHEET = ("M 40.5 32 L 63 32 L 71 40 L 71 73.5 Q 71 77 67.5 77 "
         "L 40.5 77 Q 37 77 37 73.5 L 37 35.5 Q 37 32 40.5 32 Z")
FOLD_TRI = "M 63 32 L 71 40 L 63.6 40 Q 63 40 63 39.4 Z"
SHADOW = ("M 41.7 34.3 L 64.2 34.3 L 72.2 42.2 L 72.2 75.7 Q 72.2 79.2 68.7 79.2 "
          "L 41.7 79.2 Q 38.2 79.2 38.2 75.7 L 38.2 37.8 Q 38.2 34.3 41.7 34.3 Z")
LINES = ["M 43 46 L 65 46", "M 43 53 L 65 53", "M 43 60 L 57 60"]

STAR_BIG = sparkle(71, 31, 15, rot=math.radians(10))
STAR_SAGE = sparkle(35, 27, 6.2, rot=math.radians(45))
STAR_BLUSH = sparkle(76.5, 66, 7.2, rot=math.radians(15))
DOT_A = circle(84.5, 44, 1.7)
DOT_B = circle(27.5, 59.5, 1.5)
HALO = circle(71, 31, 19.5)

ROT = "rotate(-7 54 55)"
# Композиция вписывается в «безопасную зону» адаптивной иконки (66dp):
# диагональный габарит арта ~87 ед., поэтому контент-слой масштабируется в 0.72.
SCALE = 0.72


def art(lines=True, shadow=True, halo=True, small_stars=True):
    parts = [f'<g transform="{ROT}">']
    if shadow:
        parts.append(f'<path d="{SHADOW}" fill="#241B3F" fill-opacity="0.30"/>')
    parts.append(f'<path d="{SHEET}" fill="{PAPER}"/>')
    parts.append(f'<path d="{FOLD_TRI}" fill="{FOLD}"/>')
    if lines:
        for ln in LINES:
            parts.append(f'<path d="{ln}" stroke="{LINE}" stroke-width="2.4" stroke-linecap="round"/>')
    parts.append("</g>")
    if halo:
        parts.append(f'<path d="{HALO}" fill="#FFFFFF" fill-opacity="0.10"/>')
    parts.append(f'<path d="{STAR_BIG}" fill="{STAR}"/>')
    if small_stars:
        parts.append(f'<path d="{STAR_SAGE}" fill="{SAGE}"/>')
        parts.append(f'<path d="{STAR_BLUSH}" fill="{BLUSH}"/>')
        parts.append(f'<path d="{DOT_A}" fill="#FFFFFF" fill-opacity="0.85"/>')
        parts.append(f'<path d="{DOT_B}" fill="#FFFFFF" fill-opacity="0.85"/>')
    return "".join(parts)


ART = art()
# Упрощённый вариант для малых размеров (16–48px): без теней и строк текста —
# на мелком масштабе они дают грязь, остаётся читаемый силуэт «бумага + звезда».
ART_SMALL = art(lines=False, shadow=False, halo=False, small_stars=False)

MONO = (f'<g transform="{ROT}">'
        f'<path d="{SHEET}" fill="none" stroke="#FFFFFF" stroke-width="2.8"/>'
        f'<path d="{FOLD_TRI}" fill="#FFFFFF"/></g>'
        f'<path d="{STAR_BIG}" fill="#FFFFFF"/>'
        f'<path d="{STAR_SAGE}" fill="#FFFFFF"/>'
        f'<path d="{STAR_BLUSH}" fill="#FFFFFF"/>')

BG_DEFS = (
    f'<linearGradient id="bg" x1="0" y1="0" x2="0.35" y2="1">'
    f'<stop offset="0" stop-color="{BG_TOP}"/>'
    f'<stop offset="0.55" stop-color="{BG_MID}"/>'
    f'<stop offset="1" stop-color="{BG_BOT}"/></linearGradient>'
    '<radialGradient id="glow" cx="0.42" cy="0.12" r="0.75">'
    '<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.18"/>'
    '<stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>')

BG_PLATES = ('<rect width="108" height="108" fill="url(#bg)"/>'
             '<rect width="108" height="108" fill="url(#glow)"/>')
CORNER_SVG = "M 0 23.8 Q 0 0 23.8 0 L 84.2 0 Q 108 0 108 23.8 L 108 84.2 Q 108 108 84.2 108 L 23.8 108 Q 0 108 0 84.2 Z"


def svg(inner, defs=""):
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
            + (f"<defs>{defs}</defs>" if defs else "") + inner + "</svg>")


def clipped(mask_id, mask_shape, art=ART, scale=1.0):
    """Иконка целиком: фон + арт (масштаб арт-слоя регулируется отдельно)."""
    body = art if scale == 1.0 else \
        f'<g transform="translate(54 54) scale({scale}) translate(-54 -54)">{art}</g>'
    return svg(f'<g clip-path="url(#{mask_id})">{BG_PLATES}{body}</g>',
               BG_DEFS + f'<clipPath id="{mask_id}">{mask_shape}</clipPath>')


SVG_MASTER = clipped("rr", f'<path d="{CORNER_SVG}"/>')
# macOS (Big Sur) сетка: контент 824/1024 (~0.805) по центру, поля прозрачные —
# иначе иконка в Dock/Finder выглядит заметно крупнее системных.
SVG_MAC = svg(f'<g transform="translate(54 54) scale(0.805) translate(-54 -54)">'
              f'<g clip-path="url(#rr)">{BG_PLATES}{ART}</g></g>',
              BG_DEFS + f'<clipPath id="rr"><path d="{CORNER_SVG}"/></clipPath>')
SVG_MAC_SMALL = svg(f'<g transform="translate(54 54) scale(0.805) translate(-54 -54)">'
                    f'<g clip-path="url(#rr)">{BG_PLATES}{ART_SMALL}</g></g>',
                    BG_DEFS + f'<clipPath id="rr"><path d="{CORNER_SVG}"/></clipPath>')
SVG_ROUND = clipped("cc", '<circle cx="54" cy="54" r="54"/>')
# Малые растры: та же композиция, но упрощённый арт чуть крупнее (нет мелочи).
SVG_MASTER_SMALL = clipped("rr", f'<path d="{CORNER_SVG}"/>', ART_SMALL, 0.84)
SVG_ROUND_SMALL = clipped("cc", '<circle cx="54" cy="54" r="54"/>', ART_SMALL, 0.84)
SVG_SQUARE = svg(f"{BG_PLATES}{ART}", BG_DEFS)
SVG_ADAPTIVE_BG = svg('<rect width="108" height="108" fill="url(#bg)"/>', BG_DEFS)
SVG_FG = svg(f'<g transform="translate(54 54) scale({SCALE}) translate(-54 -54)">{ART}</g>')
SVG_MONO = svg(f'<g transform="translate(54 54) scale({SCALE}) translate(-54 -54)">{MONO}</g>')
# maskable: фон на всю площадь, центр — в безопасной зоне 66dp (scale 0.61)
SVG_MASKABLE = svg(f"{BG_PLATES}"
                   f'<g transform="translate(54 54) scale(0.61) translate(-54 -54)">{ART}</g>', BG_DEFS)

# ---------- Android vector XML ----------
def android_art_group():
    lines = [
        f'    <group android:rotation="-7" android:pivotX="54" android:pivotY="55">',
        f'        <path android:pathData="{SHADOW}" android:fillColor="#4D241B3F"/>',
        f'        <path android:pathData="{SHEET}" android:fillColor="#FF{PAPER[1:]}"/>',
        f'        <path android:pathData="{FOLD_TRI}" android:fillColor="#FF{FOLD[1:]}"/>',
    ]
    for ln in LINES:
        lines.append(f'        <path android:pathData="{ln}" android:strokeColor="#FF{LINE[1:]}" android:strokeWidth="2.4" android:strokeLineCap="round"/>')
    lines.append("    </group>")
    scale_g = f'android:scaleX="{SCALE}" android:scaleY="{SCALE}" android:pivotX="54" android:pivotY="54"'
    lines.append(f"    <group {scale_g}>")
    lines.append(f'        <path android:pathData="{HALO}" android:fillColor="#1AFFFFFF"/>')
    for d, col in [(STAR_BIG, STAR), (STAR_SAGE, SAGE), (STAR_BLUSH, BLUSH)]:
        lines.append(f'        <path android:pathData="{d}" android:fillColor="#FF{col[1:]}"/>')
    for d in (DOT_A, DOT_B):
        lines.append(f'        <path android:pathData="{d}" android:fillColor="#D9FFFFFF"/>')
    lines.append("    </group>")
    return "\n".join(lines)


def vector_wrap(body):
    return ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            + body + "\n</vector>\n")


ANDROID_BG_XML = vector_wrap(
    '    <path android:pathData="M0,0h108v108h-108z">\n'
    '        <aapt:attr name="android:fillColor" xmlns:aapt="http://schemas.android.com/aapt">\n'
    '            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="37.8" android:endY="108">\n'
    f'                <item android:offset="0" android:color="#FF{BG_TOP[1:]}"/>\n'
    f'                <item android:offset="0.55" android:color="#FF{BG_MID[1:]}"/>\n'
    f'                <item android:offset="1" android:color="#FF{BG_BOT[1:]}"/>\n'
    '            </gradient>\n'
    '        </aapt:attr>\n'
    '    </path>\n'
    '    <path android:pathData="M0,0h108v108h-108z">\n'
    '        <aapt:attr name="android:fillColor" xmlns:aapt="http://schemas.android.com/aapt">\n'
    '            <gradient android:type="radial" android:centerX="45" android:centerY="13" android:gradientRadius="82">\n'
    '                <item android:offset="0" android:color="#2EFFFFFF"/>\n'
    '                <item android:offset="1" android:color="#00FFFFFF"/>\n'
    '            </gradient>\n'
    '        </aapt:attr>\n'
    '    </path>')

ANDROID_FG_XML = vector_wrap(android_art_group())

ANDROID_MONO_XML = vector_wrap(
    f'    <group android:rotation="-7" android:pivotX="54" android:pivotY="55">\n'
    f'        <path android:pathData="{SHEET}" android:strokeColor="#FFFFFFFF" android:strokeWidth="2.8"/>\n'
    f'        <path android:pathData="{FOLD_TRI}" android:fillColor="#FFFFFFFF"/>\n'
    '    </group>\n'
    f'    <group android:scaleX="{SCALE}" android:scaleY="{SCALE}" android:pivotX="54" android:pivotY="54">\n'
    f'        <path android:pathData="{STAR_BIG}" android:fillColor="#FFFFFFFF"/>\n'
    f'        <path android:pathData="{STAR_SAGE}" android:fillColor="#FFFFFFFF"/>\n'
    f'        <path android:pathData="{STAR_BLUSH}" android:fillColor="#FFFFFFFF"/>\n'
    '    </group>')


def run(cmd):
    subprocess.run(cmd, check=True)


def resvg(in_svg, out_png, width):
    os.makedirs(os.path.dirname(out_png), exist_ok=True)
    run(["resvg", "-w", str(width), "-h", str(width), str(in_svg), str(out_png)])


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(content)


def main():
    src = os.path.join(HERE, "src")
    os.makedirs(src, exist_ok=True)
    write(os.path.join(src, "master.svg"), SVG_MASTER)
    write(os.path.join(src, "mac.svg"), SVG_MAC)
    write(os.path.join(src, "mac_small.svg"), SVG_MAC_SMALL)
    write(os.path.join(src, "round.svg"), SVG_ROUND)
    write(os.path.join(src, "square.svg"), SVG_SQUARE)
    write(os.path.join(src, "adaptive_background.svg"), SVG_ADAPTIVE_BG)
    write(os.path.join(src, "adaptive_foreground.svg"), SVG_FG)
    write(os.path.join(src, "monochrome.svg"), SVG_MONO)
    write(os.path.join(src, "maskable.svg"), SVG_MASKABLE)

    # ---------- Android ----------
    res = os.path.join(ROOT, "androidApp/src/main/res")
    write(os.path.join(res, "drawable/ic_launcher_background.xml"), ANDROID_BG_XML)
    write(os.path.join(res, "drawable/ic_launcher_foreground.xml"), ANDROID_FG_XML)
    write(os.path.join(res, "drawable/ic_launcher_monochrome.xml"), ANDROID_MONO_XML)
    shutil.rmtree(os.path.join(res, "drawable-v24"), ignore_errors=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        write(os.path.join(res, f"mipmap-anydpi-v26/{name}.xml"),
              '<?xml version="1.0" encoding="utf-8"?>\n'
              '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
              '    <background android:drawable="@drawable/ic_launcher_background" />\n'
              '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
              '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
              '</adaptive-icon>\n')
    for dpi, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        small = size <= 72
        m = "master_small.svg" if small else "master.svg"
        r = "round_small.svg" if small else "round.svg"
        write(os.path.join(src, "master_small.svg"), SVG_MASTER_SMALL)
        write(os.path.join(src, "round_small.svg"), SVG_ROUND_SMALL)
        resvg(os.path.join(src, m), os.path.join(res, f"mipmap-{dpi}/ic_launcher.png"), size)
        resvg(os.path.join(src, r), os.path.join(res, f"mipmap-{dpi}/ic_launcher_round.png"), size)

    # ---------- Web ----------
    web = os.path.join(ROOT, "webApp/src/webMain/resources")
    write(os.path.join(web, "favicon.svg"), SVG_MASTER)
    resvg(os.path.join(src, "master.svg"), os.path.join(web, "favicon.png"), 48)
    resvg(os.path.join(src, "master.svg"), os.path.join(web, "icons/apple-touch-icon.png"), 180)
    resvg(os.path.join(src, "master.svg"), os.path.join(web, "icons/icon-192.png"), 192)
    resvg(os.path.join(src, "master.svg"), os.path.join(web, "icons/icon-512.png"), 512)
    resvg(os.path.join(src, "maskable.svg"), os.path.join(web, "icons/icon-maskable-512.png"), 512)
    write(os.path.join(web, "manifest.webmanifest"),
          '{\n'
          '  "name": "MagicPaper",\n'
          '  "short_name": "MagicPaper",\n'
          '  "description": "MagicPaper — карманный ИИ-агент на магической бумаге.",\n'
          '  "start_url": "./index.html",\n'
          '  "display": "standalone",\n'
          '  "background_color": "#F5EFE3",\n'
          '  "theme_color": "#7B69AB",\n'
          '  "icons": [\n'
          '    { "src": "icons/icon-192.png", "sizes": "192x192", "type": "image/png" },\n'
          '    { "src": "icons/icon-512.png", "sizes": "512x512", "type": "image/png" },\n'
          '    { "src": "icons/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable" }\n'
          '  ]\n'
          '}\n')

    # ---------- Desktop: иконки окна/дока в рантайме ----------
    # На macOS берём сетку с полями (0.805) — как у системных иконок,
    # иначе приложение визуально крупнее соседей в Доке и Launchpad.
    dres = os.path.join(ROOT, "desktopApp/src/main/resources/icons")
    write(os.path.join(src, "master_small.svg"), SVG_MASTER_SMALL)
    sys_name = "mac_small.svg" if sys.platform == "darwin" else "master_small.svg"
    big_name = "mac.svg" if sys.platform == "darwin" else "master.svg"
    for size in (16, 32, 48, 128, 256):
        m = sys_name if size <= 48 else big_name
        resvg(os.path.join(src, m), os.path.join(dres, f"app_{size}.png"), size)

    # ---------- Дистрибутивы ----------
    dist = os.path.join(HERE, "dist")
    shutil.rmtree(dist, ignore_errors=True)
    os.makedirs(dist, exist_ok=True)
    resvg(os.path.join(src, "master.svg"), os.path.join(dist, "magicpaper_1024.png"), 1024)
    resvg(os.path.join(src, "master.svg"), os.path.join(dist, "magicpaper_512.png"), 512)

    iconset = os.path.join(dist, "AppIcon.iconset")
    os.makedirs(iconset)
    for name, size in {
        "icon_16x16.png": 16, "icon_16x16@2x.png": 32,
        "icon_32x32.png": 32, "icon_32x32@2x.png": 64,
        "icon_128x128.png": 128, "icon_128x128@2x.png": 256,
        "icon_256x256.png": 256, "icon_256x256@2x.png": 512,
        "icon_512x512.png": 512, "icon_512x512@2x.png": 1024,
    }.items():
        m = "mac_small.svg" if size <= 48 else "mac.svg"
        resvg(os.path.join(src, m), os.path.join(iconset, name), size)
    run(["iconutil", "-c", "icns", "-o", os.path.join(dist, "magicpaper.icns"), iconset])

    pngs = []
    for size in (16, 24, 32, 48, 64, 128, 256):
        m = "master_small.svg" if size <= 48 else "master.svg"
        p = os.path.join(dist, f"_ico_{size}.png")
        resvg(os.path.join(src, m), p, size)
        pngs.append((size, open(p, "rb").read()))
    n = len(pngs)
    offset = 6 + 16 * n
    entries = b""
    for size, png in pngs:
        w = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", w, w, 0, 0, 1, 32, len(png), offset)
        offset += len(png)
    with open(os.path.join(dist, "magicpaper.ico"), "wb") as fh:
        fh.write(struct.pack("<HHH", 0, 1, n) + entries + b"".join(png for _, png in pngs))
    for size, _ in pngs:
        os.remove(os.path.join(dist, f"_ico_{size}.png"))
    shutil.rmtree(iconset)

    # ---------- iOS (на будущее: iosApp ещё нет в сборке) ----------
    ios = os.path.join(HERE, "dist/ios")
    for scale, s in [(2, 40), (3, 40), (2, 60), (3, 60), (1, 76), (2, 76),
                     (2, 83.5), (1, 120), (2, 120), (1, 152), (1, 167), (1, 1024)]:
        px = int(round(s * scale))
        resvg(os.path.join(src, "square.svg"), os.path.join(ios, f"AppIcon-{px}x{px}.png"), px)

    print("OK:", os.path.relpath(dist, ROOT), "+ android res + web resources + desktop png")


if __name__ == "__main__":
    main()
