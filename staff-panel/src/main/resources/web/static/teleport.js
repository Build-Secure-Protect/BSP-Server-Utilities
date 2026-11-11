"use strict";
// Teleports (SU-43; D258, D344 to D349, D364 to D366, D374): TELEPORT in a player's sheet or card opens four tabs, SPAWN,
// WARP (by server, portal warps folded at the end), PLAYER (everyone online by server, with a search) and COORDS (server,
// world, x, y, z filled with where they stand, "on the ground" on their own server only). One tap moves them (/back undoes
// it). GO TO THEM and BRING THEM TO ME at the top when your own account is in game. Every rank.
const Teleport = (() => {
  let openFor = null, tab = "Spawn", places = null, placesAt = 0, loading = false, result = null, busy = false, q = "", portals = false;
  let coords = null; // { player, server, world, x, y, z, ground }
  const dim = (d) => String(d || "").replace(/^minecraft:/, "").replace("the_nether", "nether").replace("the_end", "end");

  async function load() {
    if (loading || (places && Date.now() - placesAt < 30000)) return;
    loading = true;
    places = await call("/api/tp/places", {});
    placesAt = Date.now(); loading = false;
    App.refresh();
  }
  async function move(p, body) {
    if (busy) return;
    busy = true; result = { ok: true, message: "Moving " + p.name + "…" }; App.refresh();
    result = await call("/api/tp", Object.assign({ player: p.id }, body), true);
    busy = false; App.refresh();
  }

  // the TELEPORT key: greyed while they arrive (D366) or where BSP-Teleport does not answer
  function key(p, live) {
    const srv = live.servers.find((s) => s.server === p.server);
    const off = p.arriving ? "arriving on " + p.server : srv && srv.tp === false ? "no BSP-Teleport on " + p.server : "";
    const b = el("button", "key" + (off ? " locked-item" : "") + (openFor === p.id ? " on" : ""), "Teleport"); b.type = "button"; b.dataset.keep = "k:tp";
    if (off) { b.title = off; b.appendChild(el("small", "keynote", off)); }
    b.addEventListener("click", () => {
      if (off) { alert(p.name + ": " + off + "."); return; }
      openFor = openFor === p.id ? null : p.id; result = null; coords = null; q = "";
      if (openFor) load();
      App.refresh();
    });
    return b;
  }

  function row(title, sub, extra, onMove) {
    const r = el("div", "dest");
    const g = el("div", "grow"); const t = el("div", "nm", title); if (extra) t.appendChild(extra); g.appendChild(t); g.appendChild(el("div", "sub mono", sub));
    r.appendChild(g);
    const b = el("button", "go", "MOVE"); b.type = "button"; b.disabled = busy; b.title = "Move to " + title; b.dataset.keep = "move:" + title + "|" + sub;
    b.addEventListener("click", onMove); r.appendChild(b);
    return r;
  }

  function box(p, live) {
    if (openFor !== p.id) return null;
    const w = el("div", "tpbox");
    const self = typeof me !== "undefined" && me ? live.players.find((x) => x.name.toLowerCase() === me.mcName.toLowerCase()) : null;
    if (self && self.id !== p.id) {
      const two = el("div", "two");
      const go = el("button", "btn sm vio", "Go to them"); go.type = "button"; go.dataset.keep = "tp:goto"; go.disabled = busy; go.addEventListener("click", () => move(p, { kind: "goto" }));
      const bring = el("button", "btn sm vio", "Bring them to me"); bring.type = "button"; bring.dataset.keep = "tp:bring"; bring.disabled = busy; bring.addEventListener("click", () => move(p, { kind: "bring" }));
      two.appendChild(go); two.appendChild(bring); w.appendChild(two);
      w.appendChild(el("div", "sub", "You (" + self.name + ") are on " + self.server + "."));
    }
    const tabs = el("div", "tabs4");
    ["Spawn", "Warp", "Player", "Coords"].forEach((t) => { const b = el("button", t === tab ? "on" : "", t); b.type = "button"; b.dataset.keep = "tptab:" + t; b.addEventListener("click", () => { tab = t; App.refresh(); }); tabs.appendChild(b); });
    w.appendChild(tabs);
    if (result) w.appendChild(el("div", result.ok ? "okm" : "err", result.message));
    if (tab === "Player") playerTab(w, p, live);
    else if (tab === "Coords") coordsTab(w, p, live);
    else if (!places) w.appendChild(el("div", "note", "Reading the warps and spawns…"));
    else if (!places.ok) w.appendChild(el("div", "err", places.message));
    else if (tab === "Spawn") {
      if (!places.spawns.length) w.appendChild(el("div", "note", "No server has a /setspawn point."));
      places.spawns.forEach((s) => w.appendChild(row(s.server + " spawn", dim(s.world) + " · " + Math.round(s.x) + ", " + Math.round(s.y) + ", " + Math.round(s.z), null,
        () => move(p, { kind: "spawn", server: s.server }))));
    } else warpTab(w, p);
    return w;
  }

  function warpTab(w, p) {
    const by = new Map();
    places.warps.filter((x) => !x.portal).forEach((x) => { if (!by.has(x.server)) by.set(x.server, []); by.get(x.server).push(x); });
    if (!by.size) w.appendChild(el("div", "note", "No warps."));
    by.forEach((list, srv) => {
      w.appendChild(el("div", "grp", srv));
      list.forEach((x) => {
        const lock = x.restricted ? el("span", "lock", "restricted") : null;
        w.appendChild(row(x.name, dim(x.world) + " · " + Math.round(x.x) + ", " + Math.round(x.y) + ", " + Math.round(x.z), lock, () => move(p, { kind: "warp", warp: x.name })));
      });
    });
    const portal = places.warps.filter((x) => x.portal);
    if (portal.length) { // folded at the end (D344)
      const f = el("button", "grp fold", (portals ? "▾ " : "▸ ") + portal.length + " portal warps"); f.type = "button"; f.dataset.keep = "tp:portals";
      f.addEventListener("click", () => { portals = !portals; App.refresh(); }); w.appendChild(f);
      if (portals) portal.forEach((x) => w.appendChild(row(x.name, x.server + " · " + dim(x.world), null, () => move(p, { kind: "warp", warp: x.name }))));
    }
  }

  // typing in the search draws the list alone, so the box keeps its focus (and a phone its keyboard)
  function playerTab(w, p, live) {
    const s = el("input", "search tpq"); s.type = "search"; s.placeholder = "Find a player"; s.value = q; s.dataset.keep = "tpq";
    const list = el("div", "tplist");
    const fill = () => {
      list.textContent = "";
      const by = new Map();
      live.players.filter((x) => x.id !== p.id && (!q || x.name.toLowerCase().includes(q))).forEach((x) => { if (!by.has(x.server)) by.set(x.server, []); by.get(x.server).push(x); });
      by.forEach((ps, srv) => {
        list.appendChild(el("div", "grp", srv));
        ps.forEach((x) => list.appendChild(row(x.name, dim(x.dim) + " · " + Math.round(x.x) + ", " + Math.round(x.y) + ", " + Math.round(x.z), null,
          () => move(p, { kind: "player", target: x.id }))));
      });
      if (!by.size) list.appendChild(el("div", "note", q ? "Nobody online matches." : "Nobody else is online."));
    };
    s.addEventListener("input", () => { q = s.value.trim().toLowerCase(); fill(); });
    w.appendChild(s); fill(); w.appendChild(list);
  }

  function coordsTab(w, p, live) {
    if (!coords || coords.player !== p.id) coords = { player: p.id, server: p.server, world: p.dim, x: String(Math.floor(p.x)), y: String(Math.floor(p.y)), z: String(Math.floor(p.z)), ground: false };
    const two = el("div", "two");
    const sv = el("select", "tpsel"); sv.dataset.keep = "tp:server"; live.servers.filter((s) => s.tp !== false).forEach((s) => { const o = el("option", null, s.server); o.value = s.server; if (s.server === coords.server) o.selected = true; sv.appendChild(o); });
    sv.addEventListener("change", () => { coords.server = sv.value; if (coords.server !== p.server) coords.ground = false; App.refresh(); });
    const wd = el("select", "tpsel"); wd.dataset.keep = "tp:world"; ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"].concat(coords.world && !coords.world.startsWith("minecraft:") ? [coords.world] : [])
      .forEach((d) => { const o = el("option", null, dim(d)); o.value = d; if (d === coords.world) o.selected = true; wd.appendChild(o); });
    wd.addEventListener("change", () => { coords.world = wd.value; });
    const l1 = el("label", "fld", "Server"); l1.appendChild(sv); const l2 = el("label", "fld", "World"); l2.appendChild(wd); two.appendChild(l1); two.appendChild(l2); w.appendChild(two);
    const three = el("div", "three");
    // a number pad, and a ± key beside each box: an iPhone's pad has no minus (audit)
    ["x", "y", "z"].forEach((k) => {
      const l = el("label", "fld", k); const row = el("div", "tpnumrow");
      const i = el("input", "tpnum tp" + k); i.type = "text"; i.inputMode = "numeric"; i.autocomplete = "off"; i.dataset.keep = "tp:" + k;
      i.value = k === "y" && coords.ground ? "" : coords[k];
      i.placeholder = k === "y" && coords.ground ? "top" : ""; i.disabled = k === "y" && coords.ground;
      i.addEventListener("input", () => { coords[k] = i.value.trim(); });
      const pm = el("button", "pm", "±"); pm.type = "button"; pm.title = "Minus or plus"; pm.disabled = i.disabled; pm.dataset.keep = "tppm:" + k;
      pm.addEventListener("click", () => { const v = i.value.trim(); i.value = v.startsWith("-") ? v.slice(1) : "-" + v; coords[k] = i.value; });
      row.appendChild(i); row.appendChild(pm); l.appendChild(row); three.appendChild(l);
    });
    w.appendChild(three);
    const own = coords.server === p.server;
    const t = el("label", "tick" + (own ? "" : " locked-item")); const cb = el("input"); cb.type = "checkbox"; cb.checked = coords.ground && own; cb.disabled = !own; cb.dataset.keep = "tp:ground";
    cb.addEventListener("change", () => { coords.ground = cb.checked; App.refresh(); });
    t.appendChild(cb); t.appendChild(el("span", null, own ? "On the ground: the top block at x and z (" + p.server + ", their own server)" : "On the ground: only on " + p.name + "'s own server (" + p.server + ")"));
    w.appendChild(t);
    const go = el("button", "btn sm", "Move " + p.name); go.type = "button"; go.disabled = busy; go.dataset.keep = "tp:coordsgo";
    go.addEventListener("click", () => move(p, { kind: "coords", server: coords.server, world: coords.world, x: coords.x, y: coords.y, z: coords.z, ground: coords.ground && own }));
    w.appendChild(go);
  }

  function close() { openFor = null; result = null; coords = null; }
  return { key, box, close };
})();
