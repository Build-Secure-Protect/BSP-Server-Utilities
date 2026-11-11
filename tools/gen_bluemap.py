#!/usr/bin/env python3
"""Writes BlueMap's configuration for the staff web panel's map (SU-49; D259, D379 to D385, D397, D398).

One BlueMap command-line renderer reads every game server's world through read-only mounts and writes a flat map
(D397) of every dimension that is a place (D383): the overworld, the Nether and the End of each server, plus any other
modded dimension that holds anything, but never the storage grids (AE2 spatial storage, Compact Machines' rooms,
Create Teleporters' pocket). Everything generated is drawn (D398). The panel serves BlueMap's web folder itself (D381),
so BlueMap's own web server is off.

Map ids are the panel's: `<server>__<dimension>` (docs/API.md, "The staff web panel's map"; the panel's MapLive.mapId).
The server id is the name each game server has in BSP-Server Utilities' network config: the name the panel shows.

    python3 tools/gen_bluemap.py --out config --web /home/container/web --data /home/container/data \\
        --threads 4 --server "Frostwarf=/mnt/frostwarf/frostwarf" --server "BSP-Spawn=/mnt/spawn/spawn_void" ...

    python3 tools/gen_bluemap.py --self-test
"""
import argparse
import os
import re
import sys

SKIP_NAMESPACES = {"ae2", "compactmachines", "createteleporters"}  # storage grids, not places (D383)


def slug(s):
    t = re.sub(r"[^a-z0-9]+", "_", (s or "").lower()).strip("_")
    return t or "unknown"


def dimension_slug(dimension):
    d = (dimension or "").lower()
    return {"minecraft:overworld": "overworld", "minecraft:the_nether": "nether", "minecraft:the_end": "end"}.get(d, slug(d))


def map_id(server, dimension):
    """The panel's MapLive.mapId, character for character."""
    return slug(server) + "__" + dimension_slug(dimension)  # only letters, digits and _: BlueMap turns anything else into _


def dimension_label(dimension):
    s = dimension_slug(dimension)
    if s in ("overworld", "nether", "end"):
        return {"overworld": "Overworld", "nether": "Nether", "end": "End"}[s]
    path = dimension.split(":", 1)[-1].replace("_", " ")
    return path[:1].upper() + path[1:]


def holds_anything(region_dir):
    """A region folder with at least one region file holding chunks (an empty one is 8192 bytes or less)."""
    try:
        return any(f.endswith(".mca") and os.path.getsize(os.path.join(region_dir, f)) > 8192 for f in os.listdir(region_dir))
    except OSError:
        return False


def dimensions_of(world):
    """[(dimension id, region folder)] that this world has and that are places holding something."""
    found = []
    for dim, sub in (("minecraft:overworld", "region"), ("minecraft:the_nether", "DIM-1/region"), ("minecraft:the_end", "DIM1/region")):
        if os.path.isdir(os.path.join(world, sub)):
            found.append((dim, os.path.join(world, sub)))
    base = os.path.join(world, "dimensions")
    if os.path.isdir(base):
        for ns in sorted(os.listdir(base)):
            if ns in SKIP_NAMESPACES or not os.path.isdir(os.path.join(base, ns)):
                continue
            for path in sorted(os.listdir(os.path.join(base, ns))):
                region = os.path.join(base, ns, path, "region")
                if holds_anything(region):
                    found.append((ns + ":" + path, region))
    return found


def quote(s):
    return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'


def map_conf(world, dimension, name, sorting, ceiling):
    d = dimension_slug(dimension)
    nether, end = d == "nether", d == "end"
    sky = "#290000" if nether else "#080010" if end else "#7dabff"
    void = "#150000" if nether else "#080010" if end else "#000000"
    ambient = 0.1 if d == "overworld" else 0.6
    caves = 55 if d == "overworld" else -10000
    mask = "  {\n  }\n"
    if nether or ceiling:
        mask += "  {\n    subtract: true\n    min-y: 90\n    max-y: 127\n  }\n"  # the roof left out, as BlueMap's own Nether map
    return f"""# Written by tools/gen_bluemap.py (BSP-Server Utilities, the staff panel's map). Write it again rather than editing it.
world: {quote(world)}
dimension: {quote(dimension)}
name: {quote(name)}
sorting: {sorting}
start-pos: {{ x: 0, z: 0 }}
sky-color: "{sky}"
void-color: "{void}"
sky-light: 1
ambient-light: {ambient}
remove-caves-below-y: {caves}
cave-detection-ocean-floor: -5
cave-detection-uses-block-light: false
# Every chunk generated is drawn (D398)
min-inhabited-time: 0
render-mask: [
{mask}]
render-edges: true
edge-light-strength: 8
# Flat only (D397): one colour a block, about a thirtieth of the 3D map's size
enable-perspective-view: false
enable-flat-view: true
enable-free-flight-view: false
enable-hires: false
storage: "file"
ignore-missing-light-data: false
marker-sets: {{
}}
"""


