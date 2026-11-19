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

  // A read of their items. quiet: a read the page asked for by itself (every 15 s): drawn once nothing in the sheet is
  // being typed in, so a search or a count being typed is never taken away.
  async function fetchFor(p, redraw, quiet) {
    const c = cache.get(p.id) || {};
    if (c.loading) return;
    cache.set(p.id, Object.assign(c, { loading: true }));
    try { const r = await api("/api/items", { player: p.id }); cache.set(p.id, { r, at: Date.now() }); }
    catch (e) { cache.set(p.id, { r: { ok: false, message: failText(e) }, at: Date.now() }); }
    if (!quiet) { redraw(); return; }
    const a = document.activeElement;
    whenFree(a && a.closest ? a.closest("#pdetail, .offsheet, .mcard") : null, redraw);
  }
  /** The live feed's turn: read an online player's items again when 15 s have gone by (D337). */
  function poll(p, redraw) {
    const c = cache.get(p.id);
    if (!c || (!c.loading && Date.now() - c.at > REFRESH_MS)) fetchFor(Object.assign({}, p, { redraw }), redraw, true);
  }

  function slot(it, p, area) {
    const s = el("button", "islot" + (it && it.lk ? " lk" : "") + (it && it.miss ? " miss" : "")); s.type = "button";
    if (!it) { s.disabled = true; return s; }
    s.dataset.keep = "slot:" + area + ":" + it.i;
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
    const r = await call(path, body, true);
    busy = false;
    last.set(p.id, { ok: r.ok, message: r.message || (r.ok ? "Done." : "Not done."), undo: r.undo, until: r.until });
    if (r.ok && path.endsWith("/add")) marks.set(p.id, { area: body.area, hash: r.hash, until: Date.now() + REFRESH_MS + 5000 });
    if (r.ok && path.endsWith("/remove")) marks.set(p.id, { area: body.area, slot: body.slot, until: Date.now() + REFRESH_MS + 5000 });
    if (r.ok) { adding = null; tip = null; removeCount = null; }
    const c = cache.get(p.id); if (c) c.at = 0; // read again now
    fetchFor(p, p.redraw);
  }
  // a key whose second press within 3 s does it: its words change in place, so focus stays on it (D338, D339)
  function sureKey(b, key, plain, sureText, plainCls, act, ready) {
    const left = armedFor(key) ? 3000 - (Date.now() - armed.at) : 0;
    b.textContent = left > 0 ? sureText() : plain(); b.className = left > 0 ? "btn sm sure" : plainCls;
    unarm(b, left, plain(), plainCls);
    b.addEventListener("click", () => {
      if (busy) return;
      if (ready && !ready()) return;
      if (!sure(key)) { b.textContent = sureText(); b.className = "btn sm sure"; unarm(b, 3000, plain(), plainCls); return; }
      b.disabled = true; act();
    });
  }
  function removeRow(p, area, it) {
    const box = el("div", "acts");
    if (!canEdit()) { const b = el("button", "btn sm ghost locked-item", "Remove · Admin or ITEMS"); b.type = "button"; b.addEventListener("click", () => alert(NOT_YOURS)); box.appendChild(b); return box; }
    const n = Math.max(1, Math.min(it.n, removeCount === null ? it.n : removeCount));
    if (it.n > 1) {
      const row = el("div", "step");
      [1, Math.ceil(it.n / 2), it.n].filter((v, i, a) => a.indexOf(v) === i).forEach((v) => {
        const c = el("button", v === n ? "on" : "", v === it.n ? "ALL " + v : String(v)); c.type = "button"; c.dataset.keep = "rmn:" + v;
        c.addEventListener("click", () => { removeCount = v; p.redraw(); }); row.appendChild(c);
      });
      box.appendChild(row);
    }
    const key = "rm " + p.id + " " + area.id + " " + it.i + " " + n;
    const go = el("button"); go.type = "button"; go.dataset.keep = "rmgo";
    sureKey(go, key, () => "Remove " + n, () => "Sure? Remove " + n + " " + name(it), "btn sm danger",
      () => change(p, "/api/items/remove", { player: p.id, area: area.id, slot: it.i, hash: it.h, count: n }));
    box.appendChild(go);
    return box;
  }
  function addButton(p, area, label) {
    const can = canEdit();
    const b = el("button", "plus" + (can ? "" : " off"), (label || "+ ADD") + (can ? "" : " · Admin or ITEMS")); b.type = "button"; b.dataset.keep = "iadd:" + area.id; // D278, D371
    b.addEventListener("click", () => {
      if (!canEdit()) { alert(NOT_YOURS); return; }
      adding = adding && adding.player === p.id && adding.area === area.id ? null : { player: p.id, area: area.id, item: "", count: 1 };
      p.redraw();
    });
    return b;
  }
  // the picker (D271, D360): the creative inventory, tabs along the top, a search box (name, or @mod), slots. Choosing a
  // tab, searching and picking an item change the picker in place; a tab's items are drawn a screenful at a time.
  const PAGE = 180;
  function picker(p, area, version) {
    const cat = Pictures.ready();
    const f = el("div", "addbox picker");
    f.appendChild(el("div", "ilab2", "ADD TO " + p.name.toUpperCase() + " · " + where(area.id, 0).replace(/ slot 1$/, "").replace(/hotbar 1/, "inventory").toUpperCase()));
    if (adding.tab === undefined) { adding.tab = 0; adding.q = ""; adding.sel = -1; adding.shown = PAGE; }
    const tabs = el("div", "ptabs2"); tabs.dataset.scroll = "ptabs";
    const tabKeys = cat.tabs.map((t, i) => {
      const b = el("button", "ptab"); b.type = "button"; b.title = t.name; b.dataset.keep = "ptab:" + i;
      const ic = Pictures.icon(t.icon, 20); if (ic) b.appendChild(ic); else b.textContent = t.name.slice(0, 2);
      b.addEventListener("click", () => { adding.tab = i; adding.q = ""; q.value = ""; adding.shown = PAGE; fill(); g.scrollTop = 0; });
      tabs.appendChild(b); return b;
    });
    f.appendChild(tabs);
    const q = el("input", "pickq"); q.type = "search"; q.placeholder = "Search " + cat.items.length + " items, or @mod"; q.value = adding.q; q.dataset.keep = "pickq";
    q.autocapitalize = "off"; q.spellcheck = false;
    q.addEventListener("input", () => { adding.q = q.value; clearTimeout(adding.timer); adding.timer = setTimeout(() => { adding.shown = PAGE; fill(); g.scrollTop = 0; }, 200); });
    f.appendChild(q);
    const lab = el("div", "ilab2"), g = el("div", "igrid pgrid"), more = el("div", "pmore"), below = el("div", "pickbelow");
    g.dataset.scroll = "pgrid";
    f.appendChild(lab); f.appendChild(g); f.appendChild(more); f.appendChild(below);
    function list() {
      const words2 = adding.q.trim().toLowerCase();
      if (!words2) { const t = cat.tabs[adding.tab] || cat.tabs[0]; return { items: t ? t.items : [], label: t ? t.name.toUpperCase() + " · " + (t.items || []).length : "" }; }
      let mod = null, text = words2;
      if (text.startsWith("@")) { const sp = text.indexOf(" "); mod = (sp < 0 ? text.slice(1) : text.slice(1, sp)); text = sp < 0 ? "" : text.slice(sp + 1).trim(); }
      const out = [];
      for (let i = 0; i < cat.items.length; i++) {
        const it = cat.items[i], ns = it.id.slice(0, it.id.indexOf(":"));
        if (mod && !ns.startsWith(mod)) continue;
        if (text && !(it.name || "").toLowerCase().includes(text) && !it.id.includes(text)) continue;
        out.push(i);
      }
      return { items: out, label: out.length + " found" };
    }
    function itemKey(i) {
      const b = el("button", "islot" + (i === adding.sel ? " sel" : "")); b.type = "button"; b.title = cat.items[i].name; b.dataset.keep = "pick:" + i;
      const ic = Pictures.icon(i, 24); b.appendChild(ic || el("span", "ilt", letters(cat.items[i])));
      b.addEventListener("click", () => {
        adding.sel = i;
        g.querySelectorAll(".islot.sel").forEach((x) => x.classList.remove("sel")); b.classList.add("sel");
        fillBelow();
      });
      return b;
    }
    function fill() {
      const l = list();
      tabKeys.forEach((b, i) => b.classList.toggle("on", i === adding.tab && !adding.q.trim()));
      lab.textContent = l.label;
      g.textContent = ""; more.textContent = "";
      l.items.slice(0, adding.shown).forEach((i) => g.appendChild(itemKey(i)));
      const left = l.items.length - adding.shown;
      if (left > 0) {
        const m = el("button", "btn sm ghost", "Show " + Math.min(PAGE, left) + " more (" + left + " not shown)"); m.type = "button"; m.dataset.keep = "pmore";
        m.addEventListener("click", () => {
          const from = adding.shown; adding.shown += PAGE;
          l.items.slice(from, adding.shown).forEach((i) => g.appendChild(itemKey(i)));
          const rest = l.items.length - adding.shown;
          if (rest > 0) m.textContent = "Show " + Math.min(PAGE, rest) + " more (" + rest + " not shown)"; else { m.remove(); }
        });
        more.appendChild(m);
      }
    }
    function fillBelow() {
      below.textContent = "";
      if (adding.sel < 0) return;
      const it = cat.items[adding.sel];
      const r = el("div", "pickrow"); const ic = Pictures.icon(adding.sel, 32); if (ic) r.appendChild(ic);
      const t = el("div", "grow"); t.appendChild(el("div", "nm", it.name)); t.appendChild(el("div", "sub mono", it.id)); r.appendChild(t); below.appendChild(r);
      const go = el("button"); go.type = "button"; go.dataset.keep = "addgo";
      const plain = () => "Add " + adding.count + " " + it.name, sure2 = () => "Sure? Add " + adding.count + " " + it.name;
      const key = "add " + p.id + " " + area.id + " " + adding.sel;
      below.appendChild(countRow(() => { go.textContent = armedFor(key) ? sure2() : plain(); }));
      sureKey(go, key, plain, sure2, "btn sm",
        () => change(p, "/api/items/add", { player: p.id, area: area.id, item: it.id, snbt: it.nbt || "", count: adding.count, version }));
      below.appendChild(go);
    }
    fill(); fillBelow();
    return f;
  }
  // 1, 16, 32, 64 or typed, up to 100 stacks (D339); changed in place
  function countRow(changed) {
    const row = el("div", "step"), chips = [];
    [1, 16, 32, 64].forEach((v) => {
      const c = el("button", adding.count === v ? "on" : "", String(v)); c.type = "button"; c.dataset.keep = "cnt:" + v; chips.push([v, c]);
      c.addEventListener("click", () => { adding.count = v; n.value = String(v); mark(); changed(); }); row.appendChild(c);
    });
    const n = el("input", "addcount"); n.type = "text"; n.inputMode = "numeric"; n.pattern = "[0-9]*"; n.maxLength = 4; n.value = adding.count; n.title = "Up to 100 stacks"; n.dataset.keep = "addcount";
    const mark = () => chips.forEach(([v, c]) => c.classList.toggle("on", v === adding.count));
    n.addEventListener("input", () => { adding.count = Math.max(1, Math.min(6400, Number(n.value.replace(/[^0-9]/g, "")) || 1)); mark(); changed(); });
    row.appendChild(n);
    return row;
  }
  // + ADD (D339): the picker once the pictures are there; until then an item id and a count
  function addForm(p, area, version) {
    if (Pictures.ready()) return picker(p, area, version);
    const f = el("div", "addbox");
    f.appendChild(el("div", "ilab2", "ADD TO " + p.name.toUpperCase() + " · " + where(area.id, 0).replace(/ slot 1$/, "").replace(/hotbar 1/, "inventory").toUpperCase()));
    const i = el("input", "additem"); i.type = "text"; i.maxLength = 160; i.placeholder = "Item id: minecraft:diamond, create:brass_ingot"; i.value = adding.item; i.dataset.keep = "additem";
    i.autocapitalize = "off"; i.spellcheck = false;
    const key = "add " + p.id + " " + area.id;
    const go = el("button"); go.type = "button"; go.dataset.keep = "addgo";
    const plain = () => "Add", sure2 = () => "Sure? Add " + adding.count + " " + words(adding.item);
    i.addEventListener("input", () => { adding.item = i.value; if (armedFor(key)) go.textContent = sure2(); });
    f.appendChild(i);
    f.appendChild(countRow(() => { if (armedFor(key)) go.textContent = sure2(); }));
    f.appendChild(el("div", "note", "No item pictures yet: an Admin types /su icons in their own game, and this becomes the creative picker."));
    sureKey(go, key, plain, sure2, "btn sm",
      () => change(p, "/api/items/add", { player: p.id, area: area.id, item: adding.item.trim(), count: adding.count, version }),
      () => { if (adding.item.trim()) return true; i.focus(); return false; }); // an id first
    f.appendChild(go);
    return f;
  }
  function answerLine(p) {
    const a = last.get(p.id);
    if (!a) return null;
    const line = el("div", a.ok ? "okm" : "err", a.message);
    const left = a.undo ? a.until - Date.now() : 0;
    if (left > 0) {
      const u = el("button", "undo", "UNDO " + Math.floor(left / 60000) + ":" + String(Math.floor(left / 1000) % 60).padStart(2, "0")); u.type = "button"; u.dataset.keep = "undo";
      u.addEventListener("click", () => { u.disabled = true; change(p, "/api/items/undo", { token: a.undo }); });
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
    if (stale) fetchFor(p, opts.redraw, !!c); // the first read drawn at once; later ones once nothing is being typed
    const head = el("div", "sect"); head.appendChild(el("span", null, "INVENTORY"));
    const right = el("span", "ihead");
    const r = c && c.r;
    if (r && r.ok && r.items) {
      const it = r.items;
      if (it.live) right.appendChild(el("span", "tl", "●"));
      right.appendChild(el("span", it.live ? "live" : "saved", it.live ? "live · " + ago(c.at) : "saved " + ago(it.savedAt) + (it.server ? " · " + it.server : "")));
    }
    const again = el("button", "iref", "↻"); again.type = "button"; again.title = "Read again"; again.dataset.keep = "iref";
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
    // cosmetic slots (BSP-InventorySync's CURIOS_COSMETIC) with the rest, after them, named "(cosmetic)"
    const curios = areas.filter((a) => a.kind === "CURIOS" || a.kind === "CURIOS_COSMETIC").sort((a, b) => (a.kind === "CURIOS_COSMETIC") - (b.kind === "CURIOS_COSMETIC"));
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
    const h = el("button", "ifh"); h.type = "button"; h.dataset.keep = "fold:" + key;
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
    if (!c || (!c.loading && Date.now() - c.at > REFRESH_MS)) fetchFor(p, opts.redraw, !!c);
    const r = c && c.r, inv = r && r.ok && r.items ? (r.items.areas || []).find((x) => x.id === "inventory") : null;
    const wrap = el("div", "items");
    wrap.appendChild(grid(inv, 0, 9, p, "hot"));
    const t = tipFor(p, inv); if (t) wrap.appendChild(t);
    if (r && !r.ok) wrap.appendChild(el("div", "note", r.message));
    return wrap;
  }

  return { section, hotbar, poll, forget, name, icon: (it, size) => Pictures.icon(Pictures.indexOf(it.id, it.v), size), letters, describe: (it, areaId) => tooltip(it, areaId) };
})();

