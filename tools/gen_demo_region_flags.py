#!/usr/bin/env python3
"""Writes the staff panel's demo flag list (staff-panel/src/main/resources/demo/region-flags.json) from BSP Region
Guard's own flag declarations (its flag/Flags.java), so `--demo` shows the flags a game server would list: each flag's
name, value kind, group, default and description, and whether plots, open areas and claims keep member and owner
lists for it. Run again after Region Guard's flags change:

    python3 tools/gen_demo_region_flags.py [path to BSP-Region Guard]
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RG = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "..", "BSP-Region Guard")
SRC = os.path.join(RG, "src/main/java/com/mrgregles/bsp_region_guard/flag/Flags.java")
OUT = os.path.join(HERE, "..", "staff-panel/src/main/resources/demo/region-flags.json")

KINDS = {"Boolean": "boolean", "Double": "double", "GameMode": "game-mode", "Int": "int", "Location": "location",
         "State": "state", "String": "string", "StringSet": "string-set"}
# Region Guard's docs/FLAGS.md, "Plot owner flags": the build family and these four keep member and owner lists
LISTED_EXTRA = {"item-drop", "item-pickup", "enderpearl", "chorus-fruit-teleport"}


def main():
    s = open(SRC, encoding="utf-8").read()
    groups = dict(re.findall(r'(\w+) = "([^"]+)"', re.search(r"public static final String (PROTECTION.*?);", s, re.S).group(1)))
    out = []
    for line in s.splitlines():
        if "public static final" not in line:
            continue
        name = re.search(r'"([a-z-]+)"', line)
        if not name:
            continue
        name = name.group(1)
        texts = re.findall(r'"((?:[^"\\]|\\.)*)"', line)
        about = texts[-1] if len(texts) > 1 else ""
        if re.search(r'\bbuild\("', line):
            kind, group, default, listed = "state", groups["PROTECTION"], "build", True
        elif re.search(r'\bplain\("', line):
            g = re.search(r"\b(" + "|".join(groups) + r")\b", line)
            d = re.search(r"State\.(ALLOW|DENY)", line)
            kind, group, default, listed = "state", groups[g.group(1)] if g else "", d.group(1).lower() if d else "", name in LISTED_EXTRA
        elif re.search(r'\bstring\("', line):
            g = re.search(r"\b(" + "|".join(groups) + r")\b", line)
            kind, group, default, listed = "string", groups[g.group(1)] if g else "", "", False
        else:
            m = re.search(r"new (\w+)Flag\(", line)
            if not m or m.group(1) not in KINDS:
                continue
            g = re.search(r"describe\((\w+)", line)
            kind, group, default, listed = KINDS[m.group(1)], groups.get(g.group(1), "") if g else "", "", name in LISTED_EXTRA
            d = re.search(r"State\.(ALLOW|DENY)", line)
            if d:
                default = d.group(1).lower()
        out.append({"name": name, "kind": kind, "group": group, "def": default, "about": about.replace('\\"', '"'), "lists": listed})
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print(f"{len(out)} flags written to {os.path.relpath(OUT)}")


if __name__ == "__main__":
    main()
