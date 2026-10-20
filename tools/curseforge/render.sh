#!/usr/bin/env bash
# Renders the CurseForge pictures: every tools/curseforge/*.html becomes docs/curseforge/images/<name>.png, drawn at 3x
# by headless Google Chrome. Each page measures itself (its title becomes WxH once the fonts are in), then is drawn at
# exactly that size.
# Usage: tools/curseforge/render.sh [name ...]   (no names: all pages)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../docs/curseforge/images"
chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
mkdir -p "$out"

pages=()
if [ $# -gt 0 ]; then
    for n in "$@"; do pages+=("$here/$n.html"); done
else
    for f in "$here"/*.html; do pages+=("$f"); done
fi

for page in "${pages[@]}"; do
    name="$(basename "$page" .html)"
    size="$("$chrome" --headless=new --disable-gpu --hide-scrollbars --window-size=1600,1200 --virtual-time-budget=8000 \
        --dump-dom "file://$page" 2>/dev/null | sed -n 's/.*<title>\([0-9]*x[0-9]*\)<\/title>.*/\1/p' | head -1)"
    [ -n "$size" ] || { echo "$name: the page did not measure itself" >&2; exit 1; }
    "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=3 \
        --window-size="${size/x/,}" --virtual-time-budget=8000 \
        --screenshot="$out/$name.png" "file://$page" >/dev/null 2>&1
    echo "$name.png ($size at 3x)"
done
