#!/usr/bin/env python3
"""Writes the server images: a 9 x 9 pixel icon per server of the network, in three looks.

  python3 tools/gen_server_icons.py

Each server's symbol is drawn here on a 7 x 7 grid in three shades of its colour (L light, B base, S shadow), a
second colour (l b s), the grey metal of the pack's icons (G light, g mid, h dark) and its outline colour (O). The
looks put it in a 9 x 9 icon, one chat line high:
  A plain   the symbol with a one-pixel outline in its own dark colour, corners clear
  B badge   the symbol on a round badge of its dark colour with a grey metal rim
  C shield  the symbol on a grey metal shield
Out: tools/server_icons/<look>/<server>.png (named as Velocity knows the servers) and
tools/preview/server_icons_concepts.html. No Pillow needed: the PNGs are written by hand.
"""
import base64
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tools/server_icons"
PREVIEW = ROOT / "tools/preview/server_icons_concepts.html"

GREY = {"G": "a4a8ae", "g": "7e838a", "h": "4b4f55"}

# name: (what it shows, main L B S, second l b s, outline, badge fill, rows)
SERVERS = {
    "BSP-Spawn": ("a gold star: where everyone starts", ("ffe9a8", "ffaa00", "a66e00"), ("ffffff", "ffffff", "ffffff"), "2e1f00", "4a3305", [
        "...L...",
        "..LLB..",
        "LLLBBBS",
        ".LBBBS.",
        "..BBS..",
        ".BS.SS.",
        ".S...S.",
    ]),
    "BSP-SafeRoom": ("a padlock: safe and locked", ("8ff5e0", "19d3b0", "0c8f78"), ("ffffff", "ffffff", "ffffff"), "06302a", "0b3d36", [
        "..ggg..",
        ".g...h.",
        ".G...h.",
        "LLLLLLB",
        "LBBOBBS",
        "LBBOBBS",
        "BSSSSSS",
    ]),
    "BSP-Sanctuary": ("a white temple: a place of peace", ("ffffff", "d9ccff", "9a86d6"), ("ffffff", "ffffff", "ffffff"), "2b2140", "3a2d5c", [
        "...L...",
        "..LLB..",
        ".LLBBB.",
        "SSSSSSS",
        ".B.B.B.",
        ".B.B.S.",
        "SSSSSSS",
    ]),
    "BSP-Frostwarf": ("a snowflake: ice and frost", ("ffffff", "8fd8ff", "3b8fd0"), ("ffffff", "ffffff", "ffffff"), "0b2a44", "123a5e", [
        "L..B..L",
        ".L.B.L.",
        "..LBL..",
        "BBBLBBB",
        "..SBS..",
        ".S.B.S.",
        "S..B..S",
    ]),
    "BSP-Boulderbrook": ("a boulder in a brook", ("bfe6ff", "3d8fe0", "1f5aa6"), ("ffffff", "ffffff", "ffffff"), "1a2230", "16304f", [
        "..ggg..",
        ".gGGgh.",
        "gGGgghh",
        "gGgghhh",
        ".hhhhh.",
        "BLBBLBB",
        ".SBSBS.",
    ]),
    "BSP-Moongrove": ("a crescent moon over a little tree", ("fff6c4", "e8cf6a", "a8902e"), ("8ff08f", "37a637", "6b4423"), "1e1636", "2a2050", [
        "..LLL..",
        ".LLB...",
        "LLB..l.",
        "LB..lbl",
        "LB...b.",
        ".SB..s.",
        "..SSS..",
    ]),
    "BSP-Stormfall": ("lightning from a storm cloud", ("fff27a", "ffcc00", "c98f00"), ("ffffff", "ffffff", "ffffff"), "1c1f26", "262a33", [
        "..gGg..",
        ".gGGGgh",
        "gGggggh",
        "hhhLhhh",
        "...LL..",
        "..LB...",
        "..B....",
    ]),
    "BSP-Test": ("a potion flask: trying things out", ("ff9cf2", "d14fc0", "8a2a80"), ("ffffff", "ffffff", "ffffff"), "2a0f2a", "3d153d", [
        "..hgh..",
        "...g...",
        "..gLg..",
        ".gLBBg.",
        "gLBBBSg",
        "gLBBSSg",
        ".ggggg.",
    ]),
}

LOOKS = {
    "a_plain": "A plain: the symbol with its own dark outline, corners clear (like the icons you sent)",
    "b_badge": "B badge: the symbol on a round badge of its dark colour, grey metal rim",
    "c_shield": "C shield: the symbol on a grey metal shield",
}


def rgba(hexcol, a=255):
    return (int(hexcol[0:2], 16), int(hexcol[2:4], 16), int(hexcol[4:6], 16), a)


def core_pixels(spec):
    """The 7 x 7 symbol as RGBA (None where clear)."""
    _, main, second, outline, _, rows = spec
    colours = {"L": main[0], "B": main[1], "S": main[2], "l": second[0], "b": second[1], "s": second[2], "O": outline, **GREY}
    return [[rgba(colours[c]) if c != "." else None for c in row] for row in rows]


def blank():
    return [[None] * 9 for _ in range(9)]


def disc_mask():
    rows = ["..xxxxx..", ".xxxxxxx.", "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", ".xxxxxxx.", "..xxxxx.."]
    return [[c == "x" for c in r] for r in rows]


