#!/usr/bin/env python3
"""Writes the HTML pages behind the CurseForge pictures (tools/curseforge/<name>.html). The screens and faces are taken
from the confirmed mock-up pages in tools/preview/ (the looks the screens were built from); the block scenes, the
network diagram and the banner are drawn here in plain CSS (headless Chrome draws no WebGL). render.sh turns every page
into docs/curseforge/images/<name>.png. Edit this script, never the pages.

  python3 tools/curseforge/gen_pages.py && tools/curseforge/render.sh
"""
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
PREVIEW = HERE.parent / "preview"

EXTRA = """<style>
html, body { margin: 0; padding: 0; background: #1b2129; overflow: hidden; }
#shot { position: absolute; left: 0; top: 0; display: inline-block; }
.bgworld { padding: 18px; background:
    linear-gradient(rgba(10,14,20,.58), rgba(10,14,20,.58)),
    radial-gradient(ellipse at 20% 85%, #3d5a3a 0, transparent 45%),
    radial-gradient(ellipse at 75% 90%, #4b6b45 0, transparent 40%),
    radial-gradient(ellipse at 60% 20%, #6d8fb3 0, transparent 55%),
    linear-gradient(#8fb2d6, #5f7f9e 55%, #3a5137 56%, #2d3f2b); }
.cap { font: 600 13px "IBM Plex Sans", sans-serif; color: #e6eaf2; }
.capm { font: 400 12px "IBM Plex Sans", sans-serif; color: #9aa3b5; }
.vt { font-family: "VT323", monospace; -webkit-font-smoothing: none; }
</style>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=VT323&family=IBM+Plex+Sans:wght@400;600&display=swap">"""

MEASURE = """<script>
document.fonts.ready.then(() => setTimeout(() => {
  const r = document.getElementById('shot').getBoundingClientRect();
  document.title = Math.ceil(r.width) + 'x' + Math.ceil(r.height);
}, 300));
</script>"""


def head_of(source):
    """The page's head, and any styles left between it and the body (the setup guide's mock-ups keep theirs there)."""
    s = (PREVIEW / source).read_text()
    head = s[s.index("<head>") + 6:s.index("</head>")] + s[s.index("</head>") + 7:s.index("<body")]
    return re.sub(r"<title>.*?</title>", "", head)


def element(source, start, index):
    """The index-th element of the page whose opening tag starts with `start`, with everything inside it."""
    s = (PREVIEW / source).read_text()
    hits = [m.start() for m in re.finditer(re.escape(start), s)]
    i = hits[index]
    depth, pos = 0, i
    for m in re.finditer(r"<(/?)div\b[^>]*>", s[i:]):
        depth += -1 if m.group(1) else 1
        if depth == 0:
            pos = i + m.end()
            break
    return s[i:pos]


TABS = ["Rules", "Staff", "Log", "AFK", "Chunks", "Drops", "Clients"]


def seven_tabs(html):
    """Mock-ups drawn before the later tabs show three to six; the panel has seven, narrower, with the title smaller to fit."""
    def rebuild(m):
        on = re.search(r'<span class="tab on"[^>]*>([^<]*)</span>', m.group(0))
        picked = on.group(1) if on else ""
        return '<span class="tabs">' + "".join(
            f'<span class="tab{" on" if t == picked else ""}" style="width:{44 if t == "Clients" else 38}px">{t}</span>' for t in TABS) + "</span>"
    out = re.sub(r'<span class="tabs">(?:<span class="tab[^"]*"[^>]*>[^<]*</span>)+</span>', rebuild, html)
    if out != html:
        out = re.sub(r'<span class="ttl v"( style="font-size:[0-9.]+px")?>Server utilities</span>', '<span class="ttl v" style="font-size:10px">Server utilities</span>', out)
    return out


def page(name, head, body):
    (HERE / f"{name}.html").write_text(f'<!doctype html>\n<html><head><meta charset="utf-8">{head}{EXTRA}</head>\n'
                                       f'<body><div id="shot">{body}</div>\n{MEASURE}\n</body></html>\n')


def from_mockup(name, source, start, index, wrap=True, css="", swap=()):
    el = seven_tabs(element(source, start, index))
    for old, new in swap:
        el = el.replace(old, new)
    page(name, head_of(source) + (f"<style>{css}</style>" if css else ""), f'<div class="bgworld">{el}</div>' if wrap else el)


