"use strict";
// The Map tab (SU-47; D259, D389 to D395): BlueMap's page from /map/ in a frame (the same site, so this page drives it
// through window.bluemap), its own bar hidden; beside it everyone online by server; over it the panel's bar (server,
// dimension, pins, following) and the followed player's card. Following goes with the player across servers and
// dimensions (D391), with a 2-minute trail (D392); a player who leaves is shown greyed where they were (D395 R3).
const MapView = (() => {
  const TRAIL_MS = 120000, GONE_MS = 600000;
  let root = null, frame = null, stage = null, list = null, bar = null, cardBox = null, line = null, host = null;
  let maps = null, mapsError = "", cur = null, follow = null, followName = "", trail = [], gold = null, gone = null;
  let filter = "", cardPos = null, itemsOpen = false, notesOpen = false, pinBox = null, lastLive = { servers: [], players: [] };
  let listOpen = false, sheetUp = false, fab = null, trailMap = null, lastSeen = null; // on a phone (SU-48; D393, D395 R4): the list and the card as sheets
  let mq = null, mrows = null; // the list's search box, kept while typing, and its rows
  const show = { players: true, warps: true, spawns: true, graves: true };

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
  const esc = (t) => String(t).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const bm = () => { try { return frame && frame.contentWindow && frame.contentWindow.bluemap; } catch (e) { return null; } };

  // a map not set up (404) is kept for the session; any other failure is read again at the next try (audit)
  async function loadMaps() {
    const ctl = new AbortController(), t = setTimeout(() => ctl.abort(), TIMEOUT_MS);
    try {
      const r = await fetch("/map/settings.json", { credentials: "same-origin", signal: ctl.signal });
      if (r.status === 404) { mapsError = "The map is not set up on this panel yet (map.root in panel.properties; BlueMap draws it)."; maps = []; return; }
      if (!r.ok) { mapsError = r.status >= 500 ? "The panel answered with an error (it is in the panel's console)." : "The map could not be read (" + r.status + ")."; maps = null; return; }
      const s = await r.json(); maps = Array.isArray(s.maps) ? s.maps : []; mapsError = maps.length ? "" : "BlueMap has drawn no maps yet.";
    } catch (e) { maps = null; mapsError = ctl.signal.aborted ? "The panel did not answer in time." : "The map could not be read."; }
    finally { clearTimeout(t); }
  }

  // ---------------------------------------------------------------- mounting (the frame lives while the tab is open)
  async function mount(main) {
    host = main; main.classList.add("mapmain");
    if (typeof live !== "undefined" && live) lastLive = live; // the page's own feed (app.js) as it is now
    if (!root) {
      root = el("div", "mapview"); list = el("div", "mlist"); stage = el("div", "mstage");
      root.appendChild(list); root.appendChild(stage);
    }
    main.appendChild(root);
    if (maps === null) await loadMaps();
    if (host !== main) return; // another tab opened meanwhile
    if (!maps || !maps.length) {
      stage.textContent = ""; const n = el("div", "note mnote", mapsError); stage.appendChild(n);
      if (!maps) {
        const again = el("button", "btn sm ghost", "Try again"); again.type = "button";
        again.addEventListener("click", () => { again.disabled = true; mount(main); });
        n.appendChild(document.createElement("br")); n.appendChild(again);
      }
      drawList(); return;
    }
    if (!frame || !stage.contains(frame)) {
      stage.textContent = "";
      cur = cur && maps.includes(cur) ? cur : (maps.find((m) => m.endsWith("__overworld")) || maps[0]);
      resetSwitching(); // a switch left waiting in a removed frame never ends (audit)
      frame = el("iframe", "mframe"); frame.title = "Map"; frame.src = "/map/#" + cur;
      frame.addEventListener("load", setup);
      bar = el("div", "mbar"); cardBox = el("div", "mcardhost"); line = el("div", "mgold hidden");
      fab = el("button", "mfab"); fab.type = "button"; fab.title = "Players"; fab.dataset.keep = "mfab"; fab.innerHTML = ICONS.Players; // a fixed icon of this page's own
      fab.addEventListener("click", () => { listOpen = !listOpen; drawList(); });
      stage.appendChild(frame); stage.appendChild(bar); stage.appendChild(line); stage.appendChild(cardBox); stage.appendChild(fab);
    }
    redraw();
  }
  function unmount(main) {
    if (host && host === main) { main.classList.remove("mapmain"); if (root && root.parentNode) root.remove(); host = null; frame = null; resetSwitching(); }
  }

  // BlueMap's own bar is hidden (the panel's replaces it); clicks on heads follow, clicks on pins open the panel's box
  function setup() {
    const d = frame.contentDocument; if (!d) return;
    const st = d.createElement("style");
    st.textContent = ".control-bar{display:none!important}.bm-marker-poi-label{display:none!important}";
    d.head.appendChild(st);
    fit(); setTimeout(fit, 800); // BlueMap sizes its drawing when it starts: once the frame has its size, ask it again
    hook();
    if (follow) setTimeout(() => { const p = lastLive.players.find((x) => x.id === follow); if (p && mapId(p.server, p.dim) === cur) centre(p); }, 1500); // the tab opened again (audit)
  }
  function fit() { try { frame.contentWindow.dispatchEvent(new Event("resize")); } catch (e) { /* gone */ } }
  addEventListener("resize", () => { if (host && frame) fit(); });
  function hook() {
    const b = bm(); if (!b || !b.mapViewer) return;
    if (!busy && cur && b.mapViewer.map && b.mapViewer.map.data.id !== cur && b.mapsMap && b.mapsMap.has(cur)) { // BlueMap shows another map: put it right
      const p = follow ? lastLive.players.find((x) => x.id === follow) : null; switchTo(cur, p); return;
    }
    const sets = b.mapViewer.markers.markerSets;
    const players = sets.get("bm-players");
    if (players) players.markers.forEach((m) => {
      if (m._bsp) return; m._bsp = true;
      m.onClick = () => { const p = lastLive.players.find((x) => x.id === m.data.playerUuid); if (p) start(p); return true; };
    });
    ["bsp-warps", "bsp-portal-warps", "bsp-spawns", "bsp-graves"].forEach((id) => {
      const s = sets.get(id); if (!s) return;
      s.markers.forEach((m) => {
        if (m._bsp) return; m._bsp = true;
        m.onClick = (ev) => { const r = frame.getBoundingClientRect(), sr = stage.getBoundingClientRect(), pt = ev && ev.event; // BlueMap passes {event, data} (audit)
          pinBox = { set: id, data: m.data, x: (pt ? pt.clientX : r.width / 2) + r.left - sr.left, y: (pt ? pt.clientY : r.height / 2) + r.top - sr.top }; drawPinBox(); return true; };
      });
    });
    const vis = { "bm-players": show.players, "bsp-warps": show.warps, "bsp-spawns": show.spawns, "bsp-graves": show.graves };
    Object.entries(vis).forEach(([id, on]) => { const s = sets.get(id); if (s && s.visible !== on) s.visible = on; });
  }

  // ---------------------------------------------------------------- following (D389, D391, D392, D395 R3)
  function start(p) {
    follow = p.id; followName = p.name; trail = []; gone = null; gold = null; itemsOpen = false; notesOpen = false; Players.closeKeys();
    const id = mapId(p.server, p.dim);
    if (id !== cur && maps.includes(id)) switchTo(id, p);
    else if (maps.includes(id)) centre(p);
    else gold = { text: "There is no map of " + dimName(p.dim) + " on " + p.server + ".", at: Date.now() }; // not followed into nothing (audit)
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
    cur = id; pinBox = null;
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
          gold = { text: p.name + " is in " + dimName(p.dim) + " on " + p.server + ", which has no map.", at: Date.now() };
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
    if (frame) {
      const a = document.activeElement;
      if (!(a && a.tagName === "SELECT" && bar.contains(a))) drawBar(); // the server picker open: not closed under the pointer
      drawGold();
      const card = cardBox.querySelector(".mcard"), p = follow ? lastLive.players.find((x) => x.id === follow) : null;
      if (tick && card && p && !gone && typingIn(card) && card.querySelector(".mlive")) card.querySelector(".mlive").replaceWith(livePart(p));
      else drawCard();
      drawPinBox();
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
      mrows = el("div", "mrows");
    }
    if (mq.parentNode !== list) { list.textContent = ""; list.appendChild(mq); list.appendChild(mrows); }
    if (mq.value !== filter) mq.value = filter;
    const sy = list.scrollTop;
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
    list.scrollTop = sy;
  }
  function drawBar() {
    bar.textContent = "";
    const name = (sl) => { const on = lastLive.servers.find((s) => slug(s.server) === sl); // a server not sending now: its id made readable
      return on ? on.server : sl.split("_").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" "); };
    const servers = [...new Set(maps.map(serverOf))].sort((a, b) => name(a).localeCompare(name(b)));
    const sel = el("select", "mc msel"); sel.dataset.keep = "msel"; servers.forEach((sl) => { const o = el("option", null, name(sl)); o.value = sl; o.selected = sl === serverOf(cur); sel.appendChild(o); });
    sel.addEventListener("change", () => { stopQuiet(); const id = maps.find((m) => m === sel.value + "__overworld") || maps.find((m) => serverOf(m) === sel.value); if (id) switchTo(id, null); redraw(); });
    bar.appendChild(sel);
    const seg = el("div", "mseg");
    const rank = (m) => { const d = m.slice(m.indexOf("__") + 2); return d === "overworld" ? "0" : d === "nether" ? "1" : d === "end" ? "2" : "3" + d; };
    maps.filter((m) => serverOf(m) === serverOf(cur)).sort((a, b) => rank(a).localeCompare(rank(b))).forEach((m) => {
      const b = el("button", m === cur ? "on" : "", dimLabel(m.slice(m.indexOf("__") + 2))); b.type = "button"; b.dataset.keep = "mdim:" + m;
      b.addEventListener("click", () => { if (m === cur) return; stopQuiet(); switchTo(m, null); redraw(); });
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
  }
  function drawCard() {
    cardBox.textContent = "";
    stage.classList.toggle("fol", !!follow); // on a phone the players button moves up out of the sheet's way
    if (!follow) return;
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
        box.style.left = cardPos[0] + "px"; box.style.top = cardPos[1] + "px"; box.style.right = "auto"; box.style.bottom = "auto"; };
      const up = () => { grip.removeEventListener("pointermove", move); grip.removeEventListener("pointerup", up); };
      grip.addEventListener("pointermove", move); grip.addEventListener("pointerup", up);
    });
  }
  // a tapped pin (D395 R1): its name and server; for a warp or spawn, the player followed can be moved there (one tap, D348)
  const text = (html) => { const t = document.createElement("div"); t.innerHTML = String(html || ""); return t.textContent; }; // our own escaped labels (MapLive), read back
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

  function redrawIfOpen() { if (host) redraw(); }
  return { mount, unmount, live: onLive, redraw: redrawIfOpen, mapId };
})();
