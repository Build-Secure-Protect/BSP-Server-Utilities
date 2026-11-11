"use strict";
// The item pictures and English names from an admin's /su icons (SU-41; D271, D340, D373): read once per export (the
// browser keeps the catalog and the sheets), each picture a part of a sheet shown as a background.
const Pictures = (() => {
  let meta = null, cat = null, loading = null;
  const byKey = new Map(), byId = new Map();
  async function load() {
    if (cat) return cat;
    if (loading) return loading;
    loading = (async () => {
      try {
        meta = await api("/api/items/exports");
        if (meta.ok && meta.current) {
          const r = await api("/api/items/catalog?v=" + meta.current.id);
          if (r.ok) {
            cat = r.catalog;
            cat.items.forEach((it, i) => { const k = it.id + "|" + (it.v || ""); if (!byKey.has(k)) byKey.set(k, i); if (!byId.has(it.id)) byId.set(it.id, i); });
          }
        }
      } catch (e) { /* letters and made-up names until the next page load */ }
      loading = null;
      return cat;
    })();
    return loading;
  }
  function indexOf(id, v) { if (!cat) return -1; const k = byKey.get(id + "|" + (v || "")); return k !== undefined ? k : byId.has(id) ? byId.get(id) : -1; }
  function icon(i, size) {
    const d = el("span", "picon");
    if (!cat || i < 0 || i >= cat.items.length) return null;
    const sheet = Math.floor(i / cat.perSheet), cell = i % cat.perSheet, col = cell % cat.perRow, row = Math.floor(cell / cat.perRow);
    d.style.width = d.style.height = size + "px";
    d.style.backgroundImage = "url(/api/items/sheet/" + cat.id + "/" + sheet + ".png)";
    d.style.backgroundSize = cat.perRow * size + "px auto";
    d.style.backgroundPosition = -col * size + "px " + -row * size + "px";
    return d;
  }
  const keyName = (k) => cat && cat.names && cat.names.keys && k ? cat.names.keys[k] : undefined;
  const enchName = (id) => cat && cat.names && cat.names.ench ? cat.names.ench[id] : undefined;
  return { load, ready: () => cat, meta: () => meta, indexOf, icon, keyName, enchName };
})();
// A player's items (SU-39; D256, D279, D336 to D338, D360, D372): laid out as the game's own inventory screen (armour on
// the left, the player's head, Curios and offhand on the right, the 27 slots and the hotbar), the ender chest, backpacks
// and danks folded below. Read from the server holding the player (live), or from their last save when they are offline;
// again every 15 s while the sheet is open for an online player. A tapped slot opens a game-style tooltip: name,
// enchantments, durability, lore, what a shulker box holds. Item pictures come from the export (D340); until then each
// item is a grey tile with two letters (D341) and its name is made from its id.
// Admins, Owners and Moderators given the items power (D335) add with + ADD (an item id and a count until the picker,
// SU-41) and remove from a slot's tooltip, each with a second tap (SURE?); the answer shows under INVENTORY with UNDO for
// 5 minutes (D343), and the changed slots are outlined gold until the next read (D371).
const Items = (() => {
  const cache = new Map(); // player id -> { r: the answer, at: when read, loading }
  const open = new Set(); // folded areas opened: "<player> <area>"
  let tip = null; // { player, area, slot }
  let adding = null; // { player, area, item, count }: the + ADD box open
  let removeCount = null, armed = { key: "", at: 0 }, busy = false;
  const last = new Map(); // player id -> { ok, message, undo, until } (the answer to the newest change)
  const marks = new Map(); // player id -> { area, hash, slot, until }: slots outlined gold (D371)
  const canEdit = () => typeof me !== "undefined" && me && me.itemsPower === true;
  const NOT_YOURS = "Adding and removing items is for Admins, Owners and Moderators given the items power.";
  const sure = (key) => { if (armed.key === key && Date.now() - armed.at < 3000) { armed = { key: "", at: 0 }; return true; } armed = { key, at: Date.now() }; return false; };
  const armedFor = (key) => armed.key === key && Date.now() - armed.at < 3000;
  const REFRESH_MS = 15000;
  const ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"];

  const words = (id) => String(id || "").replace(/^.*:/, "").split(/[_/]/).filter(Boolean).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
  function name(it) {
    if (it.name) return it.name;
    if (it.miss) return words(it.miss);
    if (it.v) { const i = Pictures.indexOf(it.id, it.v); if (i >= 0 && Pictures.ready().items[i].v === it.v) return Pictures.ready().items[i].name; }
    const k = Pictures.keyName(it.key); if (k) return k;
    return words(it.id) + (it.v ? " (" + words(it.v) + ")" : "");
  }
  const enchName = (e) => (Pictures.enchName(e[0]) || words(e[0])) + (e[1] > 1 ? " " + (ROMAN[e[1]] || e[1]) : "");
  function letters(it) {
    const w = words(it.miss || it.id).split(" ");
    return (w.length > 1 ? w[0].charAt(0) + w[1].charAt(0) : w[0].slice(0, 2)).toUpperCase();
  }
  const ago = (ms) => {
    const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 60) return s + " s ago";
    const m = Math.round(s / 60); if (m < 60) return m + " min ago";
    const h = Math.round(m / 60); return h < 48 ? h + " h ago" : Math.round(h / 24) + " days ago";
  };
  function where(area, i) {
    if (area === "inventory") return i < 9 ? "hotbar " + (i + 1) : "inventory slot " + (i - 8);
    if (area === "armor") return ["feet", "legs", "chest", "head"][i] || "armour";
    if (area === "ender_chest") return "ender chest slot " + (i + 1);
    if (area.startsWith("curios")) return "Curios " + words(area.slice(area.indexOf("/") + 1)) + (area.startsWith("curios_cosmetic") ? " (cosmetic)" : "");
    return words(area.split("/")[0]) + " slot " + (i + 1);
  }

  async function fetchFor(p, redraw) {
    const c = cache.get(p.id) || {};
    if (c.loading) return;
    cache.set(p.id, Object.assign(c, { loading: true }));
    try { const r = await api("/api/items", { player: p.id }); cache.set(p.id, { r, at: Date.now() }); }
    catch (e) { cache.set(p.id, { r: { ok: false, message: "The panel could not be reached." }, at: Date.now() }); }
    redraw();
  }

  function slot(it, p, area) {
    const s = el("button", "islot" + (it && it.lk ? " lk" : "") + (it && it.miss ? " miss" : "")); s.type = "button";
    if (!it) { s.disabled = true; return s; }
    if (tip && tip.player === p.id && tip.area === area && tip.slot === it.i) s.classList.add("sel");
    const mk = marks.get(p.id);
    if (mk && mk.until > Date.now() && mk.area === area && (mk.hash ? it.h === mk.hash : mk.slot === it.i)) s.classList.add("chg");
    const pic = it.miss ? null : Pictures.icon(Pictures.indexOf(it.id, it.v), 24); // D341: letters without a picture
    s.appendChild(pic || el("span", "ilt", letters(it)));
    if (it.n > 1) s.appendChild(el("span", "ct", it.n > 9999 ? Math.round(it.n / 1000) + "k" : String(it.n)));
    if (it.ench && it.ench.length) s.classList.add("glint");
    s.title = name(it) + (it.n > 1 ? " × " + it.n : "");
    s.addEventListener("click", () => {
      tip = tip && tip.player === p.id && tip.area === area && tip.slot === it.i ? null : { player: p.id, area, slot: it.i };
      removeCount = null; // each stack starts at ALL (D338, audit)
      p.redraw();
    });
    return s;
  }
  function grid(area, from, to, p, cls) {
    const g = el("div", "igrid" + (cls ? " " + cls : ""));
    const by = new Map((area ? area.slots : []).map((s) => [s.i, s]));
    for (let i = from; i < to; i++) g.appendChild(slot(by.get(i), p, area ? area.id : ""));
    return g;
  }
  function tooltip(it, areaId, p, area) {
    const t = el("div", "mctip");
    t.appendChild(el("div", it.ench && it.ench.length ? "n ench" : "n", name(it) + (it.n > 1 ? " × " + it.n : "")));
    (it.ench || []).forEach((e) => t.appendChild(el("div", "g", enchName(e))));
    if (it.max) t.appendChild(el("div", "g", "Durability: " + (it.max - it.dmg) + " / " + it.max));
    (it.lore || []).forEach((l) => t.appendChild(el("div", "lo", l)));
    if (it.in && it.in.length) {
      t.appendChild(el("div", "g", "Holds:"));
      it.in.forEach((x) => t.appendChild(el("div", "w", x.n + " × " + name(x))));
    }
    if (it.lk) t.appendChild(el("div", "gd", "Locked: a live Shatter Totem is in it. The panel never moves it."));
    if (it.miss) t.appendChild(el("div", "gd", "Its mod is missing on the server that read it: " + it.miss));
    t.appendChild(el("div", "bl", (it.miss || it.id) + " · " + where(areaId, it.i)));
    if (p && area && area.rem && !it.lk) t.appendChild(removeRow(p, area, it));
    return t;
  }
  function tipFor(p, area) {
    if (!tip || tip.player !== p.id || !area || tip.area !== area.id) return null;
    const it = area.slots.find((s) => s.i === tip.slot);
    return it ? tooltip(it, area.id, p, area) : null;
  }

  // ---------------------------------------------------------------- changing items (SU-40)
  async function change(p, path, body) {
    if (busy) return;
    busy = true;
    let r;
    try { r = await api(path, body); } catch (e) { r = { ok: false, message: "The panel could not be reached." }; }
    busy = false;
    last.set(p.id, { ok: r.ok, message: r.message || (r.ok ? "Done." : "Not done."), undo: r.undo, until: r.until });
    if (r.ok && path.endsWith("/add")) marks.set(p.id, { area: body.area, hash: r.hash, until: Date.now() + REFRESH_MS + 5000 });
    if (r.ok && path.endsWith("/remove")) marks.set(p.id, { area: body.area, slot: body.slot, until: Date.now() + REFRESH_MS + 5000 });
    if (r.ok) { adding = null; tip = null; removeCount = null; }
    const c = cache.get(p.id); if (c) c.at = 0; // read again now
    fetchFor(p, p.redraw);
  }
  function removeRow(p, area, it) {
    const box = el("div", "acts");
    if (!canEdit()) { const b = el("button", "btn sm ghost locked-item", "Remove · Admin or ITEMS"); b.type = "button"; b.addEventListener("click", () => alert(NOT_YOURS)); box.appendChild(b); return box; }
    const n = Math.max(1, Math.min(it.n, removeCount === null ? it.n : removeCount));
    if (it.n > 1) {
      const row = el("div", "step");
      [1, Math.ceil(it.n / 2), it.n].filter((v, i, a) => a.indexOf(v) === i).forEach((v) => {
        const c = el("button", v === n ? "on" : "", v === it.n ? "ALL " + v : String(v)); c.type = "button";
        c.addEventListener("click", () => { removeCount = v; p.redraw(); }); row.appendChild(c);
      });
      box.appendChild(row);
    }
    const key = "rm " + p.id + " " + area.id + " " + it.i + " " + n;
    const go = el("button", "btn sm " + (armedFor(key) ? "sure" : "danger"), armedFor(key) ? "Sure? Remove " + n + " " + name(it) : "Remove " + n); go.type = "button";
    go.addEventListener("click", () => {
      if (!sure(key)) { p.redraw(); return; }
      change(p, "/api/items/remove", { player: p.id, area: area.id, slot: it.i, hash: it.h, count: n });
    });
    box.appendChild(go);
    return box;
  }
  function addButton(p, area, label) {
    const b = el("button", "plus" + (canEdit() ? "" : " off"), label || "+ ADD"); b.type = "button";
    if (!canEdit()) b.title = "Admin or ITEMS";
    b.addEventListener("click", () => {
      if (!canEdit()) { alert(NOT_YOURS); return; }
      adding = adding && adding.player === p.id && adding.area === area.id ? null : { player: p.id, area: area.id, item: "", count: 1 };
      p.redraw();
    });
    return b;
  }
  // the picker (D271, D360): the creative inventory, tabs along the top, a search box (name, or @mod), slots
  function picker(p, area, version) {
    const cat = Pictures.ready();
    const f = el("div", "addbox picker");
    f.appendChild(el("div", "ilab2", "ADD TO " + p.name.toUpperCase() + " · " + where(area.id, 0).replace(/ slot 1$/, "").replace(/hotbar 1/, "inventory").toUpperCase()));
    if (adding.tab === undefined) { adding.tab = 0; adding.q = ""; adding.sel = -1; }
    const tabs = el("div", "ptabs2");
    cat.tabs.forEach((t, i) => {
      const b = el("button", "ptab" + (i === adding.tab && !adding.q ? " on" : "")); b.type = "button"; b.title = t.name;
      const ic = Pictures.icon(t.icon, 20); if (ic) b.appendChild(ic); else b.textContent = t.name.slice(0, 2);
      b.addEventListener("click", () => { adding.tab = i; adding.q = ""; p.redraw(); });
      tabs.appendChild(b);
    });
    f.appendChild(tabs);
    const q = el("input", "pickq"); q.type = "search"; q.placeholder = "Search " + cat.items.length + " items, or @mod"; q.value = adding.q;
    q.autocapitalize = "off"; q.spellcheck = false;
    q.addEventListener("input", () => { adding.q = q.value; clearTimeout(adding.timer); adding.timer = setTimeout(() => p.redraw(), 200); });
    f.appendChild(q);
    let list;
    const words2 = adding.q.trim().toLowerCase();
    if (words2) {
      let mod = null, text = words2;
      if (text.startsWith("@")) { const sp = text.indexOf(" "); mod = (sp < 0 ? text.slice(1) : text.slice(1, sp)); text = sp < 0 ? "" : text.slice(sp + 1).trim(); }
      list = [];
      for (let i = 0; i < cat.items.length && list.length < 270; i++) {
        const it = cat.items[i], ns = it.id.slice(0, it.id.indexOf(":"));
        if (mod && !ns.startsWith(mod)) continue;
        if (text && !(it.name || "").toLowerCase().includes(text) && !it.id.includes(text)) continue;
        list.push(i);
      }
      f.appendChild(el("div", "ilab2", list.length >= 270 ? "the first 270 found" : list.length + " found"));
    } else {
      const t = cat.tabs[adding.tab] || cat.tabs[0];
      list = t ? t.items : [];
      f.appendChild(el("div", "ilab2", t ? t.name.toUpperCase() + " · " + list.length : ""));
    }
    const g = el("div", "igrid pgrid");
    list.forEach((i) => {
      const b = el("button", "islot" + (i === adding.sel ? " sel" : "")); b.type = "button"; b.title = cat.items[i].name;
      const ic = Pictures.icon(i, 24); b.appendChild(ic || el("span", "ilt", letters(cat.items[i])));
      b.addEventListener("click", () => { adding.sel = i; p.redraw(); });
      g.appendChild(b);
    });
    f.appendChild(g);
    if (adding.sel >= 0) {
      const it = cat.items[adding.sel];
      const r = el("div", "pickrow"); const ic = Pictures.icon(adding.sel, 32); if (ic) r.appendChild(ic);
      const t = el("div", "grow"); t.appendChild(el("div", "nm", it.name)); t.appendChild(el("div", "sub mono", it.id)); r.appendChild(t); f.appendChild(r);
      f.appendChild(countRow(p));
      const key = "add " + p.id + " " + area.id + " " + adding.sel;
      const go = el("button", "btn sm" + (armedFor(key) ? " sure" : ""), armedFor(key) ? "Sure? Add " + adding.count + " " + it.name : "Add " + adding.count + " " + it.name); go.type = "button";
      go.addEventListener("click", () => {
        if (!sure(key)) { p.redraw(); return; }
        change(p, "/api/items/add", { player: p.id, area: area.id, item: it.id, snbt: it.nbt || "", count: adding.count, version });
      });
      f.appendChild(go);
    }
    return f;
  }
  function countRow(p) {
    const row = el("div", "step");
    [1, 16, 32, 64].forEach((v) => { const c = el("button", adding.count === v ? "on" : "", String(v)); c.type = "button"; c.addEventListener("click", () => { adding.count = v; p.redraw(); }); row.appendChild(c); });
    const n = el("input", "addcount"); n.type = "number"; n.min = 1; n.max = 6400; n.value = adding.count; n.title = "Up to 100 stacks";
    n.addEventListener("input", () => { adding.count = Math.max(1, Math.min(6400, Number(n.value) || 1)); });
    row.appendChild(n);
    return row;
  }
  // + ADD (D339): the picker once the pictures are there; until then an item id and a count
  function addForm(p, area, version) {
    if (Pictures.ready()) return picker(p, area, version);
    const f = el("div", "addbox");
    f.appendChild(el("div", "ilab2", "ADD TO " + p.name.toUpperCase() + " · " + where(area.id, 0).replace(/ slot 1$/, "").replace(/hotbar 1/, "inventory").toUpperCase()));
    const i = el("input", "additem"); i.type = "text"; i.maxLength = 160; i.placeholder = "Item id: minecraft:diamond, create:brass_ingot"; i.value = adding.item;
    i.autocapitalize = "off"; i.spellcheck = false;
    i.addEventListener("input", () => { adding.item = i.value; });
    f.appendChild(i);
    f.appendChild(countRow(p));
    f.appendChild(el("div", "note", "No item pictures yet: an Admin types /su icons in their own game, and this becomes the creative picker."));
    const key = "add " + p.id + " " + area.id;
    const go = el("button", "btn sm" + (armedFor(key) ? " sure" : ""), armedFor(key) ? "Sure? Add " + adding.count + " " + words(adding.item) : "Add"); go.type = "button";
    go.addEventListener("click", () => {
      if (!adding.item.trim()) { i.focus(); return; }
      if (!sure(key)) { p.redraw(); return; }
      change(p, "/api/items/add", { player: p.id, area: area.id, item: adding.item.trim(), count: adding.count, version });
    });
    f.appendChild(go);
    return f;
  }
  function answerLine(p) {
    const a = last.get(p.id);
    if (!a) return null;
    const line = el("div", a.ok ? "okm" : "err", a.message);
    const left = a.undo ? a.until - Date.now() : 0;
    if (left > 0) {
      const u = el("button", "undo", "UNDO " + Math.floor(left / 60000) + ":" + String(Math.floor(left / 1000) % 60).padStart(2, "0")); u.type = "button";
      u.addEventListener("click", () => change(p, "/api/items/undo", { token: a.undo }));
      line.appendChild(u);
    }
    return line;
  }

  function placeText(a) {
    const pl = a.place;
    if (!pl) return "";
    let s = pl.area === "inventory" ? where("inventory", pl.slot) : pl.area === "ender_chest" ? "in the ender chest" : pl.area.startsWith("curios") ? "on " + where(pl.area, pl.slot)
      : pl.area.startsWith("backpack") ? "inside another backpack" : pl.area === "cursor" ? "on the cursor" : words(pl.area);
    if (pl.inside && pl.inside.length) s += ", inside " + pl.inside.map(words).join(" in ");
    return s;
  }

  // the section in a player's sheet or card, or the offline player's page (Find a player)
  function section(p, opts) {
    p = Object.assign({}, p, { redraw: opts.redraw });
    const wrap = el("div", "items");
    const c = cache.get(p.id);
    const stale = !c || (opts.live && !c.loading && Date.now() - c.at > REFRESH_MS);
    if (stale) fetchFor(p, opts.redraw);
    const head = el("div", "sect"); head.appendChild(el("span", null, "INVENTORY"));
    const right = el("span", "ihead");
    const r = c && c.r;
    if (r && r.ok && r.items) {
      const it = r.items;
      if (it.live) right.appendChild(el("span", "tl", "●"));
      right.appendChild(el("span", it.live ? "live" : "saved", it.live ? "live · " + ago(c.at) : "saved " + ago(it.savedAt) + (it.server ? " · " + it.server : "")));
    }
    const again = el("button", "iref", "↻"); again.type = "button"; again.title = "Read again";
    again.addEventListener("click", () => { cache.set(p.id, Object.assign(cache.get(p.id) || {}, { at: 0 })); fetchFor(p, opts.redraw); });
    right.appendChild(again);
    const inv0 = r && r.ok && r.items ? (r.items.areas || []).find((x) => x.id === "inventory") : null;
    if (inv0 && inv0.add) right.appendChild(addButton(p, inv0));
    head.appendChild(right); wrap.appendChild(head);
    const ans = answerLine(p); if (ans) wrap.appendChild(ans);
    if (!r) { wrap.appendChild(el("div", "note", "Reading " + p.name + "'s items…")); return wrap; }
    if (!r.ok || !r.items) { wrap.appendChild(el("div", "note", r.message || "The items could not be read.")); return wrap; }
    const areas = r.items.areas || [], by = (id) => areas.find((a) => a.id === id);
    if (!r.items.live) wrap.appendChild(el("div", "note2", "Changes reach " + p.name + " at their next login, on any server."));
    if (adding && adding.player === p.id && adding.area === "inventory" && by("inventory")) wrap.appendChild(addForm(p, by("inventory"), r.items.version));
    // armour, the head, Curios and the offhand, as the game's own screen
    const eq = el("div", "iequip");
    const arm = el("div", "icol"); const armor = by("armor"); [3, 2, 1, 0].forEach((i) => arm.appendChild(grid(armor, i, i + 1, p, "one").firstChild)); eq.appendChild(arm);
    const fig = el("div", "ifig"); fig.appendChild(typeof Players !== "undefined" ? Players.face(p, 56) : el("span")); eq.appendChild(fig);
    const cur = el("div", "icol"); cur.appendChild(el("span", "ilab", "CURIOS"));
    const curios = areas.filter((a) => a.kind === "CURIOS");
    let shown = 0;
    curios.forEach((a) => a.slots.forEach((s) => { if (shown < 3) { cur.appendChild(slot(s, p, a.id)); shown++; } }));
    while (shown < 3) { cur.appendChild(slot(null, p, "")); shown++; }
    eq.appendChild(cur);
    const off = el("div", "icol end"); off.appendChild(el("span", "ilab", "OFFHAND")); off.appendChild(grid(by("offhand"), 0, 1, p, "one").firstChild); eq.appendChild(off);
    wrap.appendChild(eq);
    const tops = [armor, by("offhand"), ...curios].map((a) => tipFor(p, a)).find(Boolean); if (tops) wrap.appendChild(tops);
    const inv = by("inventory");
    wrap.appendChild(grid(inv, 9, 36, p)); wrap.appendChild(grid(inv, 0, 9, p, "hot"));
    const t = tipFor(p, inv); if (t) wrap.appendChild(t);
    const more = curios.reduce((n, a) => n + a.slots.length, 0) - 3;
    if (more > 0) wrap.appendChild(fold(p, "curios", "CURIOS", "all " + curios.reduce((n, a) => n + a.size, 0) + " slots", () => {
      const box = el("div");
      curios.forEach((a) => { box.appendChild(el("div", "ilab2", where(a.id, 0))); box.appendChild(grid(a, 0, a.size, p)); const tt = tipFor(p, a); if (tt) box.appendChild(tt); });
      return box;
    }));
    const cursor = by("cursor"); if (cursor && cursor.slots.length) { wrap.appendChild(el("div", "ilab2", "ON THE CURSOR (a screen was open)")); wrap.appendChild(grid(cursor, 0, 1, p, "one")); const ct = tipFor(p, cursor); if (ct) wrap.appendChild(ct); }
    const ender = by("ender_chest");
    const addIn = (b, a) => { if (!a.add) return; const row = el("div", "addrow"); row.appendChild(addButton(p, a)); b.appendChild(row); if (adding && adding.player === p.id && adding.area === a.id) b.appendChild(addForm(p, a, r.items.version)); };
    if (ender) wrap.appendChild(fold(p, "ender_chest", "ENDER CHEST", ender.slots.length + " / " + ender.size, () => { const b = el("div"); addIn(b, ender); b.appendChild(grid(ender, 0, ender.size, p)); const tt = tipFor(p, ender); if (tt) b.appendChild(tt); return b; }));
    areas.filter((a) => a.kind === "BACKPACK" || a.kind === "DANK").forEach((a) => {
      const kind = a.kind === "BACKPACK" ? "BACKPACK" : "DANK";
      const note = !a.rem ? "read only (linked)" : !a.add && !r.items.live ? "removals only while offline" : "";
      wrap.appendChild(fold(p, a.id, kind, [placeText(a), a.slots.length + " / " + a.size, note].filter(Boolean).join(" · "), () => {
        const b = el("div"); if (!a.size) { b.appendChild(el("div", "note", "Never opened: its contents do not exist yet.")); return b; }
        addIn(b, a); b.appendChild(grid(a, 0, a.size, p)); const tt = tipFor(p, a); if (tt) b.appendChild(tt); return b;
      }));
    });
    return wrap;
  }
  function fold(p, key, title, sub, body) {
    const k = p.id + " " + key, isOpen = open.has(k);
    const f = el("div", "ifold");
    const h = el("button", "ifh"); h.type = "button";
    h.appendChild(el("b", null, title)); h.appendChild(el("span", "mu", sub + (isOpen ? " ⌃" : " ⌄")));
    h.addEventListener("click", () => { isOpen ? open.delete(k) : open.add(k); p.redraw(); });
    f.appendChild(h);
    if (isOpen) f.appendChild(body());
    return f;
  }
  function forget(id) { cache.delete(id); if (tip && tip.player === id) tip = null; }
  // the hotbar alone, from the same read as the section (the map's card, SU-47; D390)
  function hotbar(p, opts) {
    p = Object.assign({}, p, { redraw: opts.redraw });
    const c = cache.get(p.id);
    if (!c || (!c.loading && Date.now() - c.at > REFRESH_MS)) fetchFor(p, opts.redraw);
    const r = c && c.r, inv = r && r.ok && r.items ? (r.items.areas || []).find((x) => x.id === "inventory") : null;
    const wrap = el("div", "items");
    wrap.appendChild(grid(inv, 0, 9, p, "hot"));
    const t = tipFor(p, inv); if (t) wrap.appendChild(t);
    if (r && !r.ok) wrap.appendChild(el("div", "note", r.message));
    return wrap;
  }

  return { section, hotbar, forget, name, icon: (it, size) => Pictures.icon(Pictures.indexOf(it.id, it.v), size), letters, describe: (it, areaId) => tooltip(it, areaId) };
})();