# ---------------------------------------------------------------- the screens and faces, from the confirmed mock-ups

SCREENS = [
    ("rules_popup", "rules_refined.html", '<div class="world"', 0, False),
    ("rules_read_only", "rules_refined.html", '<div class="world"', 1, False),
    ("admin_rules", "rules_refined.html", '<div class="scr v"', 0, True),
    ("panel_text", "panel_refined.html", '<div class="scr v"', 0, True),
    ("panel_image", "panel_refined.html", '<div class="scr v"', 1, True),
    ("face_staff", "staff_log_refined.html", '<div class="wallbg"', 0, False),
    ("face_log", "staff_log_refined.html", '<div class="wallbg"', 1, False),
    ("admin_staff", "staff_log_refined.html", '<div class="scr v"', 0, True),
    ("admin_log", "staff_log_refined.html", '<div class="scr v"', 1, True, ".tbl { font-size: 7.9px; }"),  # the mock-up's server column ran off the edge
    ("admin_staff_group", "staff_log_refined.html", '<div class="scr v"', 2, True),
    ("actionlog_screen", "staff_log_refined.html", '<div class="world"', 0, False),
    ("panel_staff_pane", "staff_log_refined.html", '<div class="scr v"', 3, True),
    ("panel_log_pane", "staff_log_refined.html", '<div class="scr v"', 4, True),
    ("admin_afk", "afk_refined.html", '<div class="scr v"', 0, True, "", [("AFK now · 5", "AFK now · 4")]),  # the built tab counts staff typed away apart
    ("afk_player", "afk_refined.html", '<div class="world"', 0, False),
    ("admin_chunks", "chunks_refined.html", '<div class="scr v"', 0, True, ".tbl td:nth-child(5) { display: none; }",
     [("on BSP-Wild 20 min", "away 20 min"), ("<td>Loaded</td><td>Unloaded</td>", "<td>On</td><td>Off</td>")]),  # as built: mods are in a player's view
    ("admin_chunks_player", "chunks_refined.html", '<div class="scr v"', 1, True),
    ("chunks_return", "chunks_refined.html", '<div class="world"', 0, False),
    ("admin_drops", "drops_refined.html", '<div class="scr v"', 0, True),
    ("admin_drops_search", "drops_refined.html", '<div class="scr v"', 1, True),
    ("clear_chat", "drops_refined.html", '<div class="world"', 0, False),
    ("check_refused", "clients_refined.html", '<div class="dirt"', 0, False),
    ("check_kicked", "clients_refined.html", '<div class="dirt"', 1, False),
    ("check_bright", "clients_refined.html", '<div class="world"', 0, False),
    ("admin_clients", "clients_refined.html", '<div class="scr v"', 0, True),
    ("admin_clients_packs", "clients_refined.html", '<div class="scr v"', 1, True),
    ("admin_clients_rules", "clients_refined.html", '<div class="scr v"', 2, True),
    ("setup_card", "setup_refined.html", '<div class="dirt2"', 0, False),
    ("setup_reminder", "setup_refined.html", '<div class="dirt2"', 1, False),
    ("setup_guide", "setup_refined.html", '<div class="scr v"', 2, True),
    ("setup_check", "setup_refined.html", '<div class="scr v"', 3, True),
    ("setup_finish", "setup_refined.html", '<div class="scr v"', 4, True),
]

# ---------------------------------------------------------------- the block, drawn in CSS

B = 72  # one block, in page pixels
STONE = (f"background-color:#7d7d7d;background-image:"
         f"linear-gradient(rgba(0,0,0,.22) 2px, transparent 2px), linear-gradient(90deg, rgba(0,0,0,.22) 2px, transparent 2px),"
         f"linear-gradient(rgba(255,255,255,.06) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.05) 1px, transparent 1px),"
         f"radial-gradient(circle at 30% 40%, rgba(255,255,255,.06), transparent 30%), radial-gradient(circle at 70% 70%, rgba(0,0,0,.08), transparent 35%);"
         f"background-size:{B}px {B // 2}px, {B}px {B}px, {B // 4}px {B // 4}px, {B // 4}px {B // 4}px, {B}px {B}px, {B * 2}px {B}px;")