def shield_mask():
    rows = ["xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", ".xxxxxxx.", "..xxxxx..", "...xxx..."]
    return [[c == "x" for c in r] for r in rows]


def edge(mask, x, y):
    """Whether a pixel of the mask touches the outside (orthogonally)."""
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = x + dx, y + dy
        if not (0 <= nx < 9 and 0 <= ny < 9) or not mask[ny][nx]:
            return True
    return False


def plain(spec):
    img = blank()
    core = core_pixels(spec)
    for y in range(7):
        for x in range(7):
            img[y + 1][x + 1] = core[y][x]
    out = [row[:] for row in img]
    o = rgba(spec[3])
    for y in range(9):
        for x in range(9):
            if img[y][x] is None and any(0 <= x + dx < 9 and 0 <= y + dy < 9 and img[y + dy][x + dx] is not None
                                         for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out[y][x] = o
    return out


def framed(spec, mask, fill_for):
    img = blank()
    for y in range(9):
        for x in range(9):
            if mask[y][x]:
                img[y][x] = fill_for(x, y, edge(mask, x, y))
    core = core_pixels(spec)
    for y in range(7):
        for x in range(7):
            if core[y][x] is not None and mask[y + 1][x + 1] and not edge(mask, x + 1, y + 1):
                img[y + 1][x + 1] = core[y][x]
    return img


def badge(spec):
    fill = rgba(spec[4])
    rim_light, rim, rim_dark = rgba(GREY["G"]), rgba(GREY["g"]), rgba(GREY["h"])

    def f(x, y, is_edge):
        if not is_edge:
            return fill
        return rim_light if x + y < 8 else rim if x + y == 8 else rim_dark
    return framed(spec, disc_mask(), f)


def shield(spec):
    light, mid, dark, outline = rgba(GREY["G"]), rgba(GREY["g"]), rgba(GREY["h"]), rgba("2a2d33")

    def f(x, y, is_edge):
        if is_edge:
            return outline
        return light if x + y < 6 else mid if x + y < 11 else dark
    return framed(spec, shield_mask(), f)


def png(pixels):
    h, w = len(pixels), len(pixels[0])
    raw = b"".join(b"\x00" + b"".join(bytes(p if p is not None else (0, 0, 0, 0)) for p in row) for row in pixels)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def preview(made):
    def img(data, scale):
        return f'<img src="data:image/png;base64,{base64.b64encode(data).decode()}" width="{9 * scale}" height="{9 * scale}" alt="">'
    head = (PREVIEW.parent / "server_images_refined.html").read_text()
    head = head[:head.index("<body>")].replace("<title>Server Images Refined</title>", "<title>Server Icons Concepts</title>")
    sections = []
    for look, title in LOOKS.items():
        cells = []
        for name, spec in SERVERS.items():
            data = made[look][name]
            cells.append(f'<div class="ic"><div class="big">{img(data, 8)}</div><b>{name}</b><span>{spec[0]}</span></div>')
        lines = "".join(f'<div>{img(made[look][n], 2)}<span>&lt;Kat_Miner&gt; hello from {n}</span></div>' for n in SERVERS)
        sections.append(f'''<section><h2>{title}</h2><div class="card"><div class="grid">{"".join(cells)}</div>
<div class="stage"><div class="world" style="width:440px"><div class="mc chatbox chat2">{lines}</div></div></div></div></section>''')
    style = """<style>
.grid{ display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:12px; }
.ic{ display:flex; flex-direction:column; align-items:center; gap:4px; text-align:center; font-size:12px; }
.ic span{ color:var(--muted); } .big{ background:#2a2f3a; padding:10px; border-radius:4px; line-height:0; }
img{ image-rendering:pixelated; image-rendering:crisp-edges; }
.chat2 > div{ height:18px !important; gap:6px; font-size:16px; } .chat2 img{ flex:none; }
</style>"""
    body = f'''<body><div class="wrap">
<header><h1>Server icons</h1><p class="lede">Mock-up set K: a 9 x 9 icon per server in the style of the pack's icons, in three looks. Each is drawn one chat line high, so in game it is exactly this many pixels; here at x8, and in a chat line at GUI scale 2. Pick a look; any single icon can be swapped for another idea.</p></header>
{"".join(sections)}
<section><h2>Decisions</h2><div class="card"><ol>
<li><b>K1 the look:</b> A plain (as the icons you sent), B on a round badge with a grey rim, or C on a grey shield.</li>
<li><b>K2 the symbols:</b> keep them all, or name the ones to change (for example "Sanctuary: a dove instead of a temple").</li>
</ol></div></section></div></body></html>'''
    PREVIEW.write_text(head + style + body)


def main():
    made = {}
    for look, make in (("a_plain", plain), ("b_badge", badge), ("c_shield", shield)):
        folder = OUT / look
        folder.mkdir(parents=True, exist_ok=True)
        made[look] = {}
        for name, spec in SERVERS.items():
            data = png(make(spec))
            (folder / f"{name}.png").write_bytes(data)
            made[look][name] = data
    preview(made)
    print(f"{len(SERVERS)} servers x {len(LOOKS)} looks in {OUT.relative_to(ROOT)}; preview {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