// More › Find a player (D336, D372): anyone who ever joined, by the start of their name. An online player opens their
// sheet on the Players tab; an offline one opens here, with their items from the last save, graves and notes.
const FindPlayer = (() => {
  const st = { q: "", found: [], chosen: null, timer: 0 };
  async function search(q) {
    st.q = q;
    if (!q) { st.found = []; App.refreshMore(); return; }
    try { const r = await api("/api/players/find", { q }); if (st.q === q) st.found = r.ok ? r.players : []; } catch (e) { st.found = []; }
    if (st.q === q) App.refreshMore();
  }
  function view(main) {
    if (st.chosen) { offline(main, st.chosen); return; }
    main.appendChild(el("h2", null, "Find a player"));
    const q = el("input", "search findq"); q.type = "search"; q.placeholder = "The start of a name, online or not"; q.value = st.q; q.maxLength = 16;
    q.addEventListener("input", () => { clearTimeout(st.timer); const v = q.value.trim(); st.timer = setTimeout(() => search(v), 250); });
    main.appendChild(q);
    const list = el("div", "list");
    st.found.forEach((p) => {
      const b = el("button", "item menuitem"); b.type = "button";
      const g = el("div", "grow"); g.appendChild(el("div", "nm", p.name)); g.appendChild(Tags.chips(p.id, 2));
      g.appendChild(el("div", "sub", p.server ? "online on " + p.server : "offline"));
      b.appendChild(g); b.appendChild(el("span", "tl", "OPEN"));
      b.addEventListener("click", () => { if (p.server) openPlayerSheet(p.id); else { st.chosen = p; App.refreshMore(); } });
      list.appendChild(b);
    });
    if (st.q && !st.found.length) list.appendChild(el("div", "note", "Nobody whose name starts with “" + st.q + "” has played on the network."));
    main.appendChild(list);
    main.appendChild(el("div", "note", "Everyone who has joined the network. An online player opens on the Players tab; an offline one here, with their items from their last save."));
    if (document.activeElement === document.body) q.focus();
  }
  function offline(main, p) {
    const who = el("div", "offwho"); const g = el("div", "grow");
    g.appendChild(el("div", "dname", p.name)); g.appendChild(Tags.chips(p.id));
    g.appendChild(el("div", "sub", "offline"));
    who.appendChild(Players.face(p, 44)); who.appendChild(g); main.appendChild(who);
    const keys = el("div", "keys"); const gr = el("button", "key", "Graves"); gr.type = "button"; gr.addEventListener("click", () => { st.chosen = null; openGraves(p.name); });
    keys.appendChild(gr); main.appendChild(keys);
    const box = el("div", "offsheet");
    const ml = Market.line(p); // D405: their lots and orders, when they have any
    if (ml) box.appendChild(ml);
    box.appendChild(Items.section(p, { live: false, redraw: () => App.refreshMore() }));
    box.appendChild(Notes.section(p));
    main.appendChild(box);
  }
  return { view, reset: () => { st.chosen = null; }, chosen: () => st.chosen, open: (p) => { st.chosen = p; } };
})();