def panel(x, y, w, h, inner, grid=False):
    """A joined Display Panel w x h blocks at block (x, y) of a wall: a slim bezel round the outside only, a matte black
    face, the small teal light in the bottom-right corner (owner decision D41)."""
    bez = max(3, B * 4 // 64)
    lines = ""
    if grid:
        for i in range(1, w):
            lines += f'<div style="position:absolute;left:{i * B - bez}px;top:0;bottom:0;border-left:1px dashed rgba(255,255,255,.18)"></div>'
        for j in range(1, h):
            lines += f'<div style="position:absolute;top:{j * B - bez}px;left:0;right:0;border-top:1px dashed rgba(255,255,255,.18)"></div>'
    return (f'<div style="position:absolute;left:{x * B}px;top:{y * B}px;width:{w * B}px;height:{h * B}px;background:#2A2F3A;'
            f'box-shadow:0 6px 14px rgba(0,0,0,.45)">'
            f'<div style="position:absolute;inset:{bez}px;background:#0C0E12;overflow:hidden">{lines}{inner}</div>'
            f'<div style="position:absolute;right:{bez + 4}px;bottom:{max(1, bez // 3)}px;width:{B // 6}px;height:{max(2, bez // 2)}px;background:#19D3B0"></div></div>')


def text_face(w, h):
    return (f'<div class="vt" style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;text-align:center">'
            f'<div style="color:#FFD23A;font-size:22px">WELCOME TO</div>'
            f'<div style="color:#19D3B0;font-size:40px;line-height:34px">BUILD SECURE PROTECT</div>'
            f'<div style="color:#E6EAF2;font-size:20px">Read the rules: /rules</div>'
            f'<div style="color:#9AA3B5;font-size:18px">Staff online: on the right</div></div>')


def staff_mini():
    rows = [("OWNER", "#FFD23A", [("on", "Mrgregles", "Spawn")]),
            ("ADMIN", "#B58CFF", [("on", "Ferrous", "Wild"), ("off", "Kestrel", "3 h")]),
            ("MODERATOR", "#19D3B0", [("away", "Bramble", "away"), ("on", "Quill", "Nether"), ("off", "Wren", "2 d")])]
    dot = {"on": "#8FE04A", "away": "#F2B544", "off": "#3A3F4A"}
    cols = ""
    for label, colour, people in rows:
        items = "".join(f'<div style="display:flex;gap:5px;align-items:center;white-space:nowrap"><span style="width:7px;height:7px;background:{dot[s]};display:inline-block"></span>'
                        f'<span style="color:{"#6B7280" if s == "off" else "#E6EAF2"}">{n}</span>'
                        f'<span style="color:{"#F2B544" if s == "away" else "#9AA3B5"};font-size:13px">{w}</span></div>' for s, n, w in people)
        cols += (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:3px"><div style="color:{colour};border-bottom:1px solid {colour}55">{label}</div>{items}</div>')
    return (f'<div class="vt" style="position:absolute;inset:8px 10px;font-size:15px;display:flex;flex-direction:column;gap:6px">'
            f'<div style="display:flex;justify-content:space-between"><span style="color:#19D3B0;font-size:24px">STAFF ONLINE</span><span style="color:#8FE04A;font-size:20px">4 of 6</span></div>'
            f'<div style="display:flex;gap:10px">{cols}</div></div>')


def block_world():
    wall = f'<div style="position:absolute;left:0;top:0;width:{11 * B}px;height:{4 * B}px;{STONE}">'
    wall += panel(0.5, 0.5, 4, 3, text_face(4, 3)) + panel(5.5, 0.5, 5, 3, staff_mini())
    wall += "</div>"
    scene = (f'<div style="position:relative;width:{11 * B + 60}px;height:{4 * B + 110}px;overflow:hidden;'
             f'background:linear-gradient(#7fa6cf, #b7d0e8 62%, #6d9a4a 62%, #557c39)">'
             f'<div style="position:absolute;left:40px;top:34px;transform:perspective(1400px) rotateY(12deg);transform-origin:0% 50%">{wall}</div></div>')
    page("block_world", "", scene)


def block_join():
    def example(w, h, title, sub):
        return (f'<div style="display:flex;flex-direction:column;gap:8px;align-items:center">'
                f'<div style="position:relative;width:{w * B + 2 * B}px;height:{h * B + B}px;{STONE}">{panel(1, 0.5, w, h, "", grid=True)}</div>'
                f'<div class="cap">{title}</div><div class="capm">{sub}</div></div>')
    body = (f'<div style="padding:22px 26px;background:#10151C;display:flex;gap:30px;align-items:flex-end">'
            + example(1, 1, "One block", "a thin wall panel")
            + example(2, 1, "Two side by side", "one panel, one bezel")
            + example(4, 2, "Up to 8 wide and 6 high", "nothing between the blocks; settings shared")
            + "</div>")
    page("block_join", "", body)


def network():
    def box(x, y, w, h, title, sub, colour):
        return (f'<div style="position:absolute;left:{x}px;top:{y}px;width:{w}px;height:{h}px;background:#10151C;border:1.5px solid {colour};'
                f'display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;text-align:center">'
                f'<div class="cap" style="color:{colour}">{title}</div><div class="capm">{sub}</div></div>')
    servers = [("BSP-Spawn", "Forge 47.4"), ("BSP-Stormfall", "Forge 47.4"), ("BSP-Moongrove", "Forge 47.4")]
    body = '<div style="position:relative;width:640px;height:250px;background:#0C0E12">'
    for i, (n, s) in enumerate(servers):
        body += box(20 + i * 210, 20, 180, 56, n, s, "#19D3B0")
        body += f'<div style="position:absolute;left:{110 + i * 210}px;top:76px;width:0;height:58px;border-left:1.5px dashed #9AA3B5"></div>'
    body += '<div style="position:absolute;left:110px;top:134px;width:420px;border-top:1.5px dashed #9AA3B5"></div>'
    body += box(110, 150, 200, 56, "MySQL", "rules, staff, the log, every setting, client checks", "#B58CFF")
    body += box(330, 150, 200, 56, "Redis", "changes reach every server at once", "#B58CFF")
    body += '<div class="capm" style="position:absolute;left:20px;top:222px;width:600px;text-align:center">BSP-Core\'s database and Redis are used when it is installed; without them each server keeps its own.</div>'
    body += "</div>"
    page("network", "", body)


def banner():
    body = (f'<div style="position:relative;width:600px;height:150px;background:#0C0E12;overflow:hidden">'
            f'<div class="vt" style="position:absolute;left:30px;top:34px;color:#19D3B0;font-size:44px;line-height:40px">BSP SERVER UTILITIES</div>'
            f'<div style="position:absolute;left:30px;top:80px;font:400 12px \'IBM Plex Sans\',sans-serif;color:#E6EAF2;line-height:16px">Rules · display panels · staff online · action log · AFK<br>chunk unloading · despawn times · client check · setup guide</div>'
            f'<div class="vt" style="position:absolute;left:30px;top:118px;color:#9AA3B5;font-size:13px">Forge 1.20.1 · for the Build Secure Protect network</div>'
            f'<div style="position:absolute;right:22px;top:24px;width:150px;height:100px;background:#2A2F3A">'
            f'<div style="position:absolute;inset:3px;background:#10151C" class="vt">'
            f'<div style="position:absolute;left:7px;top:4px;color:#19D3B0;font-size:15px">STAFF ONLINE</div>'
            + "".join(f'<div style="position:absolute;left:7px;top:{24 + i * 16}px;display:flex;gap:5px;align-items:center;font-size:14px">'
                      f'<span style="width:6px;height:6px;background:{d};display:inline-block"></span><span style="color:{c}">{n}</span>'
                      f'<span style="color:#9AA3B5;font-size:12px">{w}</span></div>'
                      for i, (d, c, n, w) in enumerate([("#8FE04A", "#FFD23A", "Mrgregles", "Spawn"), ("#8FE04A", "#B58CFF", "Ferrous", "Wild"),
                                                         ("#F2B544", "#19D3B0", "Bramble", "away"), ("#3A3F4A", "#6B7280", "Wren", "2 d")]))
            + f'</div><div style="position:absolute;right:6px;bottom:0;width:14px;height:2px;background:#19D3B0"></div></div></div>')
    page("banner", "", body)


def main():
    for args in SCREENS:
        from_mockup(*args)
    block_world()
    block_join()
    network()
    banner()
    names = [s[0] for s in SCREENS] + ["block_world", "block_join", "network", "banner"]
    print(f"{len(names)} pages: {', '.join(names)}")


if __name__ == "__main__":
    main()
