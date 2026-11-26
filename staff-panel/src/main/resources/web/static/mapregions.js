"use strict";
// The Map tab's Regions window and outlines (SU-156; D766 to D768, D776, D777, set BE A refined, R5 and R9): the BSP Region
// Guard regions of the map shown, by kind, in a second floating window with the players window's look (see-through, dragged
// by its strip, sized from its corner, folded with "–", kept in this browser apart from it; the last touched on top); on a
// phone a sheet behind its own REGIONS button. Each region is a flat see-through rectangle on BlueMap in its kind's colour
// (D766, D767), shown or hidden by kind, one by one, or all at once with REGIONS in the bar (D768). Choosing one (its row or
// its rectangle) stops following, zooms the map to fit it, draws it as a box of its real heights and opens its card with
// ZOOM TO FIT, RESET NOW for a mine and EDIT › (D777). The outlines are BlueMap markers this page puts in BlueMap's popup
// set, which BlueMap's own marker refresh leaves alone; their labels and details are put in as markup by BlueMap: escaped.

const MapRegions = (() => {
  const KEY = "bsp.mapRegions", FRESH_MS = 60000, ARM_MS = 3000, WMIN = 200;
  const COLOURS = { protected: [255, 92, 92], mine: [255, 210, 58], plot_for_sale: [166, 227, 107], claim: [90, 209, 106], open_for_buying: [127, 178, 255] };
  const ORDER = ["protected", "mine", "plot_for_sale", "claim", "open_for_buying"];
  const LABEL = { protected: "Region", mine: "Mine", plot_for_sale: "Plot for sale", claim: "Claim", open_for_buying: "Open area" };
  let ctx = null, box = null, ft = null, ct = null, mn = null, rz = null, q = null, chipsBox = null, rows = null, fabBtn = null, foot = null;
  let pref = loadPref(), data = new Map(), loading = new Set(), chosen = null, want = null, q0 = "", sheet = false, drawnSig = "", armReset = 0, msg = "";

  function loadPref() {
    const d = { x: -1, y: 54, w: 250, h: 0, folded: true, kinds: [], ids: [], off: false };
    try { const s = JSON.parse(localStorage.getItem(KEY) || "null"); if (s && typeof s === "object") return Object.assign(d, s); } catch (e) { /* the first time */ }
    return d;
  }
  function savePref() { try { localStorage.setItem(KEY, JSON.stringify(pref)); } catch (e) { /* kept for this page only */ } }
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const esc = (t) => String(t).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const hex = (k) => { const c = COLOURS[k] || [154, 163, 181]; return "#" + c.map((n) => n.toString(16).padStart(2, "0")).join(""); };
  const sizeOf = (r) => (r.box[3] - r.box[0] + 1) + " × " + (r.box[5] - r.box[2] + 1);
  const admin = () => typeof me !== "undefined" && me && (me.rank === "ADMIN" || me.rank === "OWNER");
  const idKey = (server, r) => server + "|" + r.dim + "|" + r.id;

  // ---------------------------------------------------------------- what the map shows
  function here() { // the server (as the feed names it) and the dimension slug of the map shown
    const cur = ctx && ctx.cur(); if (!cur) return null;
    return { map: cur, server: ctx.serverName(ctx.serverOf(cur)), dim: ctx.dimOf(cur) };
  }
  function regionsHere() {
    const h = here(); if (!h) return { h: null, page: null, list: [] };
    const page = data.get(h.server.toLowerCase());
    const list = page && page.ok ? (page.regions || []).filter((r) => r.box && r.type !== "global" && !r.shellOf && ctx.mapId(h.server, r.dim) === h.map) : [];
    return { h, page, list };
  }
  const shown = (server, r) => !pref.off && !pref.kinds.includes(r.type) && !pref.ids.includes(idKey(server, r));
  const gens = new Map(); // bumped by forget(): an answer to a read begun before a change is dropped and read again (audit)
  async function load(server) {
    const k = server.toLowerCase();
    if (loading.has(k)) return;
    loading.add(k);
    const g = gens.get(k) || 0;
    const r = await call("/api/regions/server", { server });
    loading.delete(k);
    if ((gens.get(k) || 0) !== g) { load(server); return; }
    const old = data.get(k);
    if (!r.ok && old && old.ok) data.set(k, Object.assign({}, old, { at: Date.now() - FRESH_MS + 20000 })); // a failed read keeps the outlines; tried again in 20 s (audit)
    else data.set(k, Object.assign({}, r, { at: Date.now() }));
    if (chosen && r.ok) { // the chosen region as read now, or gone
      const n = (r.regions || []).find((x) => x.dim === chosen.r.dim && x.id === chosen.r.id);
      if (chosen.server.toLowerCase() === k) { if (n) chosen.r = n; else chosen = null; }
    }
    drawnSig = ""; draw(); sync(); if (ctx) ctx.redrawCard();
  }
  function ensure() {
    const h = here(); if (!h) return;
    const p = data.get(h.server.toLowerCase());
    if (!p || Date.now() - p.at > FRESH_MS) load(h.server);
  }
  /** After a change on the WORLDS tab: that server is read again when next looked at. */
  function forget(server) { const k = String(server).toLowerCase(); data.delete(k); gens.set(k, (gens.get(k) || 0) + 1); drawnSig = ""; }
  /** A server's regions changed (its regionsAt moved, D781): read again now if its map is shown, else when next looked at. */
  function regionsChanged(server) { const k = String(server).toLowerCase(); if (!data.has(k) && !loading.has(k)) return; forget(server); sync(); } // a read under way is dropped by its generation

  // ---------------------------------------------------------------- the outlines on BlueMap
  function popSet() { const b = ctx && ctx.bm(); try { return b && b.mapViewer && b.mapViewer.markers.markerSets.get("bm-popup-set"); } catch (e) { return null; } }
  function sync() {
    if (!ctx) return;
    ensure();
    const b = ctx.bm(), pop = popSet();
    if (!b || !pop || !b.mapViewer.map || b.mapViewer.map.data.id !== ctx.cur()) return; // BlueMap not ready, or still switching
    const { h, page, list } = regionsHere();
    if (want && Date.now() > want.until) want = null; // never chosen unasked later (audit)
    if (wantDraw && Date.now() > wantDraw.until) wantDraw = null;
    if (want && page && page.ok && h && ctx.mapId(want.server, want.dim) === h.map) { // MAP › from the WORLDS tab, once the map and its regions are here
      const r = list.find((x) => x.id === want.id) || (page.regions || []).find((x) => x.id === want.id && ctx.mapId(h.server, x.dim) === h.map);
      const said = want.msg; want = null;
      if (r) { choose(h.server, r); if (said) { msg = said; ctx.redrawCard(); } return; }
    }
    if (chosen && (!h || chosen.map !== h.map)) chosen = null; // its card goes with its map
    if (dr && (!h || dr.map !== h.map)) { stopDraw(); return; } // drawing belongs to the map it began on
    if (wantDraw && page && page.ok && h && ctx.mapId(wantDraw.server, wantDraw.dim) === h.map) { wantDraw = null; startDraw(); }
    if (dr) { hookDoc(); preview(); }
    const vis = list.filter((r) => shown(h.server, r));
    const sel = chosen && chosen.map === h.map ? chosen.r : null;
    const sig = h.map + "|" + vis.map((r) => r.id + ":" + r.version).join(",") + "|" + (sel ? sel.id + ":" + sel.version : "");
    if (sig === drawnSig) return;
    drawnSig = sig;
    try {
      const keep = new Set();
      const dim = page && (page.dims || []).find((d) => ctx.mapId(h.server, d.id) === h.map);
      vis.forEach((r) => {
        const id = "bsp-rg:" + r.id, c = COLOURS[r.type] || [154, 163, 181], on = sel && sel.id === r.id;
        const x1 = r.box[0], z1 = r.box[2], x2 = r.box[3] + 1, z2 = r.box[5] + 1, y = clamp(64, r.box[1], r.box[4]);
        pop.updateMarkerFromData(id, { type: "shape", position: { x: x1, y, z: z1 }, shape: [{ x: x1, z: z1 }, { x: x2, z: z1 }, { x: x2, z: z2 }, { x: x1, z: z2 }],
          shapeY: y, depthTest: false, lineWidth: on ? 3 : 2, lineColor: { r: c[0], g: c[1], b: c[2], a: 0.95 }, fillColor: { r: c[0], g: c[1], b: c[2], a: on ? 0.3 : 0.15 },
          label: esc(r.id), detail: "<b>" + esc(r.id) + "</b><br>" + esc(LABEL[r.type] || r.type), classes: [] });
        const m = pop.markers.get(id);
        if (m) m.onClick = () => { if (dr) return false; choose(h.server, r); return true; }; // the panel's card, not BlueMap's popup; while drawing, a corner
        keep.add(id);
      });
      if (sel) { // the chosen one as a box of its real heights (D767)
        const c = COLOURS[sel.type] || [154, 163, 181], lo = dim ? Math.max(sel.box[1], dim.minY) : sel.box[1], hi = dim ? Math.min(sel.box[4] + 1, dim.maxY + 1) : sel.box[4] + 1;
        const x1 = sel.box[0], z1 = sel.box[2], x2 = sel.box[3] + 1, z2 = sel.box[5] + 1;
        pop.updateMarkerFromData("bsp-rgsel", { type: "extrude", position: { x: x1, y: lo, z: z1 }, shape: [{ x: x1, z: z1 }, { x: x2, z: z1 }, { x: x2, z: z2 }, { x: x1, z: z2 }],
          shapeMinY: lo, shapeMaxY: hi, depthTest: false, lineWidth: 3, lineColor: { r: c[0], g: c[1], b: c[2], a: 1 }, fillColor: { r: c[0], g: c[1], b: c[2], a: 0.12 },
          label: esc(sel.id), detail: "<b>" + esc(sel.id) + "</b>", classes: [] });
        const m = pop.markers.get("bsp-rgsel"); if (m) m.onClick = () => true;
        keep.add("bsp-rgsel");
      }
      [...pop.markers.keys()].forEach((k) => { if ((String(k).startsWith("bsp-rg:") || k === "bsp-rgsel") && !keep.has(k)) pop.remove(pop.markers.get(k)); }); // another map's, or hidden
    } catch (e) { drawnSig = ""; /* BlueMap's internals moved: the map still works without the outlines */ }
  }
  /** A new frame (the tab opened again, the map reloaded): every outline is put in again. */
  function frameLoaded() { drawnSig = ""; docHooked = null; if (dr) stopDraw(); }

  // ---------------------------------------------------------------- choosing (D777)
  function choose(server, r) {
    const h = here(); if (!h || dr) return;
    ctx.stopOthers(); // nobody followed, no offline pin: the region is what is looked at
    chosen = { server, map: h.map, r }; armReset = 0; msg = "";
    sheet = false; drawnSig = ""; sync(); fit(); draw(); ctx.redraw();
  }
  function fit() {
    if (!chosen) return;
    const b = ctx.bm(); if (!b || !b.mapViewer) return;
    const r = chosen.r, cx = (r.box[0] + r.box[3] + 1) / 2, cz = (r.box[2] + r.box[5] + 1) / 2, side = Math.max(r.box[3] - r.box[0] + 1, r.box[5] - r.box[2] + 1);
    try {
      const c = b.mapViewer.controlsManager;
      c.position.set(cx, clamp(64, r.box[1], r.box[4]), cz);
      c.distance = clamp(side * 2.2, 50, 20000); // room round it for the windows and the card
    } catch (e) { /* not ready: the card still shows */ }
  }
  /** WORLDS' MAP ›: this region, once its map is up (the Map tab switches to it). */
  function show(server, dim, id, said) { want = { server, dim, id, msg: said || null, until: Date.now() + 30000 }; wantDraw = null; chosen = null; drawnSig = ""; }
  function drop() { if (chosen) { chosen = null; drawnSig = ""; sync(); } }
  const active = () => !!chosen;

  // ---------------------------------------------------------------- the card (R5)
  function card(host) {
    if (!chosen) return false;
    const r = chosen.r, k = r.type;
    const c = el("div", "mcard mrcard"); host.appendChild(c); c.dataset.scroll = "mrcard";
    const top = el("div", "dtop");
    const sw = el("i", "mrsw" + (k === "plot_for_sale" ? " dash" : "")); sw.style.background = hex(k); top.appendChild(sw);
    const who = el("div", "grow"); who.appendChild(el("div", "dname mono", r.id));
    const tag = el("span", "mrkt", LABEL[k] || k); tag.style.color = hex(k); tag.style.borderColor = hex(k) + "66"; tag.style.background = hex(k) + "22";
    const sub = el("div", "sub"); sub.appendChild(tag); sub.appendChild(document.createTextNode(" " + (r.name || ""))); who.appendChild(sub); top.appendChild(who);
    const x = el("button", "close", "✕"); x.type = "button"; x.title = "Close"; x.dataset.keep = "mrclose"; x.addEventListener("click", () => { drop(); ctx.redraw(); });
    top.appendChild(x); c.appendChild(top);
    const kv = el("div", "mrkv");
    const row = (a, b) => { kv.appendChild(el("span", "mu", a)); kv.appendChild(el("span", "mono", b)); };
    row("Corners", r.box[0] + ", " + r.box[1] + ", " + r.box[2] + " → " + r.box[3] + ", " + r.box[4] + ", " + r.box[5]);
    row("Size", sizeOf(r) + ", Y " + r.box[1] + " to " + r.box[4]);
    row("Priority", String(r.priority));
    if (r.parent) row("Parent", r.parent);
    if (k === "claim" && r.owners && r.owners.length) row("Owner", r.owners.map((p) => p.name).join(", "));
    if (k === "plot_for_sale" && r.claim && r.claim.price !== undefined) row("Price", Number(r.claim.price).toLocaleString("en-GB") + " coins");
    if (k === "open_for_buying" && r.claim && r.claim.costPerBlock !== undefined) row("Cost", Number(r.claim.costPerBlock).toLocaleString("en-GB") + " a block");
    if (k === "mine" && r.mine && r.mine.nextResetAt) row("Resets", "in " + Math.max(0, Math.round((r.mine.nextResetAt - Date.now()) / 60000)) + " min");
    c.appendChild(kv);
    if (msg) c.appendChild(el("div", msg.ok ? "okm" : "err", msg.text));
    const keys = el("div", "mrkeys");
    const z = el("button", "key", "Zoom to fit"); z.type = "button"; z.dataset.keep = "mrfit"; z.addEventListener("click", fit); keys.appendChild(z);
    if (k === "mine") {
      const armed = Date.now() - armReset < ARM_MS;
      const rs = el("button", "key" + (armed ? " danger" : ""), armed ? "Sure? Reset" : "Reset now"); rs.type = "button"; rs.dataset.keep = "mrreset";
      if (!admin()) { rs.disabled = true; rs.appendChild(el("span", "rk ADMIN", "Admin")); }
      rs.addEventListener("click", async () => {
        if (Date.now() - armReset >= ARM_MS) { armReset = Date.now(); ctx.redraw(); return; } // armed only for its three seconds (audit)
        armReset = 0; rs.disabled = true;
        const was = chosen, server = was.server;
        const a = await call("/api/regions/reset", { server, dim: r.dim, id: r.id }, true);
        if (chosen === was) msg = { ok: !!a.ok, text: a.message }; // said on its own card only
        forget(server); ctx.redraw(); ensure();
      });
      unarm(rs, ARM_MS - (Date.now() - armReset), "Reset now", "key");
      keys.appendChild(rs);
    }
    const ed = el("button", "key tlk", "Edit ›"); ed.type = "button"; ed.dataset.keep = "mredit";
    ed.addEventListener("click", () => { if (typeof Worlds !== "undefined") Worlds.edit(chosen.server, r.dim, r.id); });
    keys.appendChild(ed); c.appendChild(keys);
    return true;
  }

  // ---------------------------------------------------------------- the window
  function attach(c) {
    ctx = c;
    box = el("div", "mlist mrlist");
    ft = el("div", "mft"); ft.title = "Drag to move";
    ft.appendChild(el("div", "gp")); ft.appendChild(el("span", "tt", "REGIONS")); ct = el("span", "ct"); ft.appendChild(ct);
    mn = el("button", "mmin"); mn.type = "button"; mn.dataset.keep = "mrmin";
    mn.addEventListener("click", () => { pref.folded = !pref.folded; savePref(); layout(); });
    ft.appendChild(mn);
    q = el("input", "search mq"); q.type = "search"; q.placeholder = "Find a region"; q.dataset.keep = "mrq";
    q.addEventListener("input", () => { q0 = q.value; draw(); });
    chipsBox = el("div", "mrchips"); rows = el("div", "mrows"); foot = el("div", "mrfoot");
    rz = el("div", "mrz"); rz.title = "Drag to size";
    [ft, q, chipsBox, rows, foot, rz].forEach((x) => box.appendChild(x));
    box.addEventListener("pointerdown", () => { box.classList.add("front"); ctx.back(); }, true); // the last touched on top (D776)
    c.root.appendChild(box);
    fabBtn = el("button", "mfab mrfab"); fabBtn.type = "button"; fabBtn.title = "Regions"; fabBtn.dataset.keep = "mrfab";
    fabBtn.innerHTML = '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 12h16M12 4v16"/></svg>'; // a fixed icon of this page's own
    fabBtn.addEventListener("click", () => { sheet = !sheet; if (sheet) ctx.closePlayers(); draw(); });
    move(); size();
  }
  const fab = () => fabBtn;
  /** The players window was touched: it is on top. */
  function back() { if (box) box.classList.remove("front"); }
  function closeSheet() { if (sheet) { sheet = false; draw(); } }
  function barKey() {
    const b = el("button", "mc mrkey" + (pref.off ? "" : " on")); b.type = "button"; b.dataset.keep = "mrkey"; b.title = pref.off ? "Show the region outlines" : "Hide every region outline";
    b.appendChild(el("i", "mrdot")); b.appendChild(document.createTextNode("Regions"));
    b.addEventListener("click", () => { pref.off = !pref.off; savePref(); drawnSig = ""; sync(); ctx.redraw(); });
    return b;
  }
  function draw() {
    if (!box || !ctx) return;
    if (dr) { // the form: rebuilt only when the kind, the step or the height mode changes, its scroll kept; a live tick touches nothing (audit)
      const sig = dr.kind + "|" + dr.phase + "|" + dr.hmode;
      if (sig !== formSig && !typingIn(box)) { const y = rows.scrollTop; formSig = sig; drawForm(); rows.scrollTop = y; }
      layout(); return;
    }
    formSig = "";
    ft.querySelector(".tt").textContent = "REGIONS"; q.classList.remove("hidden"); chipsBox.classList.remove("hidden"); box.classList.remove("mrform");
    box.classList.toggle("open", sheet);
    if (fabBtn) fabBtn.classList.toggle("on", sheet);
    if (q.value !== q0) q.value = q0;
    const { h, page, list } = regionsHere();
    ct.textContent = page && page.ok ? String(list.length) : "";
    // the kind chips (kept in this browser)
    chipsBox.textContent = "";
    ORDER.forEach((k) => {
      const c = el("button", "mrchip" + (pref.kinds.includes(k) ? " off" : "")); c.type = "button"; c.dataset.keep = "mrchip:" + k;
      const sw = el("i", "mrsw" + (k === "plot_for_sale" ? " dash" : "")); sw.style.background = hex(k); c.appendChild(sw); c.appendChild(document.createTextNode(LABEL[k]));
      c.addEventListener("click", () => { const i = pref.kinds.indexOf(k); if (i >= 0) pref.kinds.splice(i, 1); else pref.kinds.push(k); savePref(); drawnSig = ""; sync(); draw(); });
      chipsBox.appendChild(c);
    });
    const sy = rows.scrollTop;
    rows.textContent = "";
    if (!h) { rows.appendChild(el("div", "note mrnote", "No map is shown.")); layout(); return; }
    if (!page) { rows.appendChild(el("div", "note mrnote", "Reading " + h.server + "'s regions…")); layout(); return; }
    if (!page.ok) { rows.appendChild(el("div", "note mrnote", page.message || "The regions could not be read.")); footer(); layout(); return; }
    if (pref.off) rows.appendChild(el("div", "note mrnote", "Outlines are hidden: REGIONS in the bar shows them."));
    const f = q0.trim().toLowerCase();
    let any = false;
    ORDER.forEach((k) => {
      const rs = list.filter((r) => r.type === k && (!f || r.id.toLowerCase().includes(f) || String(r.name || "").toLowerCase().includes(f)))
        .sort((a, b) => (b.priority - a.priority) || a.id.localeCompare(b.id));
      if (!rs.length) return;
      any = true;
      const g = el("div", "mgrp mrgrp"); const sw = el("i", "mrsw" + (k === "plot_for_sale" ? " dash" : "")); sw.style.background = hex(k);
      g.appendChild(sw); g.appendChild(el("span", "grow", LABEL[k])); g.appendChild(el("span", null, String(rs.length))); rows.appendChild(g);
      rs.forEach((r) => {
        const on = chosen && chosen.r.id === r.id && chosen.map === h.map, vis = !pref.ids.includes(idKey(h.server, r));
        const row = el("div", "mrow mrrow" + (on ? " on" : "") + (vis && !pref.kinds.includes(k) ? "" : " hid"));
        const eye = el("button", "mreye" + (vis ? "" : " off")); eye.type = "button"; eye.dataset.keep = "mreye:" + r.id; eye.title = vis ? "Hide its outline" : "Show its outline";
        eye.innerHTML = vis ? '<svg viewBox="0 0 24 24"><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>'
          : '<svg viewBox="0 0 24 24"><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><path d="M4 4l16 16"/></svg>'; // fixed icons of this page's own
        eye.addEventListener("click", (ev) => { ev.stopPropagation(); const key = idKey(h.server, r), i = pref.ids.indexOf(key); if (i >= 0) pref.ids.splice(i, 1); else pref.ids.push(key); savePref(); drawnSig = ""; sync(); draw(); });
        const pick = el("button", "mrpick"); pick.type = "button"; pick.dataset.keep = "mrrow:" + r.id;
        pick.appendChild(el("span", "nm", r.id)); pick.appendChild(el("span", "d", sizeOf(r)));
        pick.addEventListener("click", () => choose(h.server, r));
        row.appendChild(eye); row.appendChild(pick); rows.appendChild(row);
      });
    });
    if (!any) rows.appendChild(el("div", "note mrnote", f ? "No region here matches." : "No regions in this world."));
    footer();
    rows.scrollTop = sy;
    layout();
  }
  // where it is drawn: as kept, inside the map and within its limits, as the players window (D551, D567); first beside it, folded
  function place() {
    const W = ctx.root.clientWidth, H = ctx.root.clientHeight;
    const hMin = Math.round(H / 5), hMax = Math.max(hMin, Math.round(H * 2 / 3)), wMax = Math.max(WMIN, Math.round(W * 2 / 3));
    if (pref.x < 0) { const p = ctx.playersRect(); pref.x = p ? p.x + p.w + 10 : 256; pref.y = p ? p.y : 54; }
    const h = clamp(pref.h || Math.round(H / 2), hMin, hMax), w = clamp(pref.w, WMIN, wMax);
    const tall = pref.folded ? (ft.offsetHeight ? ft.offsetHeight + 2 : 36) : h;
    return { x: clamp(pref.x, 0, Math.max(0, W - w)), y: clamp(pref.y, 0, Math.max(0, H - tall)), w, h, W, H, hMin, hMax, wMax };
  }
  function layout() {
    if (!box || !ctx) return;
    if (dr && !ctx.desk()) { stopDraw(); return; } // drawing is on a computer: a window made narrow stops it (audit)
    box.classList.toggle("folded", !!pref.folded);
    mn.textContent = pref.folded ? "+" : "–"; mn.title = pref.folded ? "Open" : "Fold";
    if (!ctx.desk() || !ctx.root.clientHeight) { ["left", "top", "width", "height"].forEach((k) => { box.style[k] = ""; }); return; } // a phone: the sheet
    const g = place();
    const tall = dr ? Math.max(g.h, Math.min(640, g.H - g.y - 10)) : g.h; // the form at its full height while drawing (the size chosen is kept)
    box.style.left = g.x + "px"; box.style.top = g.y + "px"; box.style.width = g.w + "px"; box.style.height = pref.folded ? "" : tall + "px";
    solid();
  }
  // solid while any part of it covers a card (as the players window, D565)
  function solid() {
    const c = ctx.cardBox() && ctx.cardBox().querySelector(".mcard");
    let on = false;
    if (c && ctx.desk()) { const a = box.getBoundingClientRect(), b = c.getBoundingClientRect(); on = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom; }
    box.classList.toggle("solid", on || !!dr);
  }
  function move() {
    ft.addEventListener("pointerdown", (e) => {
      if (!ctx.desk() || e.button !== 0 || e.target.closest("button")) return;
      const g = place(), sx = e.clientX, sy = e.clientY; let moved = false;
      ft.setPointerCapture(e.pointerId); document.body.classList.add("mapdrag");
      const mv = (m) => {
        const dx = m.clientX - sx, dy = m.clientY - sy;
        if (!moved && Math.abs(dx) + Math.abs(dy) < 4) return;
        moved = true; pref.x = g.x + dx; pref.y = g.y + dy;
        const p = place(); pref.x = p.x; pref.y = p.y; layout();
      };
      let done = false;
      const up = () => {
        if (done) return; done = true; document.body.classList.remove("mapdrag");
        ["pointermove", "pointerup", "pointercancel", "lostpointercapture"].forEach((t) => ft.removeEventListener(t, t === "pointermove" ? mv : up));
        if (moved) savePref(); else if (pref.folded) { pref.folded = false; savePref(); layout(); }
      };
      ft.addEventListener("pointermove", mv); ["pointerup", "pointercancel", "lostpointercapture"].forEach((t) => ft.addEventListener(t, up));
    });
  }
  function size() {
    rz.addEventListener("pointerdown", (e) => {
      if (!ctx.desk() || e.button !== 0) return;
      e.stopPropagation();
      const g = place(), sx = e.clientX, sy = e.clientY, tag = ctx.sizeTag();
      rz.setPointerCapture(e.pointerId); document.body.classList.add("mapdrag");
      const mv = (m) => {
        pref.x = g.x; pref.y = g.y;
        pref.w = clamp(g.w + m.clientX - sx, WMIN, Math.max(WMIN, Math.min(g.wMax, g.W - g.x)));
        pref.h = clamp(g.h + m.clientY - sy, g.hMin, Math.max(g.hMin, Math.min(g.hMax, g.H - g.y)));
        layout();
        if (tag) { tag.textContent = Math.round(pref.w) + " × " + Math.round(pref.h); tag.style.left = Math.max(0, Math.min(g.W - 90, g.x + pref.w - 40)) + "px"; tag.style.top = Math.min(g.H - 26, g.y + pref.h + 8) + "px"; tag.classList.remove("hidden"); }
      };
      let done = false;
      const up = () => {
        if (done) return; done = true; document.body.classList.remove("mapdrag");
        ["pointermove", "pointerup", "pointercancel", "lostpointercapture"].forEach((t) => rz.removeEventListener(t, t === "pointermove" ? mv : up));
        if (tag) tag.classList.add("hidden"); savePref();
      };
      rz.addEventListener("pointermove", mv); ["pointerup", "pointercancel", "lostpointercapture"].forEach((t) => rz.addEventListener(t, up));
    });
  }

  // ---------------------------------------------------------------- drawing a new region from two points (SU-157; D770, D771, set BF B refined, R6 to R8)
  // NEW REGION (Admins and Owners, on a computer): the window turns into the form; the kind first, then a click for each
  // corner on the map (the block under the pointer shown as it moves); then handles on the corners and edges move the
  // outline, typed corners move it too, and Region Guard's check (its dry run) runs a moment after each change. The block
  // under a click comes from BlueMap's own terrain when it is drawn there, else from the flat plane at the camera's height
  // (its X and Z only). CREATE once the check passes; the window goes back to the list with the new region chosen.
  const DEF_PRI = { protected: 10, mine: 15, plot_for_sale: 30, open_for_buying: 5 }, PREFIX = { protected: "region", mine: "mine", plot_for_sale: "plot", open_for_buying: "open" };
  let dr = null, ov = null, banner = null, raf = 0, wantDraw = null, docHooked = null, formSig = "";
  const PLOT_RULE = { pvp_denied: "it is a PvP world", world_refuses: "its global region refuses them", config_refuses: "the server's config refuses them" }; // WorldView.plotRule
  const drawing = () => !!dr;
  function startDraw() {
    if (!ctx || !admin() || !ctx.desk()) return;
    const { h, page } = regionsHere(); if (!h || !page || !page.ok) return;
    const d = (page.dims || []).find((x) => ctx.mapId(h.server, x.id) === h.map); if (!d) return;
    ctx.stopOthers(); chosen = null; drawnSig = "";
    dr = { server: h.server, map: h.map, dim: d.id, minY: d.minY, maxY: d.maxY, kind: "protected", phase: 1, a: null, b: null, hover: null, bx: null,
      ys: [d.minY, d.maxY], hmode: "full", id: "", name: "", priority: DEF_PRI.protected, parent: "", parentSet: false,
      price: 4000, cost: 12, min: 5, max: 64, every: 20, fill: "minecraft:stone 100", check: null, gen: 0, timer: 0, busy: false, msg: "", noPlots: d.plotsAllowed === undefined ? !!d.pvp : !d.plotsAllowed, plotRule: d.plotRule || "" }; // D778
    dr.id = nextId("protected");
    pref.folded = false; savePref();
    hookDoc(); loop(); draw(); sync(); ctx.redraw();
  }
  function stopDraw() {
    if (!dr) return;
    clearTimeout(dr.timer); dr = null; cancelAnimationFrame(raf); raf = 0;
    if (ov) { ov.remove(); ov = null; } if (banner) { banner.remove(); banner = null; }
    const pop = popSet(); try { const m = pop && pop.markers.get("bsp-draw"); if (m) pop.remove(m); } catch (e) { /* gone */ }
    drawnSig = ""; draw(); sync(); if (ctx) ctx.redraw();
  }
  function nextId(kind) {
    const { list } = regionsHere(), used = new Set(list.map((r) => r.id)), p = PREFIX[kind];
    for (let i = 1; i < 1000; i++) if (!used.has(p + "_" + i)) return p + "_" + i;
    return p + "_new";
  }
  // the block under a point of BlueMap's page (its own coordinates): {x, y (null when not known), z}
  const drawY = () => clamp(64, dr.ys[0], dr.ys[1]); // the outline's height: handles and drags use it too (audit)
  function pick(cx, cy, planeY) {
    const b = ctx.bm(), f = ctx.frame(), w = f && f.contentWindow, T = w && w.BlueMap && w.BlueMap.Three;
    if (!b || !b.mapViewer || !T) return null;
    try {
      const mv = b.mapViewer, canvas = mv.renderer.domElement, r = canvas.getBoundingClientRect();
      const rc = new T.Raycaster(); rc.setFromCamera(new T.Vector2((cx - r.left) / r.width * 2 - 1, -((cy - r.top) / r.height) * 2 + 1), mv.camera);
      const hs = planeY === undefined && mv.map && mv.map.hiresTileManager && mv.map.hiresTileManager.sceneParent, hit = hs ? rc.intersectObject(hs, true)[0] : null;
      if (hit) { const p = (hit.pointOnLine || hit.point).clone().add(rc.ray.direction.clone().multiplyScalar(0.05)).floor(); return { x: p.x, y: p.y, z: p.z }; }
      const p = new T.Vector3(); if (!rc.ray.intersectPlane(new T.Plane(new T.Vector3(0, 1, 0), -(planeY === undefined ? mv.controlsManager.position.y : planeY)), p)) return null;
      return { x: Math.floor(p.x), y: null, z: Math.floor(p.z) };
    } catch (e) { return null; }
  }
  // a world point on the stage, for the handles and the size
  function toStage(x, z) {
    const b = ctx.bm(), f = ctx.frame(), w = f && f.contentWindow, T = w && w.BlueMap && w.BlueMap.Three;
    if (!b || !T) return null;
    try {
      const mv = b.mapViewer, canvas = mv.renderer.domElement, r = canvas.getBoundingClientRect(), v = new T.Vector3(x, drawY(), z).project(mv.camera);
      const fr = f.getBoundingClientRect(), sr = ctx.stage().getBoundingClientRect();
      return { x: (v.x + 1) / 2 * r.width + r.left + fr.left - sr.left, y: (1 - v.y) / 2 * r.height + r.top + fr.top - sr.top };
    } catch (e) { return null; }
  }
  function hookDoc() {
    const f = ctx.frame(), d = f && f.contentDocument; if (!d || docHooked === d) return;
    docHooked = d;
    let down = null;
    d.addEventListener("pointerdown", (e) => { down = dr && e.button === 0 ? { x: e.clientX, y: e.clientY } : null; }, true);
    d.addEventListener("pointermove", (e) => { if (!dr || dr.phase === 3) return; dr.hover = pick(e.clientX, e.clientY); dr.hoverAt = { x: e.clientX, y: e.clientY }; preview(); }, true);
    d.addEventListener("pointerup", (e) => {
      if (!dr || !down || dr.phase === 3 || Math.abs(e.clientX - down.x) + Math.abs(e.clientY - down.y) > 5) return; // a drag moves the map
      const p = pick(e.clientX, e.clientY); if (!p) return;
      if (dr.phase === 1) { dr.a = p; dr.phase = 2; }
      else { dr.b = p; dr.phase = 3; settle(); }
      quietPopup(); draw(); preview();
    }, true);
    f.contentWindow.addEventListener("keydown", (e) => { if (e.key === "Escape" && dr) stopDraw(); });
  }
  function quietPopup() { setTimeout(() => { try { const pm = ctx.bm().popupMarker; if (pm && pm.close) pm.close(); } catch (e) { /* none */ } }, 40); } // BlueMap's own block popup stays shut
  // both corners set: the box, its heights (D771) and a parent that holds it
  function settle() {
    const a = dr.a, b = dr.b;
    dr.bx = [Math.min(a.x, b.x), Math.min(a.z, b.z), Math.max(a.x, b.x), Math.max(a.z, b.z)];
    heights(dr.kind === "mine" && a.y !== null && b.y !== null ? "clicked" : "full");
    if (!dr.parentSet) dr.parent = holder();
    changed();
  }
  function heights(mode) {
    if (mode === "clicked" && dr.a && dr.b && dr.a.y !== null && dr.b.y !== null) dr.ys = [Math.min(dr.a.y, dr.b.y), Math.max(dr.a.y, dr.b.y)];
    else { mode = mode === "clicked" ? "full" : mode; if (mode === "full") dr.ys = [dr.minY, dr.maxY]; }
    dr.hmode = mode;
  }
  function holder() { // the highest-priority region on this map that holds the whole outline
    const { list } = regionsHere(), b = dr.bx;
    const h = list.filter((r) => r.type !== "claim" && r.box[0] <= b[0] && r.box[2] <= b[1] && r.box[3] >= b[2] && r.box[5] >= b[3]).sort((x, y) => y.priority - x.priority)[0];
    return h ? h.id : "";
  }
  function box6() { return [dr.bx[0], dr.ys[0], dr.bx[1], dr.bx[2], dr.ys[1], dr.bx[3]]; }
  function fillOf(text) { // "minecraft:stone 70, minecraft:gold_ore 30" → [{block, weight}] in tenths of a percent
    const out = [];
    String(text || "").split(",").map((x) => x.trim()).filter(Boolean).forEach((x) => { const m = x.match(/^([a-z0-9_.-]+:[a-z0-9_./-]+)\s+([0-9.]+)\s*%?$/i); out.push(m ? { block: m[1].toLowerCase(), weight: Math.round(Number(m[2]) * 10) } : { block: "?", weight: 0 }); });
    return out;
  }
  function body() {
    const o = { server: dr.server, dim: dr.dim, id: dr.id, type: dr.kind, box: box6(), priority: dr.priority, parent: dr.parent, name: dr.name || dr.id };
    if (dr.kind === "plot_for_sale") o.claim = { price: dr.price };
    if (dr.kind === "open_for_buying") o.claim = { costPerBlock: dr.cost, minSize: dr.min, maxSize: dr.max };
    if (dr.kind === "mine") o.mine = { intervalSeconds: Math.round(dr.every * 60), fill: fillOf(dr.fill) };
    return o;
  }
  // Region Guard's check, a moment after the last change (R7)
  function changed() {
    if (!dr || dr.phase !== 3) return;
    clearTimeout(dr.timer); const d = dr, g = ++dr.gen; dr.check = { busy: true, text: "Checking with Region Guard…" }; checkLine();
    dr.timer = setTimeout(async () => {
      if (dr !== d || dr.gen !== g) return;
      const r = await call("/api/regions/check", body());
      if (dr !== d || dr.gen !== g) return; // a later change, or another drawing (audit)
      dr.check = { ok: !!r.ok, text: r.ok ? "Region Guard's check: " + (r.message || "fine.") : r.panel || r.failed ? r.message : "Region Guard refuses this: " + r.message };
      checkLine(); footer();
    }, 700);
    footer(); preview();
  }
  function checkLine() {
    const l = box && box.querySelector(".mrcheck"); if (!l || !dr) return;
    const c = dr.check; l.className = "mrcheck " + (!c ? "hidden" : c.busy ? "note" : c.ok ? "okm" : "err"); l.textContent = c ? c.text : "";
  }
  async function create() {
    if (!dr || !dr.check || !dr.check.ok || dr.busy) return;
    dr.busy = true; footer();
    const d0 = dr, s = dr.server, d = dr.dim, id0 = dr.id;
    const r = await call("/api/regions/create", body(), true);
    if (dr !== d0) { if (r.ok) { forget(s); ensure(); } return; } // stopped meanwhile: still read again if it was made (audit)
    dr.busy = false;
    if (!r.ok) { dr.check = { ok: false, text: r.message }; checkLine(); footer(); return; }
    const id = (r.region || id0).toLowerCase();
    stopDraw(); forget(s); show(s, d, id, { ok: true, text: r.message }); ensure(); // the list again, the new region chosen once read
  }
  // the outline: BlueMap's shape between the corner and the pointer, then the box (its own marker, kept apart from the list's)
  function preview() {
    const pop = popSet(); if (!pop || !dr) return;
    let bx = null;
    if (dr.phase === 3) bx = dr.bx;
    else if (dr.phase === 2 && dr.hover) bx = [Math.min(dr.a.x, dr.hover.x), Math.min(dr.a.z, dr.hover.z), Math.max(dr.a.x, dr.hover.x), Math.max(dr.a.z, dr.hover.z)];
    try {
      if (!bx) { const m = pop.markers.get("bsp-draw"); if (m) pop.remove(m); return; }
      const c = COLOURS[dr.kind], x1 = bx[0], z1 = bx[1], x2 = bx[2] + 1, z2 = bx[3] + 1, y = drawY();
      pop.updateMarkerFromData("bsp-draw", { type: "shape", position: { x: x1, y, z: z1 }, shape: [{ x: x1, z: z1 }, { x: x2, z: z1 }, { x: x2, z: z2 }, { x: x1, z: z2 }], shapeY: y,
        depthTest: false, lineWidth: 2, lineColor: { r: c[0], g: c[1], b: c[2], a: 1 }, fillColor: { r: c[0], g: c[1], b: c[2], a: 0.22 }, label: "", classes: [] });
      const m = pop.markers.get("bsp-draw"); if (m) m.onClick = () => false;
    } catch (e) { /* BlueMap not ready */ }
  }
  // the handles, the size and the pointer's block, drawn over the map every frame while drawing
  // each handle: its name and where it sits, as an index into the three x and three z lines of the outline (west, middle, east; north, middle, south)
  const HANDLES = [["nw", 0, 0], ["n", 1, 0], ["ne", 2, 0], ["e", 2, 1], ["se", 2, 2], ["s", 1, 2], ["sw", 0, 2], ["w", 0, 1]];
  function loop() {
    cancelAnimationFrame(raf);
    const step = () => { if (!dr) return; overlay(); raf = requestAnimationFrame(step); };
    raf = requestAnimationFrame(step);
  }
  function overlay() {
    const st = ctx.stage(); if (!st || !ctx.frame()) { stopDraw(); return; } // the tab closed or the map went away (audit)
    if (!ov || !st.contains(ov)) { ov = el("div", "mrdraw"); st.appendChild(ov); }
    if (!banner || !st.contains(banner)) { banner = el("div", "mrbanner"); st.appendChild(banner); }
    banner.textContent = dr.phase === 1 ? "Click the first corner. " : dr.phase === 2 ? "Click the second corner. " : "Drag a handle to adjust. ";
    banner.appendChild(el("span", "mu", "Esc to stop"));
    if (dr.phase < 3) {
      ov.querySelectorAll(".mrhand, .mrsize").forEach((x) => x.remove());
      let tip = ov.querySelector(".mrtip");
      if (dr.hover && dr.hoverAt) {
        if (!tip) { tip = el("div", "mrtip"); ov.appendChild(tip); }
        const fr = ctx.frame().getBoundingClientRect(), sr = st.getBoundingClientRect();
        tip.style.left = (dr.hoverAt.x + fr.left - sr.left + 14) + "px"; tip.style.top = (dr.hoverAt.y + fr.top - sr.top + 12) + "px";
        tip.textContent = dr.hover.x + ", " + (dr.hover.y === null ? "?" : dr.hover.y) + ", " + dr.hover.z;
      } else if (tip) tip.remove();
      return;
    }
    const t = ov.querySelector(".mrtip"); if (t) t.remove();
    const b = dr.bx, xs = [b[0], (b[0] + b[2] + 1) / 2, b[2] + 1], zs = [b[1], (b[1] + b[3] + 1) / 2, b[3] + 1];
    HANDLES.forEach(([k, xi, zi]) => {
      const p = toStage(xs[xi], zs[zi]); if (!p) return;
      let hnd = ov.querySelector('[data-h="' + k + '"]');
      if (!hnd) { hnd = el("div", "mrhand" + (k.length === 1 ? " edge" : "")); hnd.dataset.h = k; ov.appendChild(hnd); grab(hnd, k); }
      hnd.style.left = p.x + "px"; hnd.style.top = p.y + "px";
    });
    const bottom = toStage(xs[1], zs[2]);
    let sz = ov.querySelector(".mrsize"); if (!sz) { sz = el("div", "mrsize"); ov.appendChild(sz); }
    if (bottom) { sz.style.left = bottom.x + "px"; sz.style.top = (bottom.y + 10) + "px"; }
    sz.textContent = (b[2] - b[0] + 1) + " × " + (b[3] - b[1] + 1) + (dr.hmode === "full" ? "" : " × " + (dr.ys[1] - dr.ys[0] + 1) + " high");
  }
  function grab(hnd, k) {
    hnd.addEventListener("pointerdown", (e) => {
      if (e.button !== 0 || !dr) return;
      e.preventDefault(); e.stopPropagation();
      hnd.setPointerCapture(e.pointerId); document.body.classList.add("mapdrag");
      const mv = (m) => {
        if (!dr || !ctx.frame()) return;
        const fr = ctx.frame().getBoundingClientRect(), p = pick(m.clientX - fr.left, m.clientY - fr.top, drawY()); if (!p) return;
        const b = dr.bx;
        if (k.includes("w")) b[0] = Math.min(p.x, b[2]); if (k.includes("e")) b[2] = Math.max(p.x - 1, b[0]);
        if (k.includes("n")) b[1] = Math.min(p.z, b[3]); if (k.includes("s")) b[3] = Math.max(p.z - 1, b[1]);
        preview(); cornersOut();
      };
      let done = false;
      const up = () => {
        if (done) return; done = true; document.body.classList.remove("mapdrag");
        ["pointermove", "pointerup", "pointercancel", "lostpointercapture"].forEach((t) => hnd.removeEventListener(t, t === "pointermove" ? mv : up));
        if (dr && !dr.parentSet) { dr.parent = holder(); const ps = box.querySelector('[data-keep="mrdparent"]'); if (ps) ps.value = dr.parent; }
        changed();
      };
      hnd.addEventListener("pointermove", mv); ["pointerup", "pointercancel", "lostpointercapture"].forEach((t) => hnd.addEventListener(t, up));
    });
  }
  function cornersOut() { // the corner fields follow the handles
    const a = box.querySelector('[data-keep="mrdc1"]'), b = box.querySelector('[data-keep="mrdc2"]');
    if (a && document.activeElement !== a) a.value = dr.bx[0] + ", " + dr.bx[1];
    if (b && document.activeElement !== b) b.value = dr.bx[2] + ", " + dr.bx[3];
  }
  // the form, in place of the list (R6 to R8)
  function drawForm() {
    ft.querySelector(".tt").textContent = "NEW REGION";
    ct.textContent = "";
    ft.title = dr.server + " · " + dimName(dr.dim);
    q.classList.add("hidden"); chipsBox.classList.add("hidden");
    box.classList.add("solid", "mrform");
    rows.textContent = "";
    rows.appendChild(el("div", "mrwhere", dr.server + " · " + dimName(dr.dim)));
    const kinds = el("div", "mrfield"); kinds.appendChild(el("span", "mrlab", "Kind"));
    const kc = el("div", "mrkinds");
    ["protected", "mine", "plot_for_sale", "open_for_buying"].forEach((k) => {
      const c = el("button", "mrchip" + (k === dr.kind ? " pick" : " dimk")); c.type = "button"; c.dataset.keep = "mrdk:" + k;
      const sw = el("i", "mrsw" + (k === "plot_for_sale" ? " dash" : "")); sw.style.background = hex(k); c.appendChild(sw); c.appendChild(document.createTextNode(LABEL[k]));
      if (k === dr.kind) c.style.borderColor = hex(k);
      c.addEventListener("click", () => {
        if (dr.id === nextId(dr.kind) || !dr.id) dr.id = nextId(k);
        if (dr.priority === DEF_PRI[dr.kind]) dr.priority = DEF_PRI[k];
        dr.kind = k; if (dr.phase === 3 && dr.hmode !== "typed") heights(k === "mine" ? "clicked" : "full"); // typed heights kept (audit)
        draw(); changed(); preview();
      });
      kc.appendChild(c);
    });
    kinds.appendChild(kc); rows.appendChild(kinds);
    if (dr.noPlots && (dr.kind === "plot_for_sale" || dr.kind === "open_for_buying")) rows.appendChild(el("div", "note mrnote", "Region Guard refuses plots and open areas in this world: " + (PLOT_RULE[dr.plotRule] || "it has PvP on") + "."));
    if (dr.phase < 3) {
      rows.appendChild(el("div", "note mrnote", dr.phase === 1 ? "Click the map where the first corner goes. Pan and zoom as usual; Esc or CANCEL stops." : "Corner 1 is at " + dr.a.x + ", " + dr.a.z + ". Click the map for the second."));
    } else {
      const grid = el("div", "mrgrid");
      const field = (label, keep, value, set, opts) => {
        const f = el("label", "mrfield"); f.appendChild(el("span", "mrlab", label));
        const i = el("input", "mrin" + (opts && opts.mono ? " mono" : "")); i.type = opts && opts.number ? "number" : "text"; i.value = value; i.dataset.keep = keep;
        if (opts && opts.max) i.maxLength = opts.max;
        i.addEventListener("input", () => { set(i.value); changed(); });
        f.appendChild(i); grid.appendChild(f); return i;
      };
      const whole = (v, keep) => { const n = Math.trunc(Number(v)); return v === "" || !Number.isFinite(n) ? keep : n; };
      field("Id", "mrdid", dr.id, (v) => { dr.id = v.trim().toLowerCase(); }, { mono: true, max: 32 });
      field("Name", "mrdname", dr.name, (v) => { dr.name = v; }, { max: 64 });
      field("Priority", "mrdpri", dr.priority, (v) => { dr.priority = whole(v, dr.priority); }, { mono: true, number: true });
      const pf = el("label", "mrfield"); pf.appendChild(el("span", "mrlab", "Parent"));
      const ps = el("select", "mrin mono"); ps.dataset.keep = "mrdparent";
      const none = el("option", null, "none"); none.value = ""; ps.appendChild(none);
      regionsHere().list.slice().sort((a, b) => a.id.localeCompare(b.id)).forEach((r) => { const o = el("option", null, r.id); o.value = r.id; ps.appendChild(o); });
      ps.value = dr.parent; ps.addEventListener("change", () => { dr.parent = ps.value; dr.parentSet = true; changed(); });
      pf.appendChild(ps); grid.appendChild(pf);
      if (dr.kind === "plot_for_sale") field("Price (coins)", "mrdprice", dr.price, (v) => { dr.price = whole(v, dr.price); }, { mono: true, number: true });
      if (dr.kind === "open_for_buying") {
        field("Cost a block", "mrdcost", dr.cost, (v) => { dr.cost = whole(v, dr.cost); }, { mono: true, number: true });
        field("Smallest side", "mrdmin", dr.min, (v) => { dr.min = whole(v, dr.min); }, { mono: true, number: true });
        field("Largest side", "mrdmax", dr.max, (v) => { dr.max = whole(v, dr.max); }, { mono: true, number: true });
      }
      if (dr.kind === "mine") field("Reset every (minutes)", "mrdevery", dr.every, (v) => { dr.every = Math.max(1, whole(v, dr.every)); }, { mono: true, number: true });
      const c1 = field("Corner 1 (x, z)", "mrdc1", dr.bx[0] + ", " + dr.bx[1], (v) => typedCorner(v, 0), { mono: true });
      const c2 = field("Corner 2 (x, z)", "mrdc2", dr.bx[2] + ", " + dr.bx[3], (v) => typedCorner(v, 2), { mono: true });
      c1.title = c2.title = "Typing moves the outline";
      rows.appendChild(grid);
      if (dr.kind === "mine") {
        const ff = el("label", "mrfield"); ff.appendChild(el("span", "mrlab", "Block mix (block and percent, adding to 100)"));
        const fi = el("input", "mrin mono"); fi.type = "text"; fi.value = dr.fill; fi.dataset.keep = "mrdfill"; fi.addEventListener("input", () => { dr.fill = fi.value; changed(); });
        ff.appendChild(fi); rows.appendChild(ff);
      }
      // the heights (D771): typed, or set from the whole world or the two blocks clicked
      const hf = el("div", "mrfield"); hf.appendChild(el("span", "mrlab", "Height"));
      const hr = el("div", "mrhrow");
      const yIn = (i, keep) => { const n = el("input", "mrin mono"); n.type = "number"; n.value = dr.ys[i]; n.dataset.keep = keep;
        n.addEventListener("input", () => { const v = Math.trunc(Number(n.value)); if (n.value === "" || !Number.isFinite(v)) return; dr.ys[i] = v; dr.hmode = "typed"; hmark(); changed(); }); return n; };
      hr.appendChild(el("span", "mu", "Y")); hr.appendChild(yIn(0, "mrdy1")); hr.appendChild(el("span", "mu", "to")); hr.appendChild(yIn(1, "mrdy2"));
      hf.appendChild(hr);
      const hk = el("div", "mrhkeys");
      const clicked = dr.a.y !== null && dr.b.y !== null;
      [["full", "Full height"], ["clicked", "Clicked blocks"]].forEach(([m, t]) => {
        const k = el("button", "key" + (dr.hmode === m ? " on" : "")); k.type = "button"; k.dataset.keep = "mrdh:" + m; k.textContent = t;
        if (m === "clicked" && !clicked) { k.disabled = true; k.title = "The map here has no ground to click on: type the heights instead."; }
        k.addEventListener("click", () => { heights(m); formSig = ""; draw(); changed(); }); // the Y fields show the new heights
        hk.appendChild(k);
      });
      hk.appendChild(el("span", "mu mrhmode", ""));
      hf.appendChild(hk); rows.appendChild(hf);
      hmark();
    }
    const cl = el("div", "mrcheck hidden"); rows.appendChild(cl); checkLine();
    footer();
  }
  function hmark() {
    const m = box.querySelector(".mrhmode"); if (m) m.textContent = dr.hmode === "typed" ? "typed" : dr.hmode === "clicked" ? "from the blocks clicked" : "the whole world";
    box.querySelectorAll('[data-keep^="mrdh:"]').forEach((k) => k.classList.toggle("on", k.dataset.keep === "mrdh:" + dr.hmode));
  }
  function typedCorner(v, at) {
    const p = String(v).split(/[ ,]+/).filter(Boolean).map(Number);
    if (p.length !== 2 || !p.every(Number.isInteger)) return;
    const other = at === 0 ? [dr.bx[2], dr.bx[3]] : [dr.bx[0], dr.bx[1]];
    dr.bx = [Math.min(p[0], other[0]), Math.min(p[1], other[1]), Math.max(p[0], other[0]), Math.max(p[1], other[1])];
    if (!dr.parentSet) { dr.parent = holder(); const ps = box.querySelector('[data-keep="mrdparent"]'); if (ps) ps.value = dr.parent; } // as a handle does (audit)
    preview();
  }
  function footer() {
    if (!foot) return;
    foot.textContent = "";
    if (!dr) { // the list: NEW REGION (Admins and Owners, on a computer)
      if (!ctx.desk()) { foot.classList.add("hidden"); return; }
      foot.classList.remove("hidden");
      const { page } = regionsHere();
      const n = el("button", "key tlk mrnew", "+ New region"); n.type = "button"; n.dataset.keep = "mrnew";
      if (!admin()) { n.disabled = true; n.appendChild(el("span", "rk ADMIN", "Admin")); }
      else if (!page || !page.ok || !page.live) { n.disabled = true; n.title = "The server shown is not answering for regions."; }
      n.addEventListener("click", startDraw); foot.appendChild(n); return;
    }
    foot.classList.remove("hidden");
    const c = el("button", "key", "Cancel"); c.type = "button"; c.dataset.keep = "mrdcancel"; c.addEventListener("click", stopDraw); foot.appendChild(c);
    foot.appendChild(el("span", "grow"));
    const ok = dr.phase === 3 && dr.check && dr.check.ok && !dr.busy;
    const k = el("button", "key" + (ok ? " tlk" : ""), dr.busy ? "Creating…" : "Create"); k.type = "button"; k.dataset.keep = "mrdcreate"; k.disabled = !ok;
    k.addEventListener("click", create); foot.appendChild(k);
  }
  function dimName(d) {
    d = String(d || "");
    if (d === "minecraft:overworld") return "Overworld";
    if (d === "minecraft:the_nether") return "Nether";
    if (d === "minecraft:the_end") return "End";
    const w = d.replace(/^.*:/, "").replace(/_/g, " ");
    return w.charAt(0).toUpperCase() + w.slice(1);
  }
  /** WORLDS' + NEW REGION ON THE MAP: drawing starts once that map and its regions are up. */
  function drawIn(server, dim) { wantDraw = { server, dim, until: Date.now() + 30000 }; want = null; chosen = null; }
  addEventListener("keydown", (e) => { if (e.key === "Escape" && dr && !(e.target && /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName))) stopDraw(); });

  return { attach, draw, layout, sync, card, barKey, fab, back, closeSheet, frameLoaded, show, drop, active, forget, regionsChanged, solid, drawIn, drawing, stopDraw };
})();