// More › Items (D373): the item pictures the panel has, who made them and when, and how to make a fresh set.
const ItemsPage = (() => {
  let data = null;
  async function view(main) {
    main.appendChild(el("h2", null, "Item pictures"));
    if (!data) { try { data = await api("/api/items/exports"); } catch (e) { data = { ok: false }; } }
    const d = data, cur = d && d.current;
    const when = (at) => new Date(at).toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
    if (d && d.problem) main.appendChild(el("div", "err", d.problem));
    if (!cur) main.appendChild(el("div", "note", "No item pictures yet. Items show as grey tiles with two letters, and + ADD asks for an item id."));
    else {
      const kv = el("div", "kvl");
      [["Items", cur.items + " in " + cur.tabs + " tabs"], ["Made by", cur.by + ", in game on " + cur.server], ["When", when(cur.at)], ["Mods", cur.mods + " (the pack as it was then)"],
        ["Size", cur.size >= 1048576 ? (cur.size / 1048576).toFixed(1) + " MB" : Math.max(1, Math.round(cur.size / 1024)) + " KB"]].forEach(([k, v]) => { kv.appendChild(el("span", null, k)); kv.appendChild(el("span", null, v)); });
      main.appendChild(kv);
      const cat = await Pictures.load();
      if (cat) { const row = el("div", "psample"); for (let i = 0; i < Math.min(27, cat.items.length); i++) { const ic = Pictures.icon(i, 30); if (ic) row.appendChild(ic); } main.appendChild(row); }
    }
    main.appendChild(el("div", "sect", "A FRESH EXPORT"));
    main.appendChild(el("div", "note", "After a pack update an Admin or Owner types /su icons in their own game (the full pack, on a computer). Their game draws every item, then sends the pictures through the server it is on; the panel picks them up within a minute. Items missing from the export show their first two letters."));
    if (d && d.list && d.list.length) {
      main.appendChild(el("div", "sect", "LAST EXPORTS"));
      const kv = el("div", "kvl"); d.list.forEach((x) => { kv.appendChild(el("span", null, when(x.at))); kv.appendChild(el("span", null, x.by + " · " + x.items + " items")); }); main.appendChild(kv);
    }
  }
  return { view, reset: () => { data = null; } };
})();