def core_conf(data, threads):
    return f"""# Written by tools/gen_bluemap.py (BSP-Server Utilities, the staff panel's map).
# BlueMap downloads Mojang's 1.20.1 client jar for the game's own textures (D378).
accept-download: true
data: {quote(data)}
render-thread-count: {threads}
update-cooldown: 60
full-update-interval: 1440
region-file-check-interval: 5
scan-for-mod-resources: true
metrics: false
log: {{
  file: {quote(os.path.join(data, "logs", "debug.log"))}
  append: false
}}
"""


def file_storage_conf(web):
    return f"""# Written by tools/gen_bluemap.py: the maps go into the web folder the panel reads (map.root in panel.properties).
storage-type: file
root: {quote(os.path.join(web, "maps"))}
compression: gzip
"""


def webapp_conf(web):
    return f"""# Written by tools/gen_bluemap.py: BlueMap's page and settings are written into the web folder; the panel serves them.
enabled: true
webroot: {quote(web)}
update-settings-file: true
use-cookies: true
default-to-flat-view: true
min-zoom-distance: 5
max-zoom-distance: 100000
resolution-default: 1
hires-slider-max: 500
hires-slider-default: 100
hires-slider-min: 0
lowres-slider-max: 7000
lowres-slider-default: 2000
lowres-slider-min: 500
scripts: [
]
styles: [
]
"""


def webserver_conf(web, data):
    return f"""# Written by tools/gen_bluemap.py: off. The staff panel serves the map behind its own login (D381); no port is opened.
enabled: false
webroot: {quote(web)}
port: 8100
sse-enabled: false
log: {{
  file: {quote(os.path.join(data, "logs", "webserver.log"))}
  append: false
}}
"""


def self_test():
    cases = {("Frostwarf", "minecraft:overworld"): "frostwarf__overworld", ("BSP-Spawn", "minecraft:the_nether"): "bsp_spawn__nether",
             ("Moongrove", "minecraft:the_end"): "moongrove__end", ("Stormfall", "allthemodium:mining"): "stormfall__allthemodium_mining",
             ("", None): "unknown__unknown"}  # the same cases as the panel's MapLiveTest
    bad = [(k, v, map_id(*k)) for k, v in cases.items() if map_id(*k) != v]
    for k, want, got in bad:
        print(f"map_id{k}: {got}, the panel says {want}")
    conf = map_conf("/w", "minecraft:the_nether", "N", 1, False)
    if "enable-hires: false" not in conf or "subtract: true" not in conf:
        bad.append(("nether map", "flat, roof left out", conf))
    print("self-test: " + ("ok" if not bad else f"{len(bad)} wrong"))
    return 0 if not bad else 1


def main():
    a = argparse.ArgumentParser(description="BlueMap's configuration for the staff panel's map.")
    a.add_argument("--out", help="the config folder to write (BlueMap's -c)")
    a.add_argument("--web", help="BlueMap's web folder: its page and the maps (the panel's map.root, mounted read-only)")
    a.add_argument("--data", help="BlueMap's data folder (the client jar, render state, logs)")
    a.add_argument("--threads", type=int, default=2, help="render threads (about half the server's cores)")
    a.add_argument("--server", action="append", default=[], metavar="ID=WORLD[=HERE]",
                   help="a game server's id (as the panel shows it), its world folder as BlueMap's container sees it, and optionally where this script can read it (on the host); repeat")
    a.add_argument("--ceiling", action="append", default=[], metavar="DIMENSION", help="a modded dimension with a roof to leave out, like the Nether")
    a.add_argument("--self-test", action="store_true")
    o = a.parse_args()
    if o.self_test:
        return self_test()
    if not (o.out and o.web and o.data and o.server):
        a.error("--out, --web, --data and at least one --server are needed")
    maps_dir = os.path.join(o.out, "maps")
    os.makedirs(maps_dir, exist_ok=True)
    os.makedirs(os.path.join(o.out, "storages"), exist_ok=True)
    written, sorting = [], 0
    for entry in o.server:
        if "=" not in entry:
            a.error(f"--server {entry}: write it as ID=WORLD")
        parts = entry.split("=", 2)
        server, world = parts[0], parts[1]
        here = parts[2] if len(parts) > 2 else world
        if not os.path.isdir(here):
            print(f"{server}: no world folder at {here} (check the path or the mount): left out")
            continue
        for dimension, region in dimensions_of(here):
            mid = map_id(server, dimension)
            name = f"{server} · {dimension_label(dimension)}"
            with open(os.path.join(maps_dir, mid + ".conf"), "w", encoding="utf-8") as f:
                f.write(map_conf(world, dimension, name, sorting, dimension in o.ceiling))
            written.append(mid + ".conf")
            sorting += 1
    for name, text in (("core.conf", core_conf(o.data, o.threads)), ("webapp.conf", webapp_conf(o.web)),
                       ("webserver.conf", webserver_conf(o.web, o.data)), (os.path.join("storages", "file.conf"), file_storage_conf(o.web))):
        with open(os.path.join(o.out, name), "w", encoding="utf-8") as f:
            f.write(text)
    others = sorted(set(f for f in os.listdir(maps_dir) if f.endswith(".conf")) - set(written))
    print(f"Wrote {len(written)} maps to {maps_dir}: " + ", ".join(m[:-5] for m in written))
    if others:
        print("Also in that folder, not written now (delete them unless wanted): " + ", ".join(others))
    return 0


if __name__ == "__main__":
    sys.exit(main())
