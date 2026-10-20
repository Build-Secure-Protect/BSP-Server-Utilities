#!/usr/bin/env python3
"""Writes the Display Panel's assets (owner decision D41, the slim bezel): the blockstate, the models, the textures, the
loot table and the pickaxe tag. Edit this script, never the output.

The block is a 3-pixel slab against the wall behind it with a black face. The blockstate is a multipart: the bezel
strip on a side is drawn only when that side does not join another panel (UP, DOWN, LEFT, RIGHT), so a joined panel has
one bezel round its outside, and the small teal light only where neither RIGHT nor DOWN joins (the bottom-right corner).
LEFT and RIGHT are as seen from the front. The light's faces are full bright (Forge's forge_data).

  python3 tools/gen_panel_assets.py
"""
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MOD = "bsp_server_utilities"
ASSETS = ROOT / "src/main/resources/assets" / MOD
DATA = ROOT / "src/main/resources/data" / MOD
NAME = "display_panel"

FACE, BACK, BEZEL, BEZEL_HI, LED, LED_HI = (0x0C, 0x0E, 0x12), (0x15, 0x18, 0x1F), (0x2A, 0x2F, 0x3A), (0x3A, 0x40, 0x4C), (0x19, 0xD3, 0xB0), (0x8C, 0xF5, 0xE2)
ROTATION = {"north": 0, "east": 90, "south": 180, "west": 270}


def png(path, pixels):
    """pixels: rows of (r, g, b) tuples."""
    h, w = len(pixels), len(pixels[0])
    raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in pixels)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def textures():
    t = ASSETS / "textures/block"
    png(t / f"{NAME}_face.png", [[FACE] * 16 for _ in range(16)])
    png(t / f"{NAME}_back.png", [[BACK] * 16 for _ in range(16)])
    # the bezel: dark grey with a lighter top row, so its edge catches the light
    png(t / f"{NAME}_bezel.png", [[BEZEL_HI if y == 0 else BEZEL] * 16 for y in range(16)])
    png(t / f"{NAME}_led.png", [[LED_HI if 6 <= x <= 9 and 6 <= y <= 9 else LED for x in range(16)] for y in range(16)])


def faces(texture, uv=None, glow=False):
    out = {}
    for side in ("north", "south", "east", "west", "up", "down"):
        f = {"texture": texture}
        if uv:
            f["uv"] = uv
        if glow:
            f["forge_data"] = {"block_light": 15, "sky_light": 15}
        out[side] = f
    return out


def element(frm, to, texture, glow=False):
    return {"from": frm, "to": to, "faces": faces(texture, glow=glow)}


BASE = {"from": [0, 0, 13], "to": [16, 16, 16], "faces": {
    "north": {"texture": "#face", "uv": [0, 0, 16, 16]},
    "south": {"texture": "#back", "uv": [0, 0, 16, 16], "cullface": "south"},
    "east": {"texture": "#back", "uv": [13, 0, 16, 16]},
    "west": {"texture": "#back", "uv": [0, 0, 3, 16]},
    "up": {"texture": "#back", "uv": [0, 13, 16, 16]},
    "down": {"texture": "#back", "uv": [0, 0, 16, 3]}}}
Z0, Z1 = 12.75, 13
STRIPS = {
    "up": element([0, 15, Z0], [16, 16, Z1], "#bezel"),
    "down": element([0, 0, Z0], [16, 1, Z1], "#bezel"),
    "left": element([15, 0, Z0], [16, 16, Z1], "#bezel"),   # viewer's left on a panel facing north is east (+x)
    "right": element([0, 0, Z0], [1, 16, Z1], "#bezel"),    # viewer's right is west
}
LED_ELEMENT = element([1.5, 0.25, 12.5], [3, 0.75, Z0], "#led", glow=True)
TEXTURES = {"face": f"{MOD}:block/{NAME}_face", "back": f"{MOD}:block/{NAME}_back", "bezel": f"{MOD}:block/{NAME}_bezel",
            "led": f"{MOD}:block/{NAME}_led", "particle": f"{MOD}:block/{NAME}_bezel"}


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def models():
    m = ASSETS / "models/block"
    write(m / f"{NAME}_base.json", {"textures": TEXTURES, "elements": [BASE]})
    for side, el in STRIPS.items():
        write(m / f"{NAME}_bezel_{side}.json", {"textures": TEXTURES, "elements": [el]})
    write(m / f"{NAME}_led.json", {"textures": TEXTURES, "elements": [LED_ELEMENT]})
    # a lone panel, for the item and the particles
    write(m / f"{NAME}.json", {"textures": TEXTURES, "elements": [BASE, *STRIPS.values(), LED_ELEMENT],
                               "display": {"gui": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
                                           "fixed": {"rotation": [0, 180, 0], "scale": [0.5, 0.5, 0.5]}}})
    write(ASSETS / "models/item" / f"{NAME}.json", {"parent": f"{MOD}:block/{NAME}"})


def blockstate():
    parts = []
    for facing, y in ROTATION.items():
        def part(model, **when):
            parts.append({"when": {"facing": facing, **when}, "apply": {"model": f"{MOD}:block/{NAME}_{model}", "y": y}})
        part("base")
        for side in ("up", "down", "left", "right"):
            part(f"bezel_{side}", **{side: "false"})
        part("led", right="false", down="false")
    write(ASSETS / "blockstates" / f"{NAME}.json", {"multipart": parts})


def data():
    write(DATA / "loot_tables/blocks" / f"{NAME}.json", {"type": "minecraft:block", "pools": [{
        "rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{NAME}"}],
        "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write(ROOT / "src/main/resources/data/minecraft/tags/blocks/mineable/pickaxe.json", {"replace": False, "values": [f"{MOD}:{NAME}"]})


def main():
    textures()
    models()
    blockstate()
    data()
    print(f"{NAME}: 4 textures, 8 models, blockstate (multipart, {4 * 6} parts), loot table, pickaxe tag")


if __name__ == "__main__":
    main()