// More › Find a player (D336, D372): anyone who ever joined, by the start of their name. An online player opens their
// sheet on the Players tab; an offline one opens here, with their items from the last save, graves and notes.
const FindPlayer = (() => {
  const st = { q: "", found: [], chosen: null, timer: 0, error: "" };
  async function search(q) {
    st.q = q;
    if (!q) { st.found = []; App.refreshMore(); return; }
    const r = await call("/api/players/find", { q });
    if (st.q !== q) return;
    st.found = r.ok ? r.players : []; st.error = r.ok ? "" : r.message;
    App.refreshMore();
  }
  function view(main) {
    if (st.chosen) { offline(main, st.chosen); return; }
    main.appendChild(el("h2", null, "Find a player"));
    const q = el("input", "search findq"); q.type = "search"; q.placeholder = "The start of a name, online or not"; q.value = st.q; q.maxLength = 16; q.dataset.keep = "findq";
    q.addEventListener("input", () => { clearTimeout(st.timer); const v = q.value.trim(); st.timer = setTimeout(() => search(v), 250); });
    main.appendChild(q);
    const list = el("div", "list");
    st.found.forEach((p) => {
      const b = el("button", "item menuitem"); b.type = "button"; b.dataset.keep = "found:" + p.id;
      const g = el("div", "grow"); g.appendChild(el("div", "nm", p.name)); g.appendChild(Tags.chips(p.id, 2));
      g.appendChild(el("div", "sub", p.server ? "online on " + p.server : "offline"));
      b.appendChild(g); b.appendChild(el("span", "tl", "OPEN"));
      // online and in the feed: their sheet; otherwise here (the feed may not have them yet: audit)
      b.addEventListener("click", () => { if (p.server && live.players.some((x) => x.id === p.id)) openPlayerSheet(p.id); else { choose(p); App.refreshMore(); } });
      list.appendChild(b);
    });
    if (st.error) list.appendChild(el("div", "err", st.error));
    else if (st.q && !st.found.length) list.appendChild(el("div", "note", "Nobody whose name starts with “" + st.q + "” has played on the network."));
    main.appendChild(list);
    main.appendChild(el("div", "note", "Everyone who has joined the network. An online player opens on the Players tab; an offline one here, with their items from their last save."));
  }
  // opening an offline player reads their items fresh (audit)
  function choose(p) { if (!st.chosen || st.chosen.id !== p.id) Items.forget(p.id); st.chosen = p; }
  // the search box ready to type in when the page is opened on a computer
  function focus() { const q = document.querySelector(".findq"); if (q && matchMedia("(min-width: 900px)").matches) q.focus(); }
  function offline(main, p) {
    const who = el("div", "offwho"); const g = el("div", "grow");
    g.appendChild(el("div", "dname", p.name)); g.appendChild(Tags.chips(p.id));
    g.appendChild(el("div", "sub", "offline"));
    who.appendChild(Players.face(p, 44)); who.appendChild(g); main.appendChild(who);
    const keys = el("div", "keys"); const gr = el("button", "key", "Graves"); gr.type = "button"; gr.dataset.keep = "k:graves"; gr.addEventListener("click", () => { st.chosen = null; openGraves(p.name); });
    keys.appendChild(gr); keys.appendChild(Bank.key(p)); main.appendChild(keys);
    const box = el("div", "offsheet");
    const ml = Market.line(p); // D405: their lots and orders, when they have any
    if (ml) box.appendChild(ml);
    const bl = Bounties.line(p); // D528
    if (bl) box.appendChild(bl);
    box.appendChild(Items.section(p, { live: false, redraw: () => App.refreshMore() }));
    box.appendChild(Notes.section(p));
    main.appendChild(box);
  }
  return { view, focus, reset: () => { st.chosen = null; }, chosen: () => st.chosen, open: choose };
})();

// More › Items (D373): the item pictures the panel has, who made them and when, and how to make a fresh set.
const ItemsPage = (() => {
  let data = null;
  async function view(host) {
    const main = el("div", "itemspage"); host.appendChild(main); // drawn again meanwhile: this one is left out (audit)
    main.appendChild(el("h2", null, "Item pictures"));
    if (!data) { data = await call("/api/items/exports"); if (!data.ok && data.failed) { const e = data; data = null; main.appendChild(el("div", "err", e.message)); return; } }
    if (!main.isConnected) return;
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
      if (!main.isConnected) return;
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
