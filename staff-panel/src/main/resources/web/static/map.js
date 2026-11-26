"use strict";
// The Map tab (SU-47; D259, D389 to D395): BlueMap's page from /map/ in a frame (the same site, so this page drives it
// through window.bluemap), its own bar hidden; over it everyone online by server (a window on a computer, SU-79), the panel's bar (server,
// dimension, pins, following) and the followed player's card. Following goes with the player across servers and
// dimensions (D391), with a 2-minute trail (D392); a player who leaves is shown greyed where they were (D395 R3).
const MapView = (() => {
  const TRAIL_MS = 120000, GONE_MS = 600000;
  let root = null, frame = null, stage = null, list = null, bar = null, cardBox = null, line = null, host = null;
  let maps = null, mapsError = "", allHidden = false, cur = null, follow = null, followName = "", trail = [], gold = null, gone = null;
  let filter = "", cardPos = null, itemsOpen = false, notesOpen = false, pinBox = null, lastLive = { servers: [], players: [] };
  let listOpen = false, sheetUp = false, fab = null, trailMap = null, lastSeen = null; // on a phone (SU-48; D393, D395 R4): the list and the card as sheets
  let mq = null, mrows = null; // the list's search box, kept while typing, and its rows
  // offline players (SU-117; D661, D662, D670, R6, R7): the switch, kept in this browser; the one chosen and where their pin is
  const OFF_KEY = "bsp.mapOffline";
  let offOn = (() => { try { return localStorage.getItem(OFF_KEY) === "1"; } catch (e) { return false; } })(), offBtn = null, offSel = null, offPinMap = null;
  const show = { players: true, warps: true, spawns: true, graves: true };
  // on a computer the list floats over the map (SU-79; D549 to D556, D560, D562 to D565, D567, D568): dragged by its strip,
  // sized from its corner, folded with "–", solid while over the followed player's card; where it is kept in this browser
  const WIN_KEY = "bsp.mapWindow", WMIN = 200;
  let win = loadWin(), wft = null, wct = null, wmin = null, wrz = null, wtag = null;
  const desk = () => matchMedia("(min-width: 900px)").matches;
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  function loadWin() {
    try {
      const s = JSON.parse(localStorage.getItem(WIN_KEY) || "null"), n = (v) => typeof v === "number" && isFinite(v);
      if (s && n(s.x) && n(s.y) && n(s.w) && n(s.h)) return { x: s.x, y: s.y, w: s.w, h: s.h, folded: !!s.folded };
    } catch (e) { /* storage refused: the window starts where it always does */ }
    return { x: 10, y: 54, w: 236, h: 0, folded: false }; // h 0: a third of the page (D550)
  }
  function saveWin() { try { localStorage.setItem(WIN_KEY, JSON.stringify(win)); } catch (e) { /* kept for this page only */ } }

  // ---------------------------------------------------------------- map ids, as the panel's MapLive makes them
  const slug = (s) => (String(s || "").toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "")) || "unknown";
  function dimSlug(d) {
    d = String(d || "").toLowerCase();
    return d === "minecraft:overworld" ? "overworld" : d === "minecraft:the_nether" ? "nether" : d === "minecraft:the_end" ? "end" : slug(d);
  }
  const mapId = (server, dim) => slug(server) + "__" + dimSlug(dim); // only letters, digits and _: BlueMap's own rule
  function dimLabel(s) {
    if (s === "overworld") return "Overworld";
    if (s === "nether") return "Nether";
    if (s === "end") return "End";
    const w = s.replace(/^[a-z0-9]+_/, "").replace(/_/g, " ");
    return w.charAt(0).toUpperCase() + w.slice(1);
  }
  const dimName = (d) => dimLabel(dimSlug(d)).replace(/^(Nether|End)$/, "the $1");
  const serverOf = (id) => id.slice(0, id.indexOf("__"));
  const dimOf = (id) => id.slice(id.indexOf("__") + 2);
  const dimRank = (m) => { const d = dimOf(m); return d === "overworld" ? "0" : d === "nether" ? "1" : d === "end" ? "2" : "3" + d; };
  function serverName(sl) { // as the feed names it; a server not sending now: its id made readable
    const on = lastLive.servers.find((s) => slug(s.server) === sl);
    return on ? on.server : sl.split("_").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
  }
  const mapLabel = (id) => serverName(serverOf(id)) + "'s " + dimLabel(dimOf(id));
  const serversInOrder = () => [...new Set(maps.map(serverOf))].sort((a, b) => serverName(a).localeCompare(serverName(b)));
  // the last map looked at, kept in this browser (D576)
  const LAST_KEY = "bsp.mapLast";
  function loadLast() { try { return localStorage.getItem(LAST_KEY) || ""; } catch (e) { return ""; } }
  function saveLast(id) { try { localStorage.setItem(LAST_KEY, id); } catch (e) { /* kept for this page only */ } }
  // where to go when the map shown is hidden (D583): that server's Overworld, else the first server's first shown map
  function fallback(server) {
    if (maps.includes(server + "__overworld")) return server + "__overworld";
    const s = serversInOrder()[0];
    return maps.filter((m) => serverOf(m) === s).sort((a, b) => dimRank(a).localeCompare(dimRank(b)))[0];
  }
  const esc = (t) => String(t).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const bm = () => { try { return frame && frame.contentWindow && frame.contentWindow.bluemap; } catch (e) { return null; } };

  // a map not set up (404) is kept for the session; any other failure is read again at the next try (audit)
  async function readMaps() { // {maps, error, allHidden}; maps null when it could not be read
    const ctl = new AbortController(), t = setTimeout(() => ctl.abort(), TIMEOUT_MS);
    try {
      const r = await fetch("/map/settings.json", { credentials: "same-origin", signal: ctl.signal, cache: "no-store" });
      if (r.status === 404) return { maps: [], error: "The map is not set up on this panel yet (map.root in panel.properties; BlueMap draws it).", allHidden: false };
      if (!r.ok) return { maps: null, error: r.status >= 500 ? "The panel answered with an error (it is in the panel's console)." : "The map could not be read (" + r.status + ")." };
      const s = await r.json(), m = Array.isArray(s.maps) ? s.maps : [];
      const all = !m.length && s.bspHidden > 0; // an Owner hid every map (D585), not a map not drawn yet
      return { maps: m, error: m.length ? "" : all ? "No maps are shown." : "BlueMap has drawn no maps yet.", allHidden: all };
    } catch (e) { return { maps: null, error: ctl.signal.aborted ? "The panel did not answer in time." : "The map could not be read." }; }
    finally { clearTimeout(t); }
  }
  async function loadMaps() { // a newer read (an Owner's change meanwhile) wins over this one (audit)
    const g = ++mapsGen, r = await readMaps();
    if (g !== mapsGen) return;
    maps = r.maps; mapsError = r.error; allHidden = !!r.allHidden; mapsStale = false;
  }

  // ---------------------------------------------------------------- an Owner changed the maps shown (SU-85; D583, D584)
  // The list is read again; a map gone from it is left for that server's Overworld (or the first shown map) with the gold
  // line, or, for the Owner who hid it from this page, a word in the sheet's saved line; a map back on the list reloads
  // BlueMap's page, which knows only the maps it started with.
  // ownIds: the maps this page's sheet is saving (then the ones it changed, for 5 s): a move off one of them is this Owner's own
  // (D584), told in the saved line; any other move is another Owner's, told by the gold line (audit)
  let mapsStale = false, mapsGen = 0, ownIds = null, ownTimer = 0, retryIn = 0, retryTimer = 0;
  async function mapsChanged() {
    if (sheetBox && !sheetBusy) loadSheetQuiet(); // another Owner's change shows in an open sheet
    if (!host) { maps = null; return; } // read when the tab opens
    const g = ++mapsGen, r = await readMaps();
    if (g !== mapsGen || !host) return; // a newer read is on its way
    if (r.maps === null) { // kept as it is meanwhile, and read again soon, then less often (audit)
      mapsStale = true; clearTimeout(retryTimer);
      retryIn = Math.min(60000, retryIn ? retryIn * 2 : 5000); retryTimer = setTimeout(() => { if (host) mapsChanged(); }, retryIn);
      return;
    }
    clearTimeout(retryTimer); retryIn = 0;
    const old = maps || [], own = (id) => !!(ownIds && ownIds.has(id));
    maps = r.maps; mapsError = r.error; allHidden = !!r.allHidden; mapsStale = false;
    if (!maps.length || !frame || !stage.contains(frame)) { // to or from "No maps are shown."
      if (!maps.length && frame) { frame.remove(); frame = null; resetSwitching(); }
      if (!maps.length && cur && own(cur)) ownMoved(null);
      mount(host); return;
    }
    let moved = null;
    if (!maps.includes(cur)) { moved = cur; cur = fallback(serverOf(cur)); saveLast(cur); }
    if (maps.some((m) => !old.includes(m))) reloadFrame(); // BlueMap's page learns the maps shown again
    else if (moved) { stopTrailOnly(); switchTo(cur, null); }
    if (moved) { if (own(moved)) ownMoved(cur); else gold = { text: "That map was hidden by an Owner.", at: Date.now() }; }
    redraw();
  }
  // D584: said in the sheet's saved line, not the gold line; held while the save is under way, so it joins its own line
  let ownMove, saving = false;
  function ownMoved(to) { ownMove = { to }; if (!saving) applyOwnMove(); }
  function applyOwnMove() {
    const m = ownMove; ownMove = undefined;
    if (!m) return;
    if (sheetBox && sheetLine && !sheetLine.bad) {
      sheetLine.text += m.to ? " You were on it, so the map moved to " + mapLabel(m.to) + "." : " You were on it; no map is shown now.";
      drawSheet();
    } else if (m.to) { // the sheet closed, or its line says the save failed (it was saved after all): the gold line says it (audit)
      gold = { text: "That map was hidden by an Owner.", at: Date.now() }; if (host) redraw();
    }
  }
  function reloadFrame() {
    if (!frame) return;
    resetSwitching();
    const f = el("iframe", "mframe"); f.title = "Map"; f.src = "/map/#" + cur; f.addEventListener("load", setup);
    frame.replaceWith(f); frame = f;
  }
  function stopTrailOnly() { trail = []; trailMap = null; drawTrail(); } // a trail never joins two worlds

  // ---------------------------------------------------------------- mounting (the frame lives while the tab is open)
  async function mount(main) {
    host = main; main.classList.add("mapmain");
    if (typeof live !== "undefined" && live) lastLive = live; // the page's own feed (app.js) as it is now
    if (!root) {
      root = el("div", "mapview"); list = el("div", "mlist"); stage = el("div", "mstage");
      root.appendChild(list); root.appendChild(stage);
      wtag = el("div", "mtag hidden"); root.appendChild(wtag); // the size while the window is dragged bigger (D562)
      list.addEventListener("pointerdown", () => { list.classList.add("front"); MapRegions.back(); }, true); // last touched on top (D556, D776)
      MapRegions.attach({ root, cur: () => cur, serverName, serverOf, dimOf, mapId, bm, desk, cardBox: () => cardBox, sizeTag: () => wtag, // SU-156
        frame: () => frame, stage: () => stage, // drawing (SU-157)
        stopOthers: () => { stopQuiet(); if (offSel) { offSel = null; dropOffPin(); } }, redraw: () => { if (host) redraw(); }, redrawCard: () => { if (host && frame) drawCard(); },
        back: () => list.classList.remove("front"), closePlayers: () => { if (listOpen) { listOpen = false; drawList(); } },
        playersRect: () => (desk() && list.offsetWidth ? { x: list.offsetLeft, y: list.offsetTop, w: list.offsetWidth } : null) });
    }
    main.appendChild(root);
    if (maps === null || mapsStale) await loadMaps();
    if (host !== main) return; // another tab opened meanwhile
    if (!maps || !maps.length) {
      stage.textContent = ""; const n = el("div", "note mnote", mapsError); stage.appendChild(n);
      if (allHidden && owner()) { n.appendChild(document.createTextNode(" Choose them under ")); n.appendChild(mapsKey()); n.appendChild(document.createTextNode(".")); }
      if (!maps) {
        const again = el("button", "btn sm ghost", "Try again"); again.type = "button";
        again.addEventListener("click", () => { again.disabled = true; mount(main); });
        n.appendChild(document.createElement("br")); n.appendChild(again);
      }
      drawList(); return;
    }
    if (spot && frame && stage.contains(frame)) setTimeout(applySpot, 0); // the frame is already up: go there now
    if (!frame || !stage.contains(frame)) {
      stage.textContent = "";
      const last = loadLast(); // the map looked at last, in this browser (D576); the first time, the first overworld as before
      cur = cur && maps.includes(cur) ? cur : maps.includes(last) ? last : (maps.find((m) => m.endsWith("__overworld")) || maps[0]);
      resetSwitching(); // a switch left waiting in a removed frame never ends (audit)
      frame = el("iframe", "mframe"); frame.title = "Map"; frame.src = "/map/#" + cur;
      frame.addEventListener("load", setup);
      bar = el("div", "mbar"); cardBox = el("div", "mcardhost"); line = el("div", "mgold hidden");
      cardBox.addEventListener("pointerdown", () => list.classList.remove("front")); // the card touched: it is on top (D556)
      fab = el("button", "mfab"); fab.type = "button"; fab.title = "Players"; fab.dataset.keep = "mfab"; fab.innerHTML = ICONS.Players; // a fixed icon of this page's own
      fab.addEventListener("click", () => { listOpen = !listOpen; if (listOpen) MapRegions.closeSheet(); drawList(); });
      stage.appendChild(frame); stage.appendChild(bar); stage.appendChild(line); stage.appendChild(cardBox); stage.appendChild(fab); stage.appendChild(MapRegions.fab());
    }
    redraw();
  }
  function unmount(main) {
    if (host && host === main) { MapRegions.stopDraw(); if (offSel) offSel.placed = false; /* its pin goes with the frame: put back when the tab opens again */ spot = null; clearTimeout(spotRetry); clearTimeout(spotTimer); spotMap = null; closeSheet(); main.classList.remove("mapmain"); if (root && root.parentNode) root.remove(); host = null; frame = null; resetSwitching(); }
  }

  // BlueMap's own bar is hidden (the panel's replaces it); clicks on heads follow, clicks on pins open the panel's box
  function setup() {
    const d = frame.contentDocument; if (!d) return;
    MapRegions.frameLoaded();
    try { frame.contentWindow.addEventListener("keydown", escKey); } catch (e) { /* not the same site */ } // Esc with the map clicked (audit)
    const st = d.createElement("style");
    st.textContent = ".control-bar{display:none!important}.bm-marker-poi-label{display:none!important}"
      + ".bsp-offpin img{width:28px;height:28px;image-rendering:pixelated;filter:grayscale(1) brightness(.8);border:2px solid #aab0bc;border-radius:5px;box-shadow:0 0 0 2px #0f1115}"
      + ".bsp-offpin .bm-marker-poi-label{display:block!important;font:600 11px sans-serif;color:#e8eaf0;background:rgba(20,23,29,.85);border:1px solid #4a505c;border-radius:5px;padding:1px 6px;white-space:nowrap}"; // an offline player's pin (R7)
    d.head.appendChild(st);
    fit(); setTimeout(fit, 800); // BlueMap sizes its drawing when it starts: once the frame has its size, ask it again
    hook();
    if (spot) setTimeout(applySpot, 300); // the TOTEMS tab's place, once BlueMap has started
    if (follow) setTimeout(() => { const p = lastLive.players.find((x) => x.id === follow); if (p && mapId(p.server, p.dim) === cur) centre(p); }, 1500); // the tab opened again (audit)
  }
  function fit() { try { frame.contentWindow.dispatchEvent(new Event("resize")); } catch (e) { /* gone */ } }
  addEventListener("resize", () => { if (host) { layoutWin(); MapRegions.layout(); } if (host && frame) fit(); });
  function hook() {
    const b = bm(); if (!b || !b.mapViewer) return;
    if (!busy && cur && b.mapViewer.map && b.mapViewer.map.data.id !== cur && b.mapsMap && b.mapsMap.has(cur)) { // BlueMap shows another map: put it right
      const p = follow ? lastLive.players.find((x) => x.id === follow) : null; switchTo(cur, p); return;
    }
    const sets = b.mapViewer.markers.markerSets;
    const players = sets.get("bm-players");
    if (players) players.markers.forEach((m) => {
      if (m._bsp) return; m._bsp = true;
      m.onClick = () => { if (MapRegions.drawing()) return false; const p = lastLive.players.find((x) => x.id === m.data.playerUuid); if (p) start(p); return true; }; // a corner while drawing (audit)
    });
    ["bsp-warps", "bsp-portal-warps", "bsp-spawns", "bsp-graves"].forEach((id) => {
      const s = sets.get(id); if (!s) return;
      s.markers.forEach((m) => {
        if (m._bsp) return; m._bsp = true;
        m.onClick = (ev) => { if (MapRegions.drawing()) return false; const r = frame.getBoundingClientRect(), sr = stage.getBoundingClientRect(), pt = ev && ev.event; // BlueMap passes {event, data} (audit)
          pinBox = { set: id, data: m.data, x: (pt ? pt.clientX : r.width / 2) + r.left - sr.left, y: (pt ? pt.clientY : r.height / 2) + r.top - sr.top }; drawPinBox(); return true; };
      });
    });
    const vis = { "bm-players": show.players, "bsp-warps": show.warps, "bsp-spawns": show.spawns, "bsp-graves": show.graves };
    Object.entries(vis).forEach(([id, on]) => { const s = sets.get(id); if (s && s.visible !== on) s.visible = on; });
    MapRegions.sync(); // the region outlines of the map shown (SU-156)
  }

  // ---------------------------------------------------------------- following (D389, D391, D392, D395 R3)
  function start(p) {
    if (offSel) { offSel = null; dropOffPin(); } // following a player online: the offline one chosen goes (D662)
    MapRegions.drop(); MapRegions.stopDraw(); // and a region chosen (SU-156) or being drawn (SU-157)
    follow = p.id; followName = p.name; trail = []; gone = null; gold = null; itemsOpen = false; notesOpen = false; Players.closeKeys();
    const id = mapId(p.server, p.dim);
    if (id !== cur && maps.includes(id)) switchTo(id, p);
    else if (maps.includes(id)) centre(p);
    else gold = { text: "There is no map of " + dimName(p.dim) + " shown on " + p.server + ".", at: Date.now() }; // not followed into nothing (audit); hidden or not drawn (D572)
    redraw();
  }
  function stop() {
    sheetUp = false; itemsOpen = false; notesOpen = false;
    follow = null; trail = []; gone = null; gold = null; pinBox = null; Players.closeKeys();
    const b = bm(); if (b) { try { b.mapViewer.controlsManager.controls.stopFollowingPlayerMarker(); } catch (e) { /* BlueMap not ready */ } }
    drawTrail(); redraw();
  }
  function centre(p) {
    const b = bm(); if (!b || !b.mapViewer) return;
    const m = b.playerMarkerManager && b.playerMarkerManager.getPlayerMarker(p.id);
    const c = b.mapViewer.controlsManager;
    if (m) { try { c.controls.followPlayerMarker(m); } catch (e) { /* not ready */ } }
    else c.position.set(p.x, p.y, p.z);
  }
  // one switch at a time, the latest wanted map winning: a switch still loading must not land after a newer one
  let switching = Promise.resolve(), busy = false, gen = 0;
  function resetSwitching() { switching = Promise.resolve(); busy = false; gen++; }
  function switchTo(id, p) {
    if (spotMap && spotMap !== id) dropSpotPin(); // the TOTEMS place's pin stays on its own map
    if (offPinMap && offPinMap !== id) dropOffPin(); // so does an offline player's (the card stays)
    cur = id; pinBox = null; saveLast(id);
    const g = gen;
    switching = switching.then(async () => {
      const b = bm();
      if (g !== gen) return; // the frame went away meanwhile
      if (!b || !b.mapViewer) { if (frame) frame.src = "/map/#" + id; return; }
      if (cur !== id) return; // a newer one is queued
      busy = true;
      try {
        if (!b.mapViewer.map || b.mapViewer.map.data.id !== id) await Promise.race([b.switchMap(id, false), new Promise((r) => setTimeout(r, 15000))]); // never stuck for good (audit)
      } catch (e) { /* not a map BlueMap knows */ } finally { if (g === gen) busy = false; }
      if (g !== gen) return;
      hook(); // the pins chosen in the bar, at once on the new map (audit)
      if (p && cur === id) {
        b.mapViewer.controlsManager.position.set(p.x, p.y, p.z);
        setTimeout(() => { if (g === gen && follow === p.id && cur === id) { hook(); centre(p); } }, 1500); // not if staff moved on meanwhile (audit)
      }
      drawTrail();
    });
    return switching;
  }
  // BlueMap may replace a player's marker (they left and came back, or a poll failed): follow the new one (audit)
  function refollow(p) {
    const b = bm(); if (!b || !b.mapViewer || busy) return;
    try {
      const c = b.mapViewer.controlsManager.controls, f = c.data && c.data.followingPlayer, m = b.playerMarkerManager && b.playerMarkerManager.getPlayerMarker(p.id);
      if (f && m && f !== m.data) c.followPlayerMarker(m);
    } catch (e) { /* not ready */ }
  }
  const following = () => { const b = bm(); try { return !!(b && b.mapViewer.controlsManager.controls.data.followingPlayer); } catch (e) { return false; } };

  function onLive(l) {
    lastLive = l;
    if (!host) return;
    hook();
    if (offSel && !follow) { const back = l.players.find((x) => String(x.id).toLowerCase() === offSel.id); if (back) start(back); } // they logged on: followed live (R7)
    if (follow) {
      const p = l.players.find((x) => x.id === follow);
      if (p) {
        const id = mapId(p.server, p.dim), back = !!gone;
        lastSeen = { x: p.x, y: p.y, z: p.z };
        if (gone) { gone = null; trail = []; }
        if (id !== trailMap) { trail = []; trailMap = id; } // a trail never joins two worlds (audit)
        if (id !== cur && maps && maps.includes(id)) {
          gold = { text: p.name + " went to " + p.server + " · " + dimName(p.dim) + ": the map followed.", at: Date.now() };
          switchTo(id, p);
        } else if (id !== cur) {
          gold = { text: p.name + " is in " + dimName(p.dim) + " on " + p.server + ", which has no map shown.", at: Date.now() }; // hidden or not drawn (D572)
        } else if (back) centre(p); // back within 10 minutes: followed again (D395 R3)
        else refollow(p);
        const last = trail[trail.length - 1];
        if (!last || last.x !== p.x || last.z !== p.z) trail.push({ x: p.x, y: p.y, z: p.z, at: Date.now() });
        trail = trail.filter((t) => Date.now() - t.at < TRAIL_MS);
      } else if (!gone) {
        const t = trail[trail.length - 1] || lastSeen || { x: 0, y: 0, z: 0 }; // where they were last, even before a trail (audit)
        gone = { at: Date.now(), x: t.x, y: t.y, z: t.z };
      } else if (Date.now() - gone.at > GONE_MS) { stop(); return; }
    }
    drawTrail();
    redraw(true);
  }

  // the trail and a left player's place, as BlueMap markers in its popup set (which its marker file never clears)
  function drawTrail() {
    const b = bm(); if (!b || !b.mapViewer) return;
    const pop = b.mapViewer.markers.markerSets.get("bm-popup-set"); if (!pop) return;
    try {
      if (follow && trail.length > 1 && trailMap === cur) {
        const f = trail[0];
        pop.updateMarkerFromData("bsp-trail", { type: "line", position: { x: f.x, y: f.y + 0.2, z: f.z }, line: trail.map((t) => ({ x: t.x, y: t.y + 0.2, z: t.z })),
          lineColor: { r: 255, g: 210, b: 58, a: 0.9 }, lineWidth: 3, depthTest: false, label: "", classes: [] });
      } else if (pop.markers.get("bsp-trail")) pop.remove(pop.markers.get("bsp-trail"));
      if (follow && gone) {
        // the label and detail are put in as markup by BlueMap: escaped (audit)
        pop.updateMarkerFromData("bsp-gone", { type: "poi", position: { x: gone.x, y: gone.y + 1.8, z: gone.z }, label: esc(followName) + " left",
          detail: esc(followName) + " left", icon: "/api/head/" + follow, anchor: { x: 12, y: 12 }, classes: ["bsp-gone"] });
      } else if (pop.markers.get("bsp-gone")) pop.remove(pop.markers.get("bsp-gone"));
    } catch (e) { /* BlueMap's internals moved: the map still works without the trail */ }
  }

  // ---------------------------------------------------------------- drawing
  // Every redraw keeps the card's scroll and the field or key in use (by its data-keep key); the bar is left alone while its
  // server picker is open. tick: the live feed alone changed: while a field in the card is typed in or chosen, only where
  // they are and their stats are drawn again, never the field (audit).
  function redraw(tick) {
    if (!host) return;
    const h = hold();
    drawList();
    MapRegions.draw(); // SU-156
    if (frame) {
      const a = document.activeElement;
      if (!(a && a.tagName === "SELECT" && bar.contains(a))) drawBar(); // the server picker open: not closed under the pointer
      drawGold();
      const card = cardBox.querySelector(".mcard"), p = follow ? lastLive.players.find((x) => x.id === follow) : null;
      if (tick && card && p && !gone && typingIn(card) && card.querySelector(".mlive")) card.querySelector(".mlive").replaceWith(livePart(p));
      else drawCard();
      drawPinBox();
      solidCheck();
    }
    give(h);
  }
  // the list: its search box is made once and kept, so typing never loses it; the rows are drawn again
  function drawList() {
    if (!list) return;
    list.classList.toggle("open", listOpen);
    if (fab) fab.classList.toggle("on", listOpen);
    if (!mq) {
      mq = el("input", "search mq"); mq.type = "search"; mq.placeholder = "Find a player"; mq.dataset.keep = "mq";
      mq.addEventListener("input", () => { filter = mq.value; drawList(); });
      offBtn = el("button", "mtoggle"); offBtn.type = "button"; offBtn.dataset.keep = "moff";
      offBtn.addEventListener("click", () => { offOn = !offOn; try { localStorage.setItem(OFF_KEY, offOn ? "1" : "0"); } catch (e) { /* this page only */ } drawList(); });
      mrows = el("div", "mrows");
      makeWin();
    }
    if (mq.parentNode !== list) { list.textContent = ""; list.appendChild(wft); list.appendChild(mq); list.appendChild(offBtn); list.appendChild(mrows); list.appendChild(wrz); }
    offBtn.textContent = ""; offBtn.classList.toggle("on", offOn); offBtn.setAttribute("aria-pressed", offOn ? "true" : "false");
    offBtn.appendChild(el("span", "msw")); offBtn.appendChild(document.createTextNode("Show offline players"));
    if (mq.value !== filter) mq.value = filter;
    wct.textContent = String(lastLive.players.length); // everyone online, kept up to date (D568)
    const sy = list.scrollTop, ry = mrows.scrollTop;
    mrows.textContent = "";
    const f = filter.trim().toLowerCase();
    lastLive.servers.forEach((s) => {
      const ps = lastLive.players.filter((p) => p.server === s.server && (!f || p.name.toLowerCase().includes(f)));
      if (!ps.length) return;
      const g = el("div", "mgrp"); g.appendChild(el("span", null, s.server)); g.appendChild(el("span", null, String(ps.length))); mrows.appendChild(g);
      ps.sort((a, b) => a.name.localeCompare(b.name)).forEach((p) => {
        const r = el("button", "mrow" + (p.id === follow ? " on" : "")); r.type = "button"; r.dataset.keep = "mrow:" + p.id;
        r.appendChild(Players.face(p, 18)); r.appendChild(el("span", "nm", p.name));
        const d = dimSlug(p.dim); r.appendChild(el("span", "d", d === "overworld" ? "OW" : d === "nether" ? "NETHER" : d === "end" ? "END" : dimLabel(d).toUpperCase()));
        r.addEventListener("click", () => { listOpen = false; sheetUp = false; start(p); });
        mrows.appendChild(r);
      });
    });
    if (!lastLive.players.length) mrows.appendChild(el("div", "note", "Nobody is online."));
    if (offOn) offRows(f);
    list.scrollTop = sy; mrows.scrollTop = ry;
    layoutWin();
  }

  // ---------------------------------------------------------------- offline players on the map (SU-117; R6, R7)
  // the OFFLINE rows under the servers: the window's search finds them too, newest seen first, then MORE
  function offRows(f) {
    Offline.ensure(); Offline.search(f); // the same list as the Players tab's, with this window's search
    const off = Offline.state(), rows = Offline.rows();
    const h = el("div", "mgrp moffh"); h.appendChild(el("span", null, "OFFLINE"));
    h.appendChild(el("span", null, !off.read ? "…" : off.total.toLocaleString("en-GB"))); mrows.appendChild(h);
    if (off.problem) mrows.appendChild(el("div", "note moffnote", off.problem));
    if (off.error) mrows.appendChild(el("div", "err moffnote", off.error));
    if (off.read && !rows.length && !off.error) mrows.appendChild(el("div", "note moffnote", off.listQ ? "Nobody offline matches “" + off.listQ + "”." : "Nobody else has played."));
    rows.forEach((p) => {
      const r = el("button", "mrow moff" + (offSel && offSel.id === p.id ? " on" : "")); r.type = "button"; r.dataset.keep = "moff:" + p.id;
      const face = Players.face(p, 18); face.classList.add("offface"); r.appendChild(face); r.appendChild(el("span", "nm", p.name));
      r.appendChild(el("span", "t", p.state === "ok" ? Offline.agoShort(p.place.at) : "—"));
      const d = p.state === "ok" ? dimSlug(p.place.dim) : "";
      r.appendChild(el("span", "d", !d ? "" : d === "overworld" ? "OW" : d === "nether" ? "NETHER" : d === "end" ? "END" : dimLabel(d).toUpperCase()));
      r.addEventListener("click", () => { listOpen = false; sheetUp = false; chooseOff(p); });
      mrows.appendChild(r);
    });
    if (off.read && rows.length < off.total && rows.length) {
      const m = el("button", "moffmore", off.loading ? "Reading…" : "MORE"); m.type = "button"; m.dataset.keep = "moffmore"; m.disabled = off.loading;
      m.addEventListener("click", () => Offline.more()); mrows.appendChild(m);
    }
  }
  // an offline player chosen (in the window, or MAP › on their card): nobody followed, their map at the block with a grey
  // pin until another player is chosen or the card is closed (D662); a world without a map shown leaves the map as it is
  function chooseOff(p) {
    stopQuiet(); dropOffPin(); MapRegions.drop(); MapRegions.stopDraw();
    offSel = { id: String(p.id).toLowerCase(), name: p.name, placed: false, hidden: false };
    itemsOpen = false; notesOpen = false; pinBox = null; Players.closeKeys();
    Offline.remember(p);
    placeOff(p.state ? p : Offline.card(p));
    redraw();
  }
  function placeOff(c) {
    if (!offSel || offSel.placed || c.state !== "ok" || !c.place) return;
    const pl = c.place, id = mapId(pl.server, pl.dim);
    offSel.placed = true;
    if (!maps || !maps.includes(id)) { offSel.hidden = true; return; } // the card says so; the map stays where it was
    // waited for as long as the card is open (a slow map never loses it: audit of SU-117); dropped when another is chosen
    spot = { id, x: Number(pl.x) + 0.5, y: Number(pl.y), z: Number(pl.z) + 0.5, label: (c.name || offSel.name) + " · logged off", until: Date.now() + 600000,
      off: { id: offSel.id, skin: !!c.skin } };
    cur = id; saveLast(id);
    if (frame && stage && stage.contains(frame)) applySpot();
  }
  function dropOffPin() {
    offPinMap = null;
    const b = bm();
    try { const pop = b && b.mapViewer.markers.markerSets.get("bm-popup-set"), m = pop && pop.markers.get("bsp-offpin"); if (m) pop.remove(m); } catch (e) { /* gone */ }
  }
  function clearOff() { offSel = null; dropOffPin(); sheetUp = false; itemsOpen = false; notesOpen = false; redraw(); }
  /** MAP › on an offline player's card (the Players tab): their place on this tab; false when its map is not shown. */
  function showOffline(c) {
    if (!c || c.state !== "ok" || !c.place || !maps || !maps.includes(mapId(c.place.server, c.place.dim))) return false;
    chooseOff(c);
    return true;
  }
  // the card of the offline player chosen (R7): as following's, greyed, with where they logged off
  function drawOffCard() {
    const c = Offline.card(offSel), name = c.name || offSel.name, who0 = { id: offSel.id, name, skin: !!c.skin };
    if (!offSel.placed) placeOff(c); // their record came after the click
    const box = el("div", "mcard moffcard" + (sheetUp ? " up" : "")); cardBox.appendChild(box);
    box.dataset.scroll = "mcard";
    const grab = el("button", "mgrab"); grab.type = "button"; grab.title = sheetUp ? "Down" : "Up for everything"; grab.dataset.keep = "mgrab";
    grab.addEventListener("click", () => { sheetUp = !sheetUp; redraw(); }); box.appendChild(grab);
    if (cardPos) { box.style.left = cardPos[0] + "px"; box.style.top = cardPos[1] + "px"; box.style.right = "auto"; box.style.bottom = "auto"; }
    const top = el("div", "dtop"); const grip = el("div", "grip"); grip.title = "Drag"; top.appendChild(grip);
    const x = el("button", "close", "✕"); x.type = "button"; x.title = "Close"; x.dataset.keep = "mclose"; x.addEventListener("click", clearOff);
    const who = el("div", "grow"); who.appendChild(el("div", "dname offn", name));
    const sub = el("div", "sub dsub offsub"); sub.appendChild(el("span", "chip offc", "OFFLINE"));
    const pl = c.state === "ok" ? c.place : null;
    sub.appendChild(document.createTextNode(" " + (c.state === "loading" ? "reading…" : pl ? (pl.kind === "logout" ? "logged off " : "last seen ") + Offline.when(pl.at, true) : Offline.lastSeen(c))));
    who.appendChild(sub);
    const face = Players.face(who0, 36); face.classList.add("offface");
    top.appendChild(face); top.appendChild(who); top.appendChild(x); box.appendChild(top);
    drag(grip, box);
    if (pl) box.appendChild(el("div", "loc mono", pl.server + " · " + dimLabel(dimSlug(pl.dim)) + " · " + pl.x + ", " + pl.y + ", " + pl.z));
    if (c.problem) box.appendChild(el("div", "offwarn", c.problem));
    if (pl && pl.kind !== "logout") box.appendChild(el("div", "offwarn", "The server did not record a logout: this is its last 5-minute record."));
    if (pl && offSel.hidden) box.appendChild(el("div", "offwarn", "That world's map is not shown, so the map stays where it was."));
    if (c.state === "none") box.appendChild(el("div", "mu offmsg", "Where they logged off is not recorded yet. It is kept from their next logout."));
    if (c.state === "bad") box.appendChild(el("div", "mu offmsg", "Last place not known: the record could not be checked."));
    const redrawCard = () => { if (host) redraw(); };
    const more = el("div", "mmore"); // on a phone, only once the sheet is pulled up
    if (itemsOpen) more.appendChild(Items.section(who0, { live: false, redraw: redrawCard }));
    else { const h = el("div", "sect"); h.appendChild(el("span", null, "HOTBAR")); h.appendChild(el("span", "mu", "from their last save")); more.appendChild(h); more.appendChild(Items.hotbar({ id: offSel.id, name }, { redraw: redrawCard })); }
    box.appendChild(more);
    const keys = el("div", "keys mkeys");
    const it = el("button", "key" + (itemsOpen ? " on" : ""), itemsOpen ? "Hotbar" : "Items"); it.type = "button"; it.dataset.keep = "m:items"; it.addEventListener("click", () => { itemsOpen = !itemsOpen; if (itemsOpen) sheetUp = true; redraw(); });
    const nt = el("button", "key" + (notesOpen ? " on" : ""), "Notes"); nt.type = "button"; nt.dataset.keep = "m:notes"; nt.addEventListener("click", () => { notesOpen = !notesOpen; if (notesOpen) sheetUp = true; redraw(); });
    const oc = el("button", "key tlk", "Open card"); oc.type = "button"; oc.dataset.keep = "m:card"; oc.addEventListener("click", () => openOffline({ id: offSel.id, name }));
    keys.appendChild(it); keys.appendChild(nt); keys.appendChild(oc); box.appendChild(keys);
    if (notesOpen) { const n = Notes.section(who0); n.classList.add("mmore"); box.appendChild(n); }
  }

  // ---------------------------------------------------------------- the floating window (a computer only)
  function makeWin() {
    wft = el("div", "mft"); wft.title = "Drag to move";
    wft.appendChild(el("div", "gp")); wft.appendChild(el("span", "tt", "PLAYERS")); wct = el("span", "ct"); wft.appendChild(wct);
    wmin = el("button", "mmin"); wmin.type = "button"; wmin.dataset.keep = "mmin";
    wmin.addEventListener("click", () => { win.folded = !win.folded; saveWin(); layoutWin(); });
    wft.appendChild(wmin);
    wrz = el("div", "mrz"); wrz.title = "Drag to size";
    moveWin(); sizeWin();
  }
  // where it is drawn: as kept, but inside the map and within its limits (D551, D567); what was chosen is kept for later
  function place() {
    const W = root.clientWidth, H = root.clientHeight;
    const hMin = Math.round(H / 5), hMax = Math.max(hMin, Math.round(H * 2 / 3)), wMax = Math.max(WMIN, Math.round(W * 2 / 3));
    const h = clamp(win.h || Math.round(H / 3), hMin, hMax), w = clamp(win.w, WMIN, wMax);
    const tall = win.folded ? (wft && wft.offsetHeight ? wft.offsetHeight + 2 : 36) : h;
    return { x: clamp(win.x, 0, Math.max(0, W - w)), y: clamp(win.y, 0, Math.max(0, H - tall)), w, h, W, H, hMin, hMax, wMax };
  }
  function layoutWin() {
    if (!list || !root || !wft) return;
    list.classList.toggle("folded", !!win.folded);
    wmin.textContent = win.folded ? "+" : "–"; wmin.title = win.folded ? "Open" : "Fold";
    if (!desk() || !root.clientHeight) { ["left", "top", "width", "height"].forEach((k) => { list.style[k] = ""; }); return; } // a phone: the sheet
    const g = place();
    list.style.left = g.x + "px"; list.style.top = g.y + "px"; list.style.width = g.w + "px"; list.style.height = win.folded ? "" : g.h + "px";
    solidCheck();
  }
  // solid while any part of it covers the followed player's card (D565)
  function solidCheck() {
    if (!list) return;
    const c = cardBox && cardBox.querySelector(".mcard");
    let on = false;
    if (c && desk()) { const a = list.getBoundingClientRect(), b = c.getBoundingClientRect(); on = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom; }
    list.classList.toggle("solid", on);
    MapRegions.solid();
  }
  // dragged by its strip; a click on a folded strip opens it (D553, D554, D568)
  function moveWin() {
    wft.addEventListener("pointerdown", (e) => {
      if (!desk() || e.button !== 0 || e.target.closest("button")) return;
      const g = place(), sx = e.clientX, sy = e.clientY; let moved = false;
      wft.setPointerCapture(e.pointerId); document.body.classList.add("mapdrag");
      const move = (m) => {
        const dx = m.clientX - sx, dy = m.clientY - sy;
        if (!moved && Math.abs(dx) + Math.abs(dy) < 4) return;
        moved = true; win.x = g.x + dx; win.y = g.y + dy;
        const p = place(); win.x = p.x; win.y = p.y; layoutWin();
      };
      let done = false;
      const up = () => {
        if (done) return; done = true; document.body.classList.remove("mapdrag");
        ["pointermove", "pointerup", "pointercancel", "lostpointercapture"].forEach((t) => wft.removeEventListener(t, t === "pointermove" ? move : up));
        if (moved) saveWin(); else if (win.folded) { win.folded = false; saveWin(); layoutWin(); }
      };
      wft.addEventListener("pointermove", move); ["pointerup", "pointercancel", "lostpointercapture"].forEach((t) => wft.addEventListener(t, up));
    });
  }
  // sized from its corner, a fifth to two-thirds of the page high (D551), never past the map's edge; the size shown meanwhile (D562)
  function sizeWin() {
    wrz.addEventListener("pointerdown", (e) => {
      if (!desk() || e.button !== 0) return;
      e.stopPropagation();
      const g = place(), sx = e.clientX, sy = e.clientY;
      wrz.setPointerCapture(e.pointerId); document.body.classList.add("mapdrag");
      const move = (m) => {
        win.x = g.x; win.y = g.y;
        win.w = clamp(g.w + m.clientX - sx, WMIN, Math.max(WMIN, Math.min(g.wMax, g.W - g.x)));
        win.h = clamp(g.h + m.clientY - sy, g.hMin, Math.max(g.hMin, Math.min(g.hMax, g.H - g.y)));
        layoutWin();
        wtag.textContent = Math.round(win.w) + " × " + Math.round(win.h);
        wtag.style.left = Math.max(0, Math.min(g.W - 90, g.x + win.w - 40)) + "px"; wtag.style.top = Math.min(g.H - 26, g.y + win.h + 8) + "px";
        wtag.classList.remove("hidden");
      };
      let done = false;
      const up = () => {
        if (done) return; done = true; document.body.classList.remove("mapdrag");
        ["pointermove", "pointerup", "pointercancel", "lostpointercapture"].forEach((t) => wrz.removeEventListener(t, t === "pointermove" ? move : up));
        wtag.classList.add("hidden"); saveWin();
      };
      wrz.addEventListener("pointermove", move); ["pointerup", "pointercancel", "lostpointercapture"].forEach((t) => wrz.addEventListener(t, up));
    });
  }
  function drawBar() {
    bar.textContent = "";
    const servers = serversInOrder();
    const sel = el("select", "mc msel"); sel.dataset.keep = "msel"; servers.forEach((sl) => { const o = el("option", null, serverName(sl)); o.value = sl; o.selected = sl === serverOf(cur); sel.appendChild(o); });
    // the picker let go once a server is chosen (or left), so the bar is drawn for that server at once: while it kept the focus
    // the bar was never drawn again, and the dimension keys left over from the server before switched back to it (bug, SU-82)
    // the same dimension on the server chosen when it shows one, else its Overworld (D577)
    sel.addEventListener("change", () => { stopQuiet(); const same = sel.value + "__" + dimOf(cur); const id = maps.includes(same) ? same : maps.find((m) => m === sel.value + "__overworld") || maps.filter((m) => serverOf(m) === sel.value).sort((a, b) => dimRank(a).localeCompare(dimRank(b)))[0]; if (id) switchTo(id, null); sel.blur(); redraw(); });
    sel.addEventListener("blur", () => setTimeout(() => { if (host && frame) redraw(); }, 0));
    bar.appendChild(sel);
    const seg = el("div", "mseg");
    maps.filter((m) => serverOf(m) === serverOf(cur)).sort((a, b) => dimRank(a).localeCompare(dimRank(b))).forEach((m) => {
      const b = el("button", m === cur ? "on" : "", dimLabel(m.slice(m.indexOf("__") + 2))); b.type = "button"; b.dataset.keep = "mdim:" + m;
      const dim = m.slice(m.indexOf("__") + 2);
      b.addEventListener("click", () => { // the dimension of the server shown now, never of the server the key was drawn for (SU-82)
        const id = serverOf(cur) + "__" + dim;
        if (id === cur) return;
        if (!maps.includes(id)) { redraw(); return; }
        stopQuiet(); switchTo(id, null); redraw();
      });
      seg.appendChild(b);
    });
    bar.appendChild(seg);
    const pins = el("div", "mc mpins");
    [["players", "Players"], ["warps", "Warps"], ["spawns", "Spawns"], ["graves", "Graves"]].forEach(([k, t]) => {
      const l = el("label", "mtg"); const c = el("input"); c.type = "checkbox"; c.checked = show[k]; c.dataset.keep = "mpin:" + k;
      if (k === "graves") { c.disabled = true; l.title = "Graves show once BSP-InventorySync lists every grave (request SU-IS4)."; }
      c.addEventListener("change", () => { show[k] = c.checked; hook(); });
      l.appendChild(c); l.appendChild(document.createTextNode(t)); pins.appendChild(l);
    });
    bar.appendChild(pins);
    bar.appendChild(MapRegions.barKey()); // every region outline on or off (D768)
    if (owner()) bar.appendChild(mapsKey());
    if (follow) {
      const f = el("div", "mc mfol"); f.appendChild(document.createTextNode("Following ")); f.appendChild(el("b", null, followName));
      if (!gone && !following()) { const c = el("button", "mcentre", "centre"); c.type = "button"; c.title = "Back to them"; c.dataset.keep = "mcentre"; c.addEventListener("click", () => { const p = lastLive.players.find((x) => x.id === follow); if (p) centre(p); redraw(); }); f.appendChild(c); }
      const x = el("button", "mx", "✕"); x.type = "button"; x.title = "Stop following"; x.dataset.keep = "mx"; x.addEventListener("click", stop); f.appendChild(x);
      bar.appendChild(f);
    }
  }
  function stopQuiet() { if (follow) { follow = null; trail = []; gone = null; gold = null; drawTrail(); const b = bm(); try { b.mapViewer.controlsManager.controls.stopFollowingPlayerMarker(); } catch (e) { /* not ready */ } } }
  function drawGold() {
    const g = gone ? { text: followName + " left the network at " + new Date(gone.at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) + ". Shown where they were last; followed again when they come back." }
      : gold && Date.now() - gold.at < 20000 ? gold : null;
    line.textContent = g ? g.text : ""; line.classList.toggle("hidden", !g);
    if (g && bar) line.style.top = Math.max(52, bar.offsetTop + bar.offsetHeight + 6) + "px"; // under a bar wrapped beside the open sheet (audit)
  }
  function drawCard() {
    cardBox.textContent = "";
    stage.classList.toggle("fol", !!follow || !!offSel || MapRegions.active()); // on a phone the players button moves up out of the sheet's way
    if (!follow) { if (offSel) drawOffCard(); else MapRegions.card(cardBox); return; } // a region chosen (SU-156)
    const p = lastLive.players.find((x) => x.id === follow);
    const box = el("div", "mcard" + (gone ? " left" : "") + (sheetUp ? " up" : "")); cardBox.appendChild(box); // "left", not "gone": the Staff tab styles that (audit)
    box.dataset.scroll = "mcard"; // its scroll kept through every redraw (audit)
    const grab = el("button", "mgrab"); grab.type = "button"; grab.title = sheetUp ? "Down" : "Up for everything"; grab.dataset.keep = "mgrab";
    grab.addEventListener("click", () => { sheetUp = !sheetUp; redraw(); }); box.appendChild(grab); // a phone's sheet: small, then pulled up (D393)
    if (cardPos) { box.style.left = cardPos[0] + "px"; box.style.top = cardPos[1] + "px"; box.style.right = "auto"; box.style.bottom = "auto"; } // where it was dragged
    const top = el("div", "dtop"); const grip = el("div", "grip"); grip.title = "Drag"; top.appendChild(grip);
    const x = el("button", "close", "✕"); x.type = "button"; x.title = "Stop following"; x.dataset.keep = "mclose"; x.addEventListener("click", stop);
    const who = el("div", "grow"); who.appendChild(el("div", "dname", followName));
    if (p) { const sub = el("div", "sub"); if (p.team) { const sq = el("span", "sq", "■"); sq.style.color = p.team.colour; sub.appendChild(sq); sub.appendChild(document.createTextNode(" " + p.team.name + " · ")); } sub.appendChild(document.createTextNode(p.ping + " ms")); who.appendChild(sub); who.appendChild(Tags.chips(p.id, 3)); }
    top.appendChild(Players.face(p || { id: follow, name: followName }, 36)); top.appendChild(who); top.appendChild(x); box.appendChild(top);
    drag(grip, box);
    if (!p) { box.appendChild(el("div", "note", "Not online now.")); return; }
    box.appendChild(livePart(p));
    const redrawCard = () => { if (host) redraw(); }; // its scroll and focus kept (audit)
    const more = el("div", "mmore"); // on a phone, only once the sheet is pulled up
    if (itemsOpen) more.appendChild(Items.section({ id: p.id, name: p.name, skin: p.skin }, { live: true, redraw: redrawCard })); // D395 R2
    else { const h = el("div", "sect"); h.appendChild(el("span", null, "HOTBAR")); more.appendChild(h); more.appendChild(Items.hotbar({ id: p.id, name: p.name }, { redraw: redrawCard })); }
    box.appendChild(more);
    const keys = el("div", "keys mkeys");
    const it = el("button", "key" + (itemsOpen ? " on" : ""), itemsOpen ? "Hotbar" : "Items"); it.type = "button"; it.dataset.keep = "m:items"; it.addEventListener("click", () => { itemsOpen = !itemsOpen; if (itemsOpen) sheetUp = true; redraw(); });
    const nt = el("button", "key" + (notesOpen ? " on" : ""), "Notes"); nt.type = "button"; nt.dataset.keep = "m:notes"; nt.addEventListener("click", () => { notesOpen = !notesOpen; if (notesOpen) sheetUp = true; redraw(); });
    const sp = el("button", "key", "Stop following"); sp.type = "button"; sp.dataset.keep = "m:stop"; sp.addEventListener("click", stop);
    keys.appendChild(it); keys.appendChild(nt); keys.appendChild(sp); box.appendChild(keys);
    const acts = Players.actions(p, lastLive); acts.classList.add("mmore"); box.appendChild(acts); // Teleport, Graves, Kick, Ban, as in the player's card
    if (notesOpen) { const n = Notes.section(p); n.classList.add("mmore"); box.appendChild(n); }
  }
  // where they are (underground marked) and their stats: what the live feed changes
  function livePart(p) {
    const w = el("div", "mlive");
    const loc = el("div", "loc mono"); loc.appendChild(document.createTextNode(p.server + " · " + dimName(p.dim) + " · " + Math.round(p.x) + ", " + Math.round(p.y) + ", " + Math.round(p.z)));
    if (dimSlug(p.dim) === "overworld" && p.y < 50) loc.appendChild(el("span", "ug", "underground"));
    w.appendChild(loc); w.appendChild(Players.stats(p));
    return w;
  }
  function drag(grip, box) {
    grip.addEventListener("pointerdown", (e) => {
      const r = box.getBoundingClientRect(), s = stage.getBoundingClientRect(), ox = e.clientX - r.left, oy = e.clientY - r.top;
      grip.setPointerCapture(e.pointerId);
      const move = (m) => { cardPos = [Math.max(0, Math.min(s.width - r.width, m.clientX - s.left - ox)), Math.max(0, Math.min(s.height - 60, m.clientY - s.top - oy))];
        box.style.left = cardPos[0] + "px"; box.style.top = cardPos[1] + "px"; box.style.right = "auto"; box.style.bottom = "auto"; solidCheck(); };
      const up = () => { grip.removeEventListener("pointermove", move); grip.removeEventListener("pointerup", up); };
      grip.addEventListener("pointermove", move); grip.addEventListener("pointerup", up);
    });
  }
  // a tapped pin (D395 R1): its name and server; for a warp or spawn, the player followed can be moved there (one tap, D348)
  // our own escaped labels (MapLive), read back as words; DOMParser's document is inert: nothing in it loads or runs (audit of SU-117)
  const text = (html) => new DOMParser().parseFromString(String(html || ""), "text/html").documentElement.textContent;
  function drawPinBox() {
    const old = stage && stage.querySelector(".mpin"); if (old) old.remove();
    if (!pinBox || !stage) return;
    const d = pinBox.data, b = el("div", "mpin popup"), label = String(d.label || ""), lines = String(d.detail || "").split("<br>"); // the label as written (MapLive)
    b.style.left = Math.max(8, Math.min(stage.clientWidth - 250, pinBox.x - 120)) + "px"; b.style.top = Math.max(8, Math.min(stage.clientHeight - 140, pinBox.y + 14)) + "px";
    const t = el("div", "pt"); t.appendChild(el("b", null, label));
    const x = el("button", "close", "✕"); x.type = "button"; x.addEventListener("click", () => { pinBox = null; drawPinBox(); });
    t.appendChild(x); b.appendChild(t);
    if (lines[1]) b.appendChild(el("div", "sub", text(lines[1])));
    const p = follow && !gone ? lastLive.players.find((y) => y.id === follow) : null;
    if (pinBox.set !== "bsp-graves") {
      if (p) {
        const box = pinBox, go = el("button", "btn sm", "Move " + p.name + " here"); go.type = "button"; go.disabled = !!box.busy;
        go.addEventListener("click", async () => {
          if (box.busy) return; box.busy = true; go.disabled = true; // one move, though the box is drawn again meanwhile (audit)
          const body = pinBox.set === "bsp-spawns" ? { kind: "spawn", server: label.replace(/ spawn$/, "") } : { kind: "warp", warp: label };
          const r = await call("/api/tp", Object.assign({ player: p.id }, body), true);
          box.busy = false; box.result = r; if (pinBox === box) drawPinBox(); // the answer on its own box only (audit)
        });
        b.appendChild(go);
      } else b.appendChild(el("div", "note", "Follow a player to move them here."));
    }
    if (pinBox.result) b.appendChild(el("div", pinBox.result.ok ? "okm" : "err", pinBox.result.message));
    stage.appendChild(b);
  }


  // ---------------------------------------------------------------- the Owner's MAPS sheet (SU-84; D570 to D586, look A of set AF)
  // Every map BlueMap has drawn, by server in the picker's order, a chip each: lit shown, struck through hidden. A tap saves
  // at once (D579); the line under the list names the change, with UNDO for that one change until the next or the sheet
  // closes (D582); a save that fails leaves the chip as it was and turns the line red. Seen only by Owners.
  let sheetBox = null, sheetData = null, sheetErr = "", sheetBusy = null, sheetLine = null;
  const owner = () => typeof me !== "undefined" && !!me && me.rank === "OWNER";
  function mapsKey() {
    const k = el("button", "mc mkey" + (sheetBox ? " on" : "")); k.type = "button"; k.dataset.keep = "mkey";
    k.title = sheetBox ? "Close the maps shown to staff" : "Choose which maps staff see (Owners)";
    k.innerHTML = ICONS.Map; k.appendChild(document.createTextNode("MAPS")); // a fixed icon of this page's own
    k.addEventListener("click", () => { if (sheetBox) closeSheet(); else openSheet(); });
    return k;
  }
  function openSheet() {
    if (!owner() || !root) return;
    sheetBox = el("div", "msheet"); sheetBox.tabIndex = -1; sheetBox.setAttribute("role", "dialog"); sheetBox.setAttribute("aria-label", "Maps shown to staff");
    root.appendChild(sheetBox); root.classList.add("sheeton");
    sheetData = null; sheetErr = ""; sheetLine = null; drawSheet(); redrawKeys();
    loadSheet();
    const c = sheetBox.querySelector(".close"); if (c) c.focus();
  }
  function closeSheet() {
    if (!sheetBox) return;
    sheetBox.remove(); sheetBox = null; sheetLine = null; sheetBusy = null;
    if (ownMove && !saving) applyOwnMove(); // a move not yet told: the gold line
    if (root) root.classList.remove("sheeton");
    redrawKeys();
  }
  function redrawKeys() { // the bar's key lit or not, and the no-map message's key
    document.querySelectorAll(".mkey").forEach((k) => { k.classList.toggle("on", !!sheetBox); k.title = sheetBox ? "Close the maps shown to staff" : "Choose which maps staff see (Owners)"; });
  }
  async function loadSheet() {
    const box = sheetBox;
    const r = await call("/api/maps");
    if (box !== sheetBox) return; // closed meanwhile
    if (r.ok) { sheetData = r; sheetErr = ""; } else { sheetErr = r.message || "The maps could not be read."; }
    drawSheet();
  }
  function escKey(e) {
    if (e.key !== "Escape" || !sheetBox) return;
    const t = e.target; // Esc in the players search or the server picker is theirs (audit)
    if (t && t.closest && /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) && !sheetBox.contains(t)) return;
    e.preventDefault(); closeSheet(); keyFocus();
  }
  function keyFocus() { const k = document.querySelector(".mkey"); if (k) k.focus(); }
  addEventListener("keydown", escKey);
  function when(at) {
    const d = new Date(at), now = new Date(), hm = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    return d.toDateString() === now.toDateString() ? hm : d.toLocaleDateString([], { day: "numeric", month: "short" }) + " " + hm;
  }
  function drawSheet() {
    if (!sheetBox) return;
    const old = sheetBox.querySelector(".mslist"), top = old ? old.scrollTop : 0, a = document.activeElement, keep = a && sheetBox.contains(a) ? a.dataset.keep : null;
    sheetBox.textContent = "";
    const head = el("div", "mshead"); head.appendChild(el("span", "t", "MAPS SHOWN TO STAFF")); head.appendChild(el("span", "own", "Owner"));
    const x = el("button", "close", "✕"); x.type = "button"; x.title = "Close (Esc)"; x.dataset.keep = "msclose"; x.addEventListener("click", () => { closeSheet(); keyFocus(); }); head.appendChild(x);
    sheetBox.appendChild(head);
    sheetBox.appendChild(el("div", "mshint", "Tap a map to hide or show it on the Map tab for every staff member. Saved as you tap."));
    if (sheetErr) {
      const n = el("div", "note", sheetErr); const again = el("button", "btn sm ghost", "Try again"); again.type = "button";
      again.addEventListener("click", () => { sheetErr = ""; drawSheet(); loadSheet(); });
      n.appendChild(document.createElement("br")); n.appendChild(again); sheetBox.appendChild(n); return;
    }
    if (!sheetData) { sheetBox.appendChild(el("div", "note", "Reading the maps…")); return; }
    const list = el("div", "mslist" + (sheetBusy ? " busy" : "")); list.setAttribute("aria-busy", sheetBusy ? "true" : "false"); // taps wait while one is saved
    if (!sheetData.servers.length) list.appendChild(el("div", "note", "BlueMap has drawn no maps yet."));
    sheetData.servers.forEach((s) => {
      const on = s.maps.filter((m) => m.shown), row = el("div", "mss" + (on.length ? "" : " msoff")); // not "gone": the Staff tab styles that
      const sn = el("div", "msn"); sn.appendChild(el("span", "nm", s.name)); sn.appendChild(el("span", "c", on.length + " of " + s.maps.length));
      [["ALL", true], ["NONE", false]].forEach(([w, show]) => {
        const b = el("button", "lk", w); b.type = "button"; b.dataset.keep = "mslk:" + s.id + ":" + w;
        const ids = s.maps.filter((m) => m.shown !== show).map((m) => m.id);
        b.disabled = !ids.length; b.title = (show ? "Show every map of " : "Hide every map of ") + s.name;
        b.addEventListener("click", () => setMaps(ids, show, show ? "all" : "none"));
        sn.appendChild(b);
      });
      row.appendChild(sn);
      const chips = el("div", "mchips");
      s.maps.forEach((m) => {
        const b = el("button", "dc " + (m.shown ? "on" : "off") + (sheetBusy && sheetBusy.has(m.id) ? " hot" : "")); b.type = "button"; b.dataset.keep = "msmap:" + m.id;
        b.setAttribute("aria-pressed", m.shown ? "true" : "false"); b.title = (m.shown ? "Shown: tap to hide " : "Hidden: tap to show ") + s.name + "'s " + m.label;
        b.appendChild(el("i")); b.appendChild(document.createTextNode(m.label));
        b.addEventListener("click", () => setMaps([m.id], !m.shown, "tap"));
        chips.appendChild(b);
      });
      row.appendChild(chips); list.appendChild(row);
    });
    sheetBox.appendChild(list); list.scrollTop = top;
    if (sheetLine) {
      const l = el("div", "msaved" + (sheetLine.bad ? " bad" : ""), sheetLine.text); l.setAttribute("role", "status");
      if (sheetLine.undo) { const u = el("button", "ud", "UNDO"); u.type = "button"; u.dataset.keep = "msundo"; u.addEventListener("click", () => setMaps(sheetLine.undo.maps, sheetLine.undo.show, "undo")); l.appendChild(u); }
      sheetBox.appendChild(l);
    }
    const last = sheetData.last;
    sheetBox.appendChild(el("div", "msfoot", (last ? "Last change: " + last.name + ", " + when(last.at) : "No change yet") + " · BSP-Map still draws hidden maps."));
    if (keep) { const f = sheetBox.querySelector('[data-keep="' + keep + '"]'); if (f && !f.disabled) f.focus(); else sheetBox.focus(); } // a key gone (UNDO, an ALL with nothing left): the sheet keeps the focus
  }
  async function setMaps(ids, show, how) {
    if (saving || !ids.length || !sheetData) return; // one save at a time from this page, the sheet closed and opened again too (audit)
    const box = sheetBox;
    saving = true; sheetBusy = new Set(ids); ownMove = undefined; drawSheet();
    clearTimeout(ownTimer); ownIds = new Set(ids); // the "maps" event may come before the answer
    const r = await call("/api/maps/set", { maps: ids, show, how }, true);
    saving = false;
    ownIds = r.ok ? new Set(r.changed || []) : null; ownTimer = setTimeout(() => { ownIds = null; }, 5000);
    if (box !== sheetBox) { applyOwnMove(); return; } // closed meanwhile: the change stands or not, and the log has it; a move is told by the gold line
    sheetBusy = null;
    if (r.ok) {
      sheetData.servers.forEach((s) => s.maps.forEach((m) => { if (ids.includes(m.id)) m.shown = show; }));
      sheetData.last = r.last;
      const changed = r.changed || [];
      sheetLine = changed.length ? { text: "Saved: " + r.saved + ".", undo: how === "undo" ? null : { maps: changed, show: !show } } : { text: "Nothing to change." };
      if (changed.length) { drawSheet(); await mapsChanged(); if (box !== sheetBox) return; applyOwnMove(); } // this page follows at once, not only on the event
    } else {
      sheetLine = { text: r.message || "Not saved.", bad: true }; // the chips stay as they were (D582)
      applyOwnMove(); // the event moved this page all the same: it was saved after all
      loadSheetQuiet(); // a change that timed out may have been saved after all: the sheet shows what is kept
    }
    drawSheet();
  }
  async function loadSheetQuiet() {
    const box = sheetBox, r = await call("/api/maps");
    if (box === sheetBox && r.ok && !sheetBusy) { sheetData = r; drawSheet(); }
  }
  function redrawIfOpen() { if (host) redraw(); }

  // ---------------------------------------------------------------- a place from another tab (the TOTEMS tab's MAP ›, SU-99, D629 R7)
  // true when that server and world have a map shown: the tab then opens on it, centred on the block, with a pin there for
  // 30 seconds; false (nothing changed) when an Owner hid it or BlueMap never drew it; null when the list of maps could not
  // be read (then nothing is said about that map)
  let spot = null, spotTimer = 0, spotRetry = 0, spotMap = null;
  const SPOT_WAIT_MS = 15000;
  // whether that map is shown, the list read first if needed; changes nothing (the caller commits with showSpot once it
  // knows staff are still where they asked from: audit of 0.16.0)
  async function canShow(server, dim) {
    if (maps === null || mapsStale) await loadMaps();
    if (!maps) return null;
    return maps.includes(mapId(server, dim));
  }
  function showSpot(server, dim, x, y, z) {
    const id = mapId(server, dim);
    if (!maps || !maps.includes(id)) return false;
    stopQuiet(); // not following anyone: the place is what is looked at
    if (offSel) { offSel = null; dropOffPin(); } // nor an offline player's card and pin (audit of SU-117)
    spot = { id, x: Number(x) + 0.5, y: Number(y), z: Number(z) + 0.5, label: server + " · " + x + " " + y + " " + z, until: Date.now() + SPOT_WAIT_MS };
    cur = id; saveLast(id);
    return true;
  }
  // once BlueMap has started and knows the map: switched, centred (again after BlueMap settles on its start position), and
  // pinned; tried every 300 ms, and dropped after 15 s so it never fires on a later load (audit of SU-99)
  function applySpot() {
    clearTimeout(spotRetry);
    const s = spot;
    if (!s) return;
    if (Date.now() > s.until || !host) { spot = null; return; }
    const b = bm();
    if (!b || !b.mapViewer || !b.mapsMap || !b.mapsMap.has(s.id)) { spotRetry = setTimeout(applySpot, 300); return; }
    spot = null;
    if (follow || (s.off && (!offSel || offSel.id !== s.off.id))) return; // staff began following someone, or chose another, meanwhile: that wins
    switchTo(s.id, s).then(() => {
      const bb = bm(); if (!bb || !bb.mapViewer || cur !== s.id) return;
      setTimeout(() => { try { if (cur === s.id && !follow) bb.mapViewer.controlsManager.position.set(s.x, s.y, s.z); } catch (e) { /* gone */ } }, 1200);
      try { bb.mapViewer.controlsManager.position.set(s.x, s.y, s.z); } catch (e) { /* not ready: the line above puts it right */ }
      try {
        const pop = bb.mapViewer.markers.markerSets.get("bm-popup-set"); if (!pop) return;
        // the label and detail are put in as markup by BlueMap: escaped
        if (s.off) { // an offline player's place (R7): their head in grey, kept until another is chosen or the card is closed
          if (!offSel || offSel.id !== s.off.id) return;
          pop.updateMarkerFromData("bsp-offpin", { type: "poi", position: { x: s.x, y: s.y + 1, z: s.z }, label: esc(s.label), detail: esc(s.label),
            icon: s.off.skin && /^[0-9a-f-]{36}$/.test(s.off.id) ? "/api/head/" + esc(s.off.id) : "/static/map-offline.svg", anchor: { x: 14, y: 14 }, classes: ["bsp-offpin"] }); // put in as markup by BlueMap
          offPinMap = s.id;
          return;
        }
        pop.updateMarkerFromData("bsp-spot", { type: "poi", position: { x: s.x, y: s.y + 1, z: s.z }, label: esc(s.label), detail: esc(s.label),
          icon: "/static/map-warp.svg", anchor: { x: 12, y: 24 }, classes: ["bsp-spot"] });
        spotMap = s.id;
        clearTimeout(spotTimer);
        spotTimer = setTimeout(dropSpotPin, 30000);
      } catch (e) { /* BlueMap's internals moved: the map is still centred there */ }
    });
  }
  // the pin lives in BlueMap's popup set, which outlives a map switch: it goes with the map it was put on (audit)
  function dropSpotPin() {
    clearTimeout(spotTimer); spotMap = null;
    const b = bm();
    try { const pop = b && b.mapViewer.markers.markerSets.get("bm-popup-set"), m = pop && pop.markers.get("bsp-spot"); if (m) pop.remove(m); } catch (e) { /* gone */ }
  }
  /** WORLDS' MAP › (SU-156): the region's map, zoomed to it and chosen once its regions are read; false when that map is not shown. */
  function showRegion(server, dim, id) {
    const m = mapId(server, dim);
    if (!maps || !maps.includes(m)) return false;
    stopQuiet(); if (offSel) { offSel = null; dropOffPin(); } spot = null;
    MapRegions.show(server, dim, id);
    cur = m; saveLast(m);
    return true;
  }
  /** WORLDS' + NEW REGION ON THE MAP (SU-157): that world's map, drawing once its regions are read; false when that map is not shown. */
  function drawRegionIn(server, dim) {
    const m = mapId(server, dim);
    if (!maps || !maps.includes(m)) return false;
    stopQuiet(); if (offSel) { offSel = null; dropOffPin(); } spot = null;
    MapRegions.drawIn(server, dim);
    cur = m; saveLast(m);
    return true;
  }
  return { mount, unmount, live: onLive, redraw: redrawIfOpen, mapId, mapsChanged, canShow, showSpot, showOffline, showRegion, drawRegionIn, offlineOn: () => offOn && !!host };
})();
