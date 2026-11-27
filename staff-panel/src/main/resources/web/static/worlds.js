"use strict";
// The WORLDS tab (SU-155; D762 to D777, set BC A and BD A refined, R1 to R4 and R9): every server as a tile with its
// dimensions and its BSP Region Guard regions counted by kind; a server's dimensions as cards and its regions as a table
// (kind chips, search, sortable columns); the editor as a drawer from the right (a full sheet on a phone) with every
// setting and flag Region Guard lists, changes held in gold until SAVE. Admins and Owners change; Moderators see it all
// read-only (D769). Text from the network is set with textContent only.

const Worlds = (() => {
  // kinds in the table's order, with the colours of D766 (the global region is never counted or drawn)
  const KINDS = [["protected", "Region", "#ff5c5c"], ["mine", "Mine", "#ffd23a"], ["plot_for_sale", "Plot for sale", "#a6e36b"],
    ["claim", "Claim", "#5ad16a"], ["open_for_buying", "Open area", "#7fb2ff"]];
  const KIND = Object.fromEntries(KINDS.map(([k, l, c], i) => [k, { label: l, colour: c, order: i }]));
  KIND.global = { label: "Global", colour: "#9aa3b5", order: 9 };
  const LISTED = new Set(["plot_for_sale", "open_for_buying", "claim"]); // they keep member and owner lists (Region Guard's FLAGS.md)
  const SWITCHABLE = ["protected", "plot_for_sale", "open_for_buying"];
  const CHIPS_KEY = "bsp.worldsKinds", ARM_MS = 3000;
  const st = { view: "grid", grid: null, gridErr: "", gq: "", server: null, page: null, pageErr: "", dim: null, q: "", hidden: loadHidden(),
    sort: { col: "kind", dir: 1 }, sel: null, edit: null, loading: false, readTok: 0, gridTok: 0, afterChange: null };
  let host = null, drawerBox = null;

  function loadHidden() { try { return new Set(JSON.parse(localStorage.getItem(CHIPS_KEY) || "[]")); } catch (e) { return new Set(); } }
  function saveHidden() { try { localStorage.setItem(CHIPS_KEY, JSON.stringify([...st.hidden])); } catch (e) { /* kept for this page only */ } }
  const desk = () => matchMedia("(min-width: 900px)").matches;
  const admin = () => me && (me.rank === "ADMIN" || me.rank === "OWNER");

  // ---------------------------------------------------------------- words
  function dimName(d) {
    d = String(d || "");
    if (d === "minecraft:overworld") return "Overworld";
    if (d === "minecraft:the_nether") return "Nether";
    if (d === "minecraft:the_end") return "End";
    const w = d.replace(/^.*:/, "").replace(/_/g, " ");
    return w.charAt(0).toUpperCase() + w.slice(1);
  }
  function ago(ms) {
    const m = Math.max(0, Math.round(ms / 60000));
    return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? Math.floor(m / 60) + " h ago" : Math.floor(m / 1440) + " d ago";
  }
  const num = (n) => Number(n).toLocaleString("en-GB");
  const sizeOf = (r) => r.box ? (r.box[3] - r.box[0] + 1) + " × " + (r.box[5] - r.box[2] + 1) : "whole world";
  const area = (r) => r.box ? (r.box[3] - r.box[0] + 1) * (r.box[5] - r.box[2] + 1) : Infinity;
  function swatch(kind) { const i = el("i", "wdsw" + (kind === "plot_for_sale" ? " dash" : "")); i.style.background = (KIND[kind] || KIND.global).colour; return i; }
  function kindTag(kind) {
    const k = KIND[kind] || { label: kind, colour: "#9aa3b5" }, t = el("span", "wdkt", k.label);
    t.style.color = k.colour; t.style.borderColor = k.colour + "66"; t.style.background = k.colour + "22"; return t;
  }
  function counts(c) {
    const box = el("div", "wdcounts");
    KINDS.forEach(([k, l]) => { if (!c || !c[k]) return; const s = el("span"); s.title = l; s.appendChild(swatch(k)); s.appendChild(document.createTextNode(String(c[k]))); box.appendChild(s); });
    if (!box.childNodes.length) box.appendChild(el("span", "mu", "No regions"));
    return box;
  }
  function key(text, cls, fn, keep) {
    const b = el("button", "wdkey" + (cls ? " " + cls : ""), text); b.type = "button";
    if (keep) b.dataset.keep = keep;
    if (fn) b.addEventListener("click", fn);
    return b;
  }
  function adminMark(b) { if (!admin()) { b.disabled = true; b.appendChild(el("span", "rk ADMIN", "Admin")); } return b; }
  function rowOf(r) { // the owner, the price, the cost or the next reset
    if (r.owners && r.owners.length && r.type === "claim") return r.owners.map((p) => p.name).join(", ");
    if (r.type === "plot_for_sale" && r.claim && r.claim.price !== undefined) return num(r.claim.price) + " coins";
    if (r.type === "open_for_buying" && r.claim && r.claim.costPerBlock !== undefined) return num(r.claim.costPerBlock) + " a block";
    if (r.type === "mine" && r.mine && r.mine.nextResetAt) return "resets in " + Math.max(0, Math.round((r.mine.nextResetAt - Date.now()) / 60000)) + " min";
    return "—";
  }

  // ---------------------------------------------------------------- reading
  async function loadGrid() {
    const tok = ++st.gridTok; // overlapping reads: the latest wins (audit)
    const r = await call("/api/regions/servers", {});
    if (tok !== st.gridTok) return;
    st.grid = r.ok ? r : st.grid; st.gridErr = r.ok ? "" : r.message;
    draw();
  }
  // EDIT › on the map's region card (SU-156): that server's page with the region's editor open once it is read
  function edit(server, dim, id) {
    if (st.edit && changes(st.edit).count && !(st.edit.server === server && st.edit.dim === dim && st.edit.id === id)) { // unsaved edits are not thrown away (audit)
      st.edit.msg = "Your unsaved changes to " + st.edit.id + " are kept: save or undo them, then open " + id + " again."; st.edit.msgOk = false;
      st.view = "server"; tab = "Worlds"; render(); return;
    }
    if (st.edit && st.edit.server === server && st.edit.dim === dim && st.edit.id === id) { st.view = "server"; tab = "Worlds"; render(); return; }
    st.view = "server"; st.server = server; st.page = null; st.pageErr = ""; st.q = ""; st.edit = null; st.sel = id; st.dim = dim; st.flash = ""; st.openAfter = { dim, id };
    tab = "Worlds"; render();
  }
  async function loadServer(refresh, wasChange) {
    const server = st.server, tok = ++st.readTok; // the latest read for the server shown wins; an older or another server's answer is dropped (audit)
    if (wasChange) st.afterChange = server; // a read after a save: kept for whichever later read wins (audit: a live tick must not undo it)
    st.loading = true; drawHeadKey();
    const r = await call("/api/regions/server", { server, refresh: !!refresh });
    if (tok !== st.readTok || server !== st.server) return;
    st.loading = false;
    const afterChange = st.afterChange === server; st.afterChange = null;
    if (r.ok) {
      st.page = r; st.pageErr = "";
      const dims = (r.dims || []).map((d) => d.id);
      if (!st.dim || !dims.includes(st.dim)) st.dim = dims[0] || null;
    } else st.pageErr = r.message;
    if (st.edit && r.ok && (afterChange || !changes(st.edit).count)) reopen(); // unsaved edits are never replaced by a read (audit)
    if (st.openAfter && r.ok) { const o = st.openAfter, x = (r.regions || []).find((y) => y.dim === o.dim && y.id === o.id); st.openAfter = null; if (x) openEditor(x); }
    draw();
  }
  function drawHeadKey() { const k = document.querySelector('[data-keep="wd:refresh"]'); if (k) k.textContent = st.loading ? "READING…" : "REFRESH"; }
  // the editor after a read: the same region (by its new id after a rename), its values as read
  function reopen() {
    const e = st.edit, r = (st.page.regions || []).find((x) => x.dim === e.dim && x.id === (e.nextId || e.id));
    if (!r) { st.edit = null; return; }
    openEditor(r, e.msg, e.msgOk);
  }

  // ---------------------------------------------------------------- the grid (R1)
  function drawGrid(box) {
    const head = el("div", "wdhead");
    head.appendChild(el("span", "wdtitle", "WORLDS"));
    const all = st.grid ? st.grid.servers : [];
    head.appendChild(el("span", "mu wdsmall", all.length ? all.length + " servers · " + all.filter((s) => s.live).length + " online" : ""));
    head.appendChild(el("span", "grow"));
    const q = el("input", "search wdsearch"); q.type = "text"; q.placeholder = "Find a region on any server"; q.value = st.gq; q.dataset.keep = "wd:gq";
    q.addEventListener("input", () => { st.gq = q.value; drawFound(); });
    head.appendChild(q);
    box.appendChild(head);
    if (st.gridErr) box.appendChild(el("div", "err", st.gridErr));
    if (!st.grid) { box.appendChild(el("div", "note", "Reading the servers…")); return; }
    const found = el("div", "wdfound"); found.id = "wdfound"; box.appendChild(found);
    const tiles = el("div", "wdtiles");
    all.forEach((s) => tiles.appendChild(tile(s)));
    if (!all.length) tiles.appendChild(el("div", "note", "No game server is sending to the panel."));
    box.appendChild(tiles);
    if (typeof AlertsView !== "undefined") box.appendChild(AlertsView.recentBox(typeof me !== "undefined" ? me : null)); // SU-168
    drawFound();
  }
  function tile(s) {
    const al = typeof AlertsView !== "undefined" ? AlertsView.tileOf(s.server) : null; // a server out, restarting or being checked (SU-168)
    const t = el("button", "wdtile" + (al ? " " + al.cls : s.live ? "" : " off")); t.type = "button"; t.dataset.keep = "wd:tile:" + s.server;
    const top = el("div", "wdtrow"); const dot = el("i", "wddot" + (al ? " " + al.dot : s.live ? "" : " off")); top.appendChild(dot);
    top.appendChild(el("span", "wdname", s.server));
    if ((s.dims || []).length && st.grid && pvpOf(s)) top.appendChild(el("span", "wdpvp", "PvP"));
    if (al) { al.chip.classList.add("wdalchip"); top.appendChild(al.chip); }
    t.appendChild(top);
    if (al) t.appendChild(el("div", "wdalline " + al.lineCls, al.line));
    const line = el("div", "wdstat");
    if (s.live) { line.appendChild(el("span", null, (s.count || 0) + " online")); line.appendChild(el("span", null, (s.mspt || 0).toFixed(1) + " ms")); }
    else if (!al) line.appendChild(el("span", null, "off · last heard " + (s.heardAt ? ago(Date.now() - s.heardAt) : "before the panel started")));
    if (line.children.length) t.appendChild(line); // a tile in an alert says when it was last heard in its alert line
    if (s.live && s.dims && s.dims.length) {
      const d = el("div", "wddims"); s.dims.forEach((x) => d.appendChild(el("span", "wddim", dimName(x.id)))); t.appendChild(d);
    }
    if (s.live && s.counts) { const c = counts(s.counts); c.classList.add("wdline"); t.appendChild(c); }
    else if (!s.live) t.appendChild(el("div", "mu wdsmall", "No regions while it is off"));
    if (s.error) t.appendChild(el("div", "wderr", s.error));
    t.addEventListener("click", () => openServer(s.server));
    return t;
  }
  const pvpOf = (s) => !!s.pvp; // the server's Overworld has PvP on (its global region allows it)
  // the grid's search: ids and names on every server (R1), from what each server last said
  function drawFound() {
    const box = $("wdfound"); if (!box) return;
    box.textContent = "";
    const q = st.gq.trim().toLowerCase();
    if (q.length < 2 || !st.grid) return;
    const hits = [];
    st.grid.servers.forEach((s) => (s.index || []).forEach(([id, name, type, dim]) => {
      if (id.toLowerCase().includes(q) || String(name).toLowerCase().includes(q)) hits.push({ server: s.server, id, name, type, dim });
    }));
    const list = el("div", "list wdhits");
    hits.slice(0, 30).forEach((h) => {
      const b = el("button", "item wdhit"); b.type = "button";
      b.appendChild(swatch(h.type)); b.appendChild(el("span", "mono wdid", h.id)); b.appendChild(el("span", "mu grow", h.name || ""));
      b.appendChild(el("span", "mu wdsmall", h.server + " · " + dimName(h.dim) + " ›"));
      b.addEventListener("click", () => { st.sel = h.id; st.dim = h.dim; openServer(h.server, h.dim, h.id); });
      list.appendChild(b);
    });
    if (!hits.length) list.appendChild(el("div", "item note", "No region by that id or name."));
    else if (hits.length > 30) list.appendChild(el("div", "item note", (hits.length - 30) + " more: type more of the name."));
    box.appendChild(list);
  }
  function openServer(name, dim, sel) {
    st.flash = "";
    st.view = "server"; st.server = name; st.page = null; st.pageErr = ""; st.q = ""; st.edit = null; st.sel = sel || null;
    st.dim = dim || null;
    draw(); loadServer(false);
  }

  // ---------------------------------------------------------------- a server (R2)
  function drawServer(box) {
    const head = el("div", "wdhead");
    const back = key("‹ Worlds", "ghost", () => { st.view = "grid"; st.edit = null; draw(); loadGrid(); }); head.appendChild(back);
    head.appendChild(el("span", "wdcrumb", st.server));
    const g = st.grid && st.grid.servers.find((s) => s.server === st.server);
    if (g && g.live) head.appendChild(el("span", "mu wdsmall", (g.count || 0) + " online · " + (g.mspt || 0).toFixed(1) + " ms"));
    head.appendChild(el("span", "grow"));
    const q = el("input", "search wdsearch"); q.type = "text"; q.placeholder = "Search " + st.server + "'s regions"; q.value = st.q; q.dataset.keep = "wd:q";
    q.addEventListener("input", () => { st.q = q.value; drawTable(); });
    head.appendChild(q);
    head.appendChild(key(st.loading ? "READING…" : "REFRESH", "ghost", () => loadServer(true), "wd:refresh"));
    if (desk()) { // drawing is on a computer (R9): the Map tab on the dimension shown, drawing (R2)
      const nb = key("+ NEW REGION ON THE MAP", "vio", null, "wd:new");
      if (!admin()) adminMark(nb);
      else if (!st.page || !st.page.live || !st.dim) nb.disabled = true;
      nb.addEventListener("click", async () => {
        const server = st.server, dim = st.dim;
        nb.disabled = true;
        const ok = await MapView.canShow(server, dim);
        nb.disabled = false;
        if (!host || tab !== "Worlds" || st.server !== server) return;
        if (ok && MapView.drawRegionIn(server, dim)) { tab = "Map"; render(); return; }
        alert(ok === null ? "The list of maps could not be read. Try again." : "No map of " + server + "'s " + dimName(dim) + " is shown on the panel.");
      });
      head.appendChild(nb);
    }
    box.appendChild(head);
    if (st.pageErr) box.appendChild(el("div", "err", st.pageErr));
    const p = st.page;
    if (!p) { if (!st.pageErr) box.appendChild(el("div", "note", "Reading " + st.server + "'s regions…")); return; }
    if (p.note) box.appendChild(el("div", "gold", p.note));
    if (st.flash) box.appendChild(el("div", "okm", st.flash));
    // the dimension cards
    const cards = el("div", "wddcards");
    (p.dims || []).forEach((d) => {
      const c = el("div", "wddcard" + (d.id === st.dim ? " on" : "")); c.tabIndex = 0; c.dataset.keep = "wd:dim:" + d.id;
      c.appendChild(el("b", null, dimName(d.id)));
      const n = {}; (p.regions || []).forEach((r) => { if (r.dim === d.id && r.type !== "global" && !r.shellOf) n[r.type] = (n[r.type] || 0) + 1; });
      c.appendChild(counts(n));
      const gl = (p.regions || []).find((r) => r.dim === d.id && r.type === "global");
      const gs = el("button", "wdlink", gl ? "GLOBAL SETTINGS ›" : "No global settings yet"); gs.type = "button"; gs.disabled = !gl;
      gs.addEventListener("click", (ev) => { ev.stopPropagation(); if (gl) openEditor(gl); });
      c.appendChild(gs);
      const pick = () => { st.dim = d.id; st.sel = null; draw(); };
      c.addEventListener("click", pick); c.addEventListener("keydown", (ev) => { if (ev.key === "Enter") pick(); });
      cards.appendChild(c);
    });
    box.appendChild(cards);
    // the kind chips (kept in this browser)
    const chips = el("div", "wdchips");
    KINDS.forEach(([k, l]) => {
      const c = el("button", "wdchip" + (st.hidden.has(k) ? " off" : "")); c.type = "button"; c.dataset.keep = "wd:chip:" + k;
      c.appendChild(swatch(k)); c.appendChild(document.createTextNode(l));
      c.addEventListener("click", () => { if (st.hidden.has(k)) st.hidden.delete(k); else st.hidden.add(k); saveHidden(); draw(); });
      chips.appendChild(c);
    });
    chips.appendChild(el("span", "grow"));
    chips.appendChild(el("span", "mu wdsmall", "Read " + ago(Date.now() - (p.readAt || Date.now()))));
    box.appendChild(chips);
    const t = el("div", "wdtable"); t.id = "wdtable"; t.dataset.scroll = "wdtable"; box.appendChild(t);
    drawTable();
  }
  function rows() {
    const p = st.page, q = st.q.trim().toLowerCase();
    let list = (p.regions || []).filter((r) => r.dim === st.dim && r.type !== "global" && !r.shellOf && !st.hidden.has(r.type));
    if (q) list = list.filter((r) => r.id.toLowerCase().includes(q) || String(r.name || "").toLowerCase().includes(q)
      || (r.owners || []).some((o) => String(o.name).toLowerCase().includes(q)));
    const s = st.sort, by = {
      kind: (a, b) => ((KIND[a.type] || KIND.global).order - (KIND[b.type] || KIND.global).order) || (b.priority - a.priority) || a.id.localeCompare(b.id),
      id: (a, b) => a.id.localeCompare(b.id), name: (a, b) => String(a.name || "").localeCompare(String(b.name || "")),
      size: (a, b) => area(a) - area(b), priority: (a, b) => a.priority - b.priority, parent: (a, b) => String(a.parent || "").localeCompare(String(b.parent || "")),
    }[s.col] || (() => 0);
    return list.sort((a, b) => by(a, b) * s.dir);
  }
  function drawTable() {
    const box = $("wdtable"); if (!box || !st.page) return;
    const h = hold(); box.textContent = "";
    const list = rows();
    if (!list.length) { box.appendChild(el("div", "note wdpad", st.q ? "No region here matches." : "No regions in " + dimName(st.dim) + (st.hidden.size ? " of the kinds shown." : "."))); give(h); return; }
    if (!desk()) { // a phone: a list (R9)
      const l = el("div", "list");
      list.forEach((r) => {
        const b = el("button", "item wdhit" + (r.id === st.sel ? " on" : "")); b.type = "button";
        b.appendChild(swatch(r.type)); b.appendChild(el("span", "mono wdid grow", r.id)); b.appendChild(el("span", "mu wdsmall", sizeOf(r) + " ›"));
        b.addEventListener("click", () => openEditor(r)); l.appendChild(b);
      });
      box.appendChild(l); give(h); return;
    }
    const t = el("table", "wdtbl"), hr = el("tr");
    // a narrow page (the chat open beside it) drops the heights, then the parent and the name, so MAP › and EDIT › stay in view
    const w = box.clientWidth || 1200, cols = new Set(["sw", "id", "kind", "size", "priority", "who", "acts"]);
    if (w >= 760) cols.add("name");
    if (w >= 900) cols.add("parent");
    if (w >= 1020) cols.add("heights");
    [["", null, "sw"], ["Id", "id", "id"], ["Name", "name", "name"], ["Kind", "kind", "kind"], ["Size", "size", "size"], ["Heights", null, "heights"], ["Priority", "priority", "priority"],
      ["Parent", "parent", "parent"], ["Owner, price or reset", null, "who"], ["", null, "acts"]].filter((x) => cols.has(x[2])).forEach(([l, c]) => {
      const th = el("th", c ? "sortable" : null, l + (c && st.sort.col === c ? (st.sort.dir > 0 ? " ▴" : " ▾") : ""));
      if (c) th.addEventListener("click", () => { st.sort = { col: c, dir: st.sort.col === c ? -st.sort.dir : 1 }; drawTable(); });
      hr.appendChild(th);
    });
    const thead = el("thead"); thead.appendChild(hr); t.appendChild(thead);
    const tb = el("tbody");
    list.forEach((r) => {
      const tr = el("tr", r.id === st.sel ? "sel" : null);
      const td = (c, cls) => { const d = el("td", cls); if (c instanceof Node) d.appendChild(c); else d.textContent = c; tr.appendChild(d); return d; };
      td(swatch(r.type)); td(r.id, "mono wdid"); if (cols.has("name")) td(r.name || ""); td(kindTag(r.type)); td(sizeOf(r), "mono");
      if (cols.has("heights")) td(r.box ? r.box[1] + " to " + r.box[4] : "—", "mono mu");
      td(String(r.priority), "mono"); if (cols.has("parent")) td(r.parent || "—", "mono mu"); td(rowOf(r), "mu");
      const acts = el("span", "wdacts");
      acts.appendChild(mapLink(r)); acts.appendChild(key("EDIT ›", "link", () => { st.sel = r.id; openEditor(r); }, "wd:edit:" + r.id));
      td(acts);
      tr.addEventListener("click", (ev) => { if (ev.target.closest("button")) return; st.sel = r.id; drawTable(); });
      tr.addEventListener("dblclick", () => openEditor(r));
      tb.appendChild(tr);
    });
    t.appendChild(tb); box.appendChild(t); give(h);
  }
  // MAP ›: the Map tab on the region's server and world, zoomed to it with its card (D777)
  function mapLink(r, where) {
    const b = key("MAP ›", "link", null, "wd:" + (where || "map") + ":" + r.dim + ":" + r.id);
    b.addEventListener("click", async (ev) => {
      ev.stopPropagation();
      if (!r.box) return;
      const server = st.server;
      b.disabled = true;
      const ok = await MapView.canShow(server, r.dim);
      b.disabled = false;
      if (!host || tab !== "Worlds" || st.server !== server) return;
      if (ok && MapView.showRegion(server, r.dim, r.id)) { tab = "Map"; render(); return; } // zoomed to it, outlined and chosen (SU-156)
      alert(ok === null ? "The list of maps could not be read. Try MAP › again." : "No map of " + server + "'s " + dimName(r.dim) + " is shown on the panel.");
    });
    if (!r.box) b.disabled = true;
    return b;
  }

  // ---------------------------------------------------------------- the editor (R3, R4)
  function openEditor(r, msg, msgOk) {
    const copy = JSON.parse(JSON.stringify(r));
    st.sel = r.id;
    st.edit = { server: st.server, dim: r.dim, id: r.id, orig: copy, draft: JSON.parse(JSON.stringify(r)), aud: "visitors", fq: "", setOnly: false,
      msg: msg || "", msgOk: !!msgOk, busy: false, armRemove: 0, armClose: 0, armReset: 0, addOwner: "", addMember: "", bad: new Set() };
    draw();
  }
  function closeEditor(force) {
    const e = st.edit; if (!e) return;
    if (!force && changes(e).count && Date.now() - e.armClose > ARM_MS) { e.armClose = Date.now(); drawDrawer(); return; }
    st.edit = null; draw();
  }
  // what changed, as the panel's /api/regions/save takes it, and how many settings
  function changes(e) {
    const o = e.orig, d = e.draft, set = {}; let count = 0;
    const same = (a, b) => JSON.stringify(a === undefined ? null : a) === JSON.stringify(b === undefined ? null : b);
    ["name", "priority", "parent", "type"].forEach((k) => { if (!same(o[k], d[k])) { set[k] = d[k]; count++; } });
    if (d.id !== o.id) { set.newId = d.id; count++; }
    if (o.box && !same(o.box, d.box)) { set.box = d.box; count++; }
    const fl = {}; const keys = new Set([...Object.keys(o.flags || {}), ...Object.keys(d.flags || {})]);
    keys.forEach((k) => { if (!same((o.flags || {})[k], (d.flags || {})[k])) { fl[k] = (d.flags || {})[k] === undefined ? null : d.flags[k]; count++; } });
    if (Object.keys(fl).length) set.flags = fl;
    if (o.lists || d.lists) { // a region switched to a plot has lists of its own (audit)
      const ls = {};
      ["members", "owners"].forEach((a) => {
        const one = {}, oa = (o.lists || {})[a] || {}, da = (d.lists || {})[a] || {};
        new Set([...Object.keys(oa), ...Object.keys(da)]).forEach((k) => { if (!same(oa[k], da[k])) { one[k] = da[k] === undefined ? null : da[k]; count++; } });
        if (Object.keys(one).length) ls[a] = one;
      });
      if (Object.keys(ls).length) set.lists = ls;
    }
    ["owners", "members"].forEach((w) => {
      const before = new Set((o[w] || []).map((p) => p.id)), after = new Set((d[w] || []).map((p) => p.id));
      const add = (d[w] || []).filter((p) => p.add).map((p) => p.name), rem = [...before].filter((x) => !after.has(x));
      if (add.length || rem.length) { set[w] = { add, remove: rem }; count += add.length + rem.length; }
    });
    [["claim", ["price", "costPerBlock", "minSize", "maxSize", "coinTier", "plotLimit"]], ["border", ["size", "x", "z", "plotsInPvp"]]].forEach(([part, ks]) => {
      if (!d[part]) return; const one = {};
      ks.forEach((k) => { if (d[part][k] !== undefined && !same((o[part] || {})[k], d[part][k])) { one[k] = d[part][k]; count++; } });
      if (Object.keys(one).length) set[part] = one;
    });
    if (d.mine) {
      const one = {}, om = o.mine || {};
      if (!same(om.fill, d.mine.fill)) { one.fill = d.mine.fill; count++; }
      if (!same(om.intervalSeconds, d.mine.intervalSeconds)) { one.intervalSeconds = d.mine.intervalSeconds; count++; }
      if (!same(om.spawn, d.mine.spawn)) { one.spawn = d.mine.spawn; count++; }
      if (Object.keys(one).length) set.mine = one;
    }
    return { set, count };
  }
  function drawDrawer() {
    if (!drawerBox) return;
    const h = hold();
    drawerBox.textContent = "";
    const e = st.edit; if (!e) { drawerBox.classList.add("hidden"); give(h); return; }
    drawerBox.classList.remove("hidden");
    const d = e.draft, can = admin() && st.page && st.page.live && !e.busy, glob = d.type === "global"; // nothing typed while a save is out (audit)
    const shade = el("div", "wdshade"); shade.addEventListener("click", () => closeEditor(false)); drawerBox.appendChild(shade);
    const dr = el("div", "wddrawer");
    // the head
    const dh = el("div", "wddh"); dh.appendChild(swatch(d.type)); dh.appendChild(el("b", "mono wdeid", glob ? "Global settings" : e.orig.id)); dh.appendChild(kindTag(d.type));
    dh.appendChild(el("span", "mu wdsmall grow", st.server + " · " + dimName(e.dim) + " · version " + e.orig.version));
    if (!glob) dh.appendChild(mapLink(e.orig, "dmap"));
    const x = key(Date.now() - e.armClose < ARM_MS ? "Discard changes?" : "×", Date.now() - e.armClose < ARM_MS ? "dg" : "ghost", () => closeEditor(false), "wd:close");
    unarm(x, ARM_MS - (Date.now() - e.armClose), "×", "wdkey ghost"); dh.appendChild(x);
    dr.appendChild(dh);
    const body = el("div", "wddbody"); body.dataset.scroll = "wddbody";
    if (!admin()) body.appendChild(el("div", "wdvio", "You can see this region; changing it is for Admins and Owners."));
    else if (st.page && !st.page.live) body.appendChild(el("div", "gold", st.server + " is not sending to the panel now: nothing can be changed until it is back."));
    if (e.msg) body.appendChild(el("div", e.msgOk ? "okm" : "err", e.msg));
    const field = (label, value, onIn, opts) => {
      const f = el("label", "wdfield" + (opts && opts.wide ? " wide" : "")); f.appendChild(el("span", null, label));
      const i = el("input", "wdin" + (opts && opts.mono ? " mono" : "")); i.type = opts && opts.number ? "number" : "text"; i.value = value === undefined || value === null ? "" : value;
      i.disabled = !can || !!(opts && opts.ro); i.dataset.keep = "wd:f:" + label;
      if (opts && opts.changed) i.classList.add("ch");
      i.addEventListener("input", () => { // a field that does not read is marked and holds SAVE back (audit)
        const ok = onIn(i.value) !== false; i.classList.toggle("bad", !ok); if (ok) e.bad.delete(label); else e.bad.add(label);
        i.classList.toggle("ch", true); markBar();
      });
      f.appendChild(i); return f;
    };
    const o = e.orig;
    // GENERAL
    if (!glob) {
      body.appendChild(el("div", "sect", "GENERAL"));
      const g = el("div", "wdgrid3");
      g.appendChild(field("Name", d.name, (v) => { d.name = v; }, { changed: d.name !== o.name }));
      g.appendChild(field("Id", d.id, (v) => { d.id = v.trim().toLowerCase(); }, { mono: true, changed: d.id !== o.id }));
      g.appendChild(field("Priority", d.priority, (v) => { const n = Math.trunc(Number(v)); d.priority = v === "" || !Number.isFinite(n) ? o.priority : n; }, { mono: true, number: true, changed: d.priority !== o.priority }));
      const pf = el("label", "wdfield"); pf.appendChild(el("span", null, "Parent"));
      const ps = el("select", "wdin mono" + (d.parent !== o.parent ? " ch" : "")); ps.disabled = !can; ps.dataset.keep = "wd:parent";
      const none = el("option", null, "none"); none.value = ""; ps.appendChild(none);
      const below = descendants(o.id, e.dim);
      (st.page.regions || []).filter((r) => r.dim === e.dim && r.type !== "global" && r.id !== o.id && !below.has(r.id)).sort((a, b) => a.id.localeCompare(b.id))
        .forEach((r) => { const op = el("option", null, r.id); op.value = r.id; ps.appendChild(op); });
      ps.value = d.parent || ""; ps.addEventListener("change", () => { d.parent = ps.value; markBar(); ps.classList.add("ch"); });
      pf.appendChild(ps); g.appendChild(pf);
      const kf = el("label", "wdfield"); kf.appendChild(el("span", null, "Kind"));
      if (SWITCHABLE.includes(o.type)) {
        const ks = el("select", "wdin" + (d.type !== o.type ? " ch" : "")); ks.disabled = !can; ks.dataset.keep = "wd:kind";
        SWITCHABLE.forEach((k) => { const op = el("option", null, KIND[k].label); op.value = k; ks.appendChild(op); });
        ks.value = d.type; ks.addEventListener("change", () => { d.type = ks.value; if (LISTED.has(d.type) && !d.lists) d.lists = { members: {}, owners: {} }; drawDrawer(); });
        kf.appendChild(ks);
      } else { const ro = el("div", "wdin ro", KIND[o.type] ? KIND[o.type].label : o.type); kf.appendChild(ro); }
      g.appendChild(kf);
      const kids = (st.page.regions || []).filter((r) => r.dim === e.dim && r.parent === o.id && !r.shellOf).map((r) => r.id);
      const cf = el("label", "wdfield"); cf.appendChild(el("span", null, "Children")); cf.appendChild(el("div", "wdin ro mono", kids.length ? kids.join(", ") : "none")); g.appendChild(cf);
      body.appendChild(g);
      // BOUNDS
      body.appendChild(el("div", "sect", "BOUNDS"));
      const b = el("div", "wdgrid3");
      const cor = (label, i0) => field(label, d.box.slice(i0, i0 + 3).join(", "), (v) => {
        const p = v.split(/[ ,]+/).filter(Boolean).map(Number);
        if (p.length === 3 && p.every((n) => Number.isInteger(n))) { for (let i = 0; i < 3; i++) d.box[i0 + i] = p[i]; return true; }
        return false;
      }, { mono: true, changed: JSON.stringify(d.box.slice(i0, i0 + 3)) !== JSON.stringify(o.box.slice(i0, i0 + 3)) });
      b.appendChild(cor("From x, y, z", 0)); b.appendChild(cor("To x, y, z", 3));
      const sf = el("label", "wdfield"); sf.appendChild(el("span", null, "Size")); sf.appendChild(el("div", "wdin ro mono", sizeOf(d) + " · Y " + d.box[1] + " to " + d.box[4])); b.appendChild(sf);
      body.appendChild(b);
      // PEOPLE
      body.appendChild(el("div", "sect", "PEOPLE"));
      ["owners", "members"].forEach((w) => body.appendChild(people(e, w, can)));
    }
    // the kind's own part
    part(body, e, can);
    // FLAGS
    flags(body, e, can);
    dr.appendChild(body);
    // the save bar
    const bar = el("div", "wdbar"); bar.id = "wdbar"; dr.appendChild(bar);
    drawerBox.appendChild(dr);
    markBar();
    give(h);
  }
  function descendants(id, dim) {
    const out = new Set(); let grew = true;
    while (grew) { grew = false; (st.page.regions || []).forEach((r) => { if (r.dim === dim && (r.parent === id || out.has(r.parent)) && !out.has(r.id)) { out.add(r.id); grew = true; } }); }
    return out;
  }
  function people(e, w, can) {
    const d = e.draft, row = el("div", "wdpeople");
    row.appendChild(el("span", "mu wdplabel", w === "owners" ? "Owners" : "Members"));
    (d[w] || []).forEach((p, i) => {
      const c = el("span", "wdperson" + (p.add ? " ch" : ""), p.name);
      if (can) { const x = key("×", "ghost tiny", () => { d[w].splice(i, 1); drawDrawer(); }); x.title = "Take " + p.name + " off"; c.appendChild(x); }
      row.appendChild(c);
    });
    if (!(d[w] || []).length) row.appendChild(el("span", "dim wdsmall", "none"));
    if (can) {
      const k = w === "owners" ? "addOwner" : "addMember";
      const i = el("input", "wdin wdadd"); i.type = "text"; i.placeholder = "Add by name"; i.value = e[k]; i.dataset.keep = "wd:add:" + w; i.maxLength = 16;
      const add = () => { const n = i.value.trim(); if (!/^[A-Za-z0-9_]{1,16}$/.test(n)) return; d[w] = d[w] || []; if (!d[w].some((p) => p.name.toLowerCase() === n.toLowerCase())) { const was = (e.orig[w] || []).find((p) => p.name.toLowerCase() === n.toLowerCase()); d[w].push(was ? Object.assign({}, was) : { id: "new:" + n, name: n, add: true }); } e[k] = ""; drawDrawer(); }; // someone taken off and added back is as they were (audit)
      i.addEventListener("input", () => { e[k] = i.value; });
      i.addEventListener("keydown", (ev) => { if (ev.key === "Enter") add(); });
      row.appendChild(i); row.appendChild(key("ADD", "ghost", add));
    }
    return row;
  }
  function part(body, e, can) {
    const d = e.draft, o = e.orig;
    const num = (label, obj, k, opts) => {
      const f = el("label", "wdfield"); f.appendChild(el("span", null, label));
      const i = el("input", "wdin mono" + (obj[k] !== (opts && opts.from ? opts.from[k] : undefined) ? " ch" : "")); i.type = "number"; i.value = obj[k] === undefined ? "" : obj[k];
      i.disabled = !can; i.dataset.keep = "wd:p:" + k;
      i.addEventListener("input", () => { const n = Math.trunc(Number(i.value)); if (i.value === "" || !Number.isFinite(n)) return; obj[k] = n; markBar(); i.classList.add("ch"); });
      f.appendChild(i); return f;
    };
    if (d.type === "plot_for_sale" || d.type === "open_for_buying") {
      d.claim = d.claim || {}; const oc = o.claim || {};
      body.appendChild(el("div", "sect", d.type === "plot_for_sale" ? "PLOT FOR SALE" : "OPEN AREA"));
      const g = el("div", "wdgrid3");
      if (d.type === "plot_for_sale") g.appendChild(num("Price (coins)", d.claim, "price", { from: oc }));
      else { g.appendChild(num("Cost a block", d.claim, "costPerBlock", { from: oc })); g.appendChild(num("Smallest side", d.claim, "minSize", { from: oc })); g.appendChild(num("Largest side", d.claim, "maxSize", { from: oc })); }
      g.appendChild(num("Claims a player may hold here (-1: no limit)", d.claim, "plotLimit", { from: oc }));
      body.appendChild(g);
      const tiers = (st.page && st.page.coinTiers) || []; // BSP Core's Shatter Coin tiers (D779), as the SALE tab in game
      if (tiers.length) {
        const f = el("div", "wdfield wdtiers"); f.appendChild(el("span", null, "Coin tier (when prices are in coins)"));
        const seg = el("span", "wdtri"), cur = d.claim.coinTier || "";
        [["", "Default"]].concat(tiers.map((t) => [t, t.charAt(0).toUpperCase() + t.slice(1)])).forEach(([v, label]) => {
          const b = key(label, cur === v ? "a" : "", () => { d.claim.coinTier = v === (oc.coinTier || "") ? oc.coinTier : v; markBar(); drawDrawer(); });
          b.disabled = !can; b.dataset.keep = "wd:tier:" + (v || "default"); seg.appendChild(b);
        });
        f.appendChild(seg);
        if ((d.claim.coinTier || "") !== (oc.coinTier || "")) f.classList.add("ch");
        body.appendChild(f);
      }
    }
    if (d.type === "claim") {
      const c = d.claim || {};
      body.appendChild(el("div", "sect", "CLAIM"));
      const kv = el("div", "wdkv");
      const add = (k, v) => { kv.appendChild(el("span", "mu", k)); kv.appendChild(el("span", null, v)); };
      add("Owner", (d.owners || []).map((p) => p.name).join(", ") || "—");
      if (c.boughtFor !== undefined) add("Bought", (c.boughtAt ? new Date(c.boughtAt).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) + " · " : "") + num0(c.boughtFor) + " coins");
      if (c.origin) add("From", c.origin === "open" ? "an open area" : "a plot for sale");
      body.appendChild(kv);
    }
    if (d.type === "mine") {
      d.mine = d.mine || {}; const m = d.mine, om = o.mine || {};
      const head = el("div", "sect"); head.appendChild(el("span", null, "MINE"));
      const armed = Date.now() - e.armReset < ARM_MS, dirty = changes(e).count > 0;
      const rb = adminMark(key(armed ? "Sure? Reset" : "RESET NOW", armed ? "dg" : "ghost", () => {
        if (dirty) { e.msg = "Save or undo your changes first: resetting reads the mine again."; e.msgOk = false; drawDrawer(); return; }
        if (Date.now() - e.armReset >= ARM_MS) { e.armReset = Date.now(); drawDrawer(); return; } // armed only for its three seconds (audit)
        e.armReset = 0; act("/api/regions/reset", { server: e.server, dim: e.dim, id: o.id });
      }, "wd:reset"));
      if (!st.page.live) rb.disabled = true;
      unarm(rb, ARM_MS - (Date.now() - e.armReset), "RESET NOW", "wdkey ghost");
      head.appendChild(rb); body.appendChild(head);
      const fill = m.fill || [];
      const bar = el("div", "wdfill");
      fill.forEach((f, i) => { const s = el("i"); s.style.width = (f.weight / 10) + "%"; s.style.background = blockColour(f.block, i); s.title = f.block; bar.appendChild(s); });
      body.appendChild(bar);
      const total = fill.reduce((a, f) => a + (f.weight || 0), 0);
      fill.forEach((f, i) => {
        const r = el("div", "wdfrow" + (JSON.stringify(f) !== JSON.stringify((om.fill || [])[i]) ? " ch" : ""));
        const sw = el("i", "wdsw"); sw.style.background = blockColour(f.block, i); r.appendChild(sw);
        const b = el("input", "wdin mono grow"); b.type = "text"; b.value = f.block; b.disabled = !can; b.dataset.keep = "wd:fb:" + i;
        b.addEventListener("input", () => { f.block = b.value.trim().toLowerCase(); markBar(); });
        const p = el("input", "wdin mono wdpct"); p.type = "number"; p.step = "0.1"; p.value = (f.weight / 10).toFixed(1); p.disabled = !can; p.dataset.keep = "wd:fw:" + i;
        p.addEventListener("input", () => { const v = Number(p.value); if (p.value === "" || !Number.isFinite(v)) return; f.weight = Math.round(v * 10); markBar(); totalLine(fill); });
        r.appendChild(b); r.appendChild(p); r.appendChild(el("span", "mu", "%"));
        if (can) r.appendChild(key("×", "ghost tiny", () => { fill.splice(i, 1); drawDrawer(); }));
        body.appendChild(r);
      });
      const foot = el("div", "wdfrow");
      if (can) foot.appendChild(key("+ ADD A BLOCK", "ghost", () => { // a block not yet in the mix, with what is left of 100%, or a share of the largest (audit)
        m.fill = fill;
        const block = ["minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:cobblestone", "minecraft:tuff", "minecraft:deepslate"].find((b) => !fill.some((f) => f.block === b)) || "minecraft:gravel";
        let w = 1000 - total;
        if (w <= 0) { const big = fill.reduce((a, f) => (f.weight > a.weight ? f : a), fill[0] || { weight: 0 }); w = big && big.weight > 10 ? 10 : 0; if (w) big.weight -= w; }
        fill.push({ block, weight: Math.max(1, w) }); drawDrawer();
      }));
      const tl = el("span"); tl.id = "wdtotal"; foot.appendChild(tl); totalLine(fill, tl);
      body.appendChild(foot);
      const g = el("div", "wdgrid3");
      const iv = el("label", "wdfield"); iv.appendChild(el("span", null, "Reset every (minutes)"));
      const ii = el("input", "wdin mono" + (m.intervalSeconds !== om.intervalSeconds ? " ch" : "")); ii.type = "number"; ii.value = Math.round((m.intervalSeconds || 0) / 60); ii.disabled = !can; ii.dataset.keep = "wd:interval";
      ii.addEventListener("input", () => { if (ii.value !== "") { m.intervalSeconds = Math.round(Number(ii.value) * 60); markBar(); ii.classList.add("ch"); } });
      iv.appendChild(ii); g.appendChild(iv);
      const nx = el("label", "wdfield"); nx.appendChild(el("span", null, "Next reset"));
      nx.appendChild(el("div", "wdin ro", m.nextResetAt ? "in " + Math.max(0, Math.round((m.nextResetAt - Date.now()) / 60000)) + " min (" + new Date(m.nextResetAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hourCycle: "h23" }) + ")" : "—"));
      g.appendChild(nx);
      const sp = el("label", "wdfield"); sp.appendChild(el("span", null, "Where players go (x, y, z)"));
      const si = el("input", "wdin mono" + (JSON.stringify(m.spawn) !== JSON.stringify(om.spawn) ? " ch" : "")); si.type = "text"; si.disabled = !can; si.dataset.keep = "wd:spawn";
      si.value = m.spawn ? m.spawn.x + ", " + m.spawn.y + ", " + m.spawn.z : ""; si.placeholder = "the mine's top";
      si.addEventListener("input", () => {
        const p = si.value.split(/[ ,]+/).filter(Boolean).map(Number), ok = (p.length === 3 && p.every(Number.isInteger)) || !si.value.trim();
        if (si.value.trim() && ok) m.spawn = { x: p[0], y: p[1], z: p[2] }; else if (!si.value.trim()) m.spawn = null;
        si.classList.toggle("bad", !ok); if (ok) e.bad.delete("spawn"); else e.bad.add("spawn"); si.classList.add("ch"); markBar(); // a place that does not read holds SAVE back (audit)
      });
      sp.appendChild(si); g.appendChild(sp);
      body.appendChild(g);
      if (m.shell) body.appendChild(el("div", "note", "Its shell, " + m.shell + ", is kept round it by Region Guard and changes with it."));
    }
    if (d.type === "global") {
      d.border = d.border || {}; const ob = o.border || {};
      body.appendChild(el("div", "sect", "WORLD BORDER"));
      const g = el("div", "wdgrid3");
      g.appendChild(num("Size in blocks (0: none)", d.border, "size", { from: ob })); g.appendChild(num("Centre x", d.border, "x", { from: ob })); g.appendChild(num("Centre z", d.border, "z", { from: ob }));
      body.appendChild(g);
      // plots and claims in this world when it is a PvP world: Region Guard's three settings (D780)
      const f = el("div", "wdfield wdpip"); f.appendChild(el("span", null, "Plots and claims in this world if it is a PvP world"));
      const seg = el("span", "wdtri"), cur = d.border.plotsInPvp || "default";
      [["default", "Follow the config", "u"], ["allow", "Allow", "a"], ["deny", "Refuse", "d"]].forEach(([v, label, cls]) => {
        const b = key(label, cur === v ? cls : "", () => { d.border.plotsInPvp = v === (ob.plotsInPvp || "default") ? ob.plotsInPvp : v; markBar(); drawDrawer(); });
        b.disabled = !can; b.dataset.keep = "wd:pip:" + v; seg.appendChild(b);
      });
      f.appendChild(seg);
      if (cur !== (ob.plotsInPvp || "default")) f.classList.add("ch");
      body.appendChild(f);
    }
  }
  function totalLine(fill, into) {
    const t = into || $("wdtotal"); if (!t) return;
    const total = fill.reduce((a, f) => a + (f.weight || 0), 0);
    t.className = total === 1000 ? "mu wdsmall" : "wdwarn";
    t.textContent = "Adds up to " + (total / 10).toFixed(1) + "%" + (total === 1000 ? "" : ": it must be 100%");
  }
  const num0 = (n) => Number(n || 0).toLocaleString("en-GB");
  function blockColour(id, i) {
    const known = { stone: "#8a8a8a", andesite: "#a3a39c", iron_ore: "#d8af93", deepslate_iron_ore: "#9a7d6c", coal_ore: "#3a3a3a", copper_ore: "#c07a4f", gold_ore: "#e8c547",
      deepslate_diamond_ore: "#5fd1c9", diamond_ore: "#5fd1c9", amethyst_block: "#9a6ad6", emerald_ore: "#3fbf6a", redstone_ore: "#c33", lapis_ore: "#3456b5" };
    const k = String(id || "").replace(/^.*:/, "");
    return known[k] || ["#6b7180", "#7fb2ff", "#b58cff", "#ff6b5c", "#19d3b0"][i % 5];
  }
  // FLAGS: every flag the server lists, by group, the set ones first; Visitors, Members or Owners for plots, open areas and claims
  function flags(body, e, can) {
    const d = e.draft, cat = (st.page.flags || []), listed = LISTED.has(d.type) && d.lists;
    const head = el("div", "sect"); const setN = Object.keys(d.flags || {}).length;
    head.appendChild(el("span", null, "FLAGS")); head.appendChild(el("span", "mu wdsmall", setN + " set · " + cat.length + " in all"));
    body.appendChild(head);
    const tools = el("div", "wdftools");
    if (listed) {
      const sw = el("div", "wdseg");
      [["visitors", "Visitors"], ["members", "Members"], ["owners", "Owners"]].forEach(([a, l]) => {
        const b = key(l, a === e.aud ? "on" : "", () => { e.aud = a; drawDrawer(); }, "wd:aud:" + a); sw.appendChild(b);
      });
      tools.appendChild(sw);
    }
    const q = el("input", "search wdfq"); q.type = "text"; q.placeholder = "Find a flag"; q.value = e.fq; q.dataset.keep = "wd:fq";
    q.addEventListener("input", () => { e.fq = q.value; drawFlags(e, can); });
    tools.appendChild(q);
    const so = key("Set only", e.setOnly ? "on" : "", () => { e.setOnly = !e.setOnly; drawDrawer(); }, "wd:setonly"); tools.appendChild(so);
    body.appendChild(tools);
    if (listed && e.aud !== "visitors") body.appendChild(el("div", "note", (e.aud === "members" ? "Members" : "Owners") + " are judged by this list inside the region; Inherit means "
      + (e.aud === "owners" ? "owners may (build flags) or the same as visitors." : "the same as owners. Members never get more than owners.")));
    const box = el("div", "wdflags"); box.id = "wdflags"; body.appendChild(box);
    drawFlags(e, can, box); // the drawer is not on the page yet: drawn into the box itself
  }
  function drawFlags(e, can, into) {
    const box = into || $("wdflags"); if (!box) return;
    const h = hold(); box.textContent = "";
    const d = e.draft, o = e.orig, aud = LISTED.has(d.type) && d.lists ? e.aud : "visitors";
    const values = aud === "visitors" ? (d.flags = d.flags || {}) : (d.lists[aud] = d.lists[aud] || {});
    const before = aud === "visitors" ? (o.flags || {}) : ((o.lists || {})[aud] || {});
    const q = e.fq.trim().toLowerCase();
    let cat = (st.page.flags || []).filter((f) => aud === "visitors" || f.lists);
    if (q) cat = cat.filter((f) => f.name.includes(q) || String(f.about || "").toLowerCase().includes(q));
    if (e.setOnly) cat = cat.filter((f) => values[f.name] !== undefined);
    const groups = [];
    cat.forEach((f) => { let g = groups.find((x) => x.name === f.group); if (!g) groups.push(g = { name: f.group, list: [] }); g.list.push(f); });
    groups.forEach((g) => {
      g.list.sort((a, b) => (values[a.name] === undefined) - (values[b.name] === undefined));
      const gh = el("div", "wdfgrp", g.name || "Other"); gh.appendChild(el("span", "mu", " · " + g.list.filter((f) => values[f.name] !== undefined).length + " set"));
      box.appendChild(gh);
      g.list.forEach((f) => box.appendChild(flagRow(f, values, before, can, aud, e)));
    });
    if (!groups.length) box.appendChild(el("div", "note wdpad", q ? "No flag by that name." : "No flag is set here."));
    give(h);
  }
  function flagRow(f, values, before, can, aud, e) {
    const v = values[f.name], changed = JSON.stringify(v) !== JSON.stringify(before[f.name]);
    const r = el("div", "wdfrowf" + (changed ? " ch" : ""));
    const n = el("span", "wdfn mono", f.name); n.title = f.about || ""; r.appendChild(n);
    const ctl = el("span", "wdfctl");
    const put = (x) => { if (x === undefined || x === null || x === "") delete values[f.name]; else values[f.name] = x; drawFlags(e, can); markBar(); };
    if (f.kind === "state" || f.kind === "boolean" || aud !== "visitors") {
      const opts = f.kind === "boolean" && aud === "visitors" ? [[true, "True", "a"], [false, "False", "d"]] : [["allow", "Allow", "a"], ["deny", "Deny", "d"]];
      const seg = el("span", "wdtri");
      opts.forEach(([val, label, cls]) => { const b = key(label, v === val ? cls : "", () => put(val)); b.disabled = !can; b.dataset.keep = "wd:fl:" + f.name + ":" + label; seg.appendChild(b); });
      const ib = key("Inherit", v === undefined ? "u" : "", () => put(undefined)); ib.disabled = !can; ib.dataset.keep = "wd:fl:" + f.name + ":inherit"; seg.appendChild(ib);
      ctl.appendChild(seg);
    } else if (f.kind === "string-set") {
      const list = Array.isArray(v) ? v : [];
      list.forEach((x, i) => { const c = el("span", "wdperson", x); if (can) c.appendChild(key("×", "ghost tiny", () => { const nl = list.slice(); nl.splice(i, 1); put(nl.length ? nl : undefined); })); ctl.appendChild(c); });
      if (can) {
        const i = el("input", "wdin wdadd mono"); i.type = "text"; i.placeholder = "+ add"; i.dataset.keep = "wd:fs:" + f.name;
        i.addEventListener("keydown", (ev) => { if (ev.key === "Enter" && i.value.trim()) put(list.concat([i.value.trim()])); });
        ctl.appendChild(i);
      }
    } else if (f.kind === "game-mode") {
      const s = el("select", "wdin"); s.disabled = !can; s.dataset.keep = "wd:fg:" + f.name;
      ["", "survival", "creative", "adventure", "spectator"].forEach((m) => { const op = el("option", null, m || "inherit"); op.value = m; s.appendChild(op); });
      s.value = v || ""; s.addEventListener("change", () => put(s.value)); ctl.appendChild(s);
    } else {
      const i = el("input", "wdin" + (f.kind === "string" ? "" : " mono") + " wdfval"); i.type = "text"; i.value = v === undefined ? "" : String(v); i.disabled = !can;
      i.placeholder = f.kind === "location" ? "x y z, or empty to inherit" : f.kind === "string" ? "empty to inherit" : "a number, or empty to inherit"; i.dataset.keep = "wd:ft:" + f.name;
      i.maxLength = 256;
      i.addEventListener("input", () => { // written as typed, the row and the bar changed in place: no redraw under the next click (audit)
        const t = i.value.trim(); if (t) values[f.name] = t; else delete values[f.name];
        r.classList.toggle("ch", JSON.stringify(values[f.name]) !== JSON.stringify(before[f.name])); markBar();
      });
      ctl.appendChild(i);
    }
    r.appendChild(ctl);
    if (v === undefined) r.appendChild(el("span", "wdeff", inherited(f, aud, e)));
    return r;
  }
  // what Inherit gives: a parent's value, the world's global region, or the flag's default (Region Guard's FLAGS.md)
  function inherited(f, aud, e) {
    if (aud === "owners") return f.def === "build" ? "owners may" : "as visitors";
    if (aud === "members") return "as owners";
    const regions = st.page.regions || [];
    let up = e.draft.parent, seen = 0;
    while (up && seen++ < 32) {
      const p = regions.find((r) => r.dim === e.dim && r.id === up); if (!p) break;
      if (p.flags && p.flags[f.name] !== undefined) return "gives " + show(p.flags[f.name]) + " from " + p.id;
      up = p.parent;
    }
    const g = regions.find((r) => r.dim === e.dim && r.type === "global");
    if (e.draft.type !== "global" && g && g.flags && g.flags[f.name] !== undefined) return "gives " + show(g.flags[f.name]) + " from the global region";
    if (f.def === "build") return "build rule: members may, others not";
    return f.def ? "default " + f.def : "not set";
  }
  const show = (v) => Array.isArray(v) ? v.join(", ") : String(v);
  function markBar() {
    const bar = $("wdbar"), e = st.edit; if (!bar || !e) return;
    const h = hold(); bar.textContent = "";
    const can = admin() && st.page && st.page.live, c = changes(e);
    bar.appendChild(el("span", c.count ? "wdchg" : "mu wdsmall", c.count ? c.count + (c.count === 1 ? " change" : " changes") + " not saved" : "No changes"));
    bar.appendChild(el("span", "grow"));
    if (e.orig.type !== "global") {
      const armed = Date.now() - e.armRemove < ARM_MS, claimRule = e.orig.type === "claim" && me.rank !== "OWNER";
      const rm = key(armed ? "Sure? Remove" : "REMOVE", "dg", () => {
        if (Date.now() - e.armRemove >= ARM_MS) { e.armRemove = Date.now(); markBar(); return; } // armed only for its three seconds (audit)
        e.armRemove = 0; act("/api/regions/remove", { server: e.server, dim: e.dim, id: e.orig.id, version: e.orig.version }, true);
      }, "wd:remove");
      unarm(rm, ARM_MS - (Date.now() - e.armRemove), "REMOVE", "wdkey dg");
      if (!admin()) adminMark(rm); else if (claimRule) { rm.disabled = true; rm.appendChild(el("span", "rk OWNER", "Owner")); } else if (!can) rm.disabled = true;
      bar.appendChild(rm);
    }
    const undo = key("UNDO CHANGES", "ghost", () => openEditor(e.orig), "wd:undo"); undo.disabled = !c.count || e.busy; bar.appendChild(undo);
    const save = key(e.busy ? "SAVING…" : "SAVE", "pri", () => act("/api/regions/save", { server: e.server, dim: e.dim, id: e.orig.id, version: e.orig.version, set: c.set }), "wd:save");
    if (!admin()) adminMark(save); else save.disabled = !can || !c.count || e.busy || e.bad.size > 0;
    if (e.bad.size) bar.insertBefore(el("span", "wdwarn", "A field does not read: put it right to save."), bar.firstChild.nextSibling);
    bar.appendChild(save);
    give(h);
  }
  async function act(path, body, removing) {
    const e = st.edit; if (!e || e.busy) return;
    e.busy = true; st.flash = ""; markBar(); drawDrawer(); // fields held while it is out; an old "Removed …" note goes
    const r = await call(path, body, true);
    e.busy = false;
    if (st.edit !== e) { // closed meanwhile: still read again when it was done (audit)
      if (r.ok) { if (typeof MapRegions !== "undefined") MapRegions.forget(e.server); if (st.server === e.server) loadServer(true); }
      return;
    }
    if (r.ok && typeof MapRegions !== "undefined") MapRegions.forget(e.server); // the map's outlines read again (SU-156)
    if (r.ok && removing) { st.edit = null; st.sel = null; st.flash = r.message; }
    else { e.msg = r.message; e.msgOk = !!r.ok; if (r.ok && r.region) e.nextId = r.region; }
    const part = !r.ok && Array.isArray(r.saved) && r.saved.length > 0; // Region Guard refused part way: what was saved is in game (D783)
    if (part && typeof MapRegions !== "undefined") MapRegions.forget(e.server);
    if (r.ok || r.stale || part) await loadServer(true, true); // the region as it is now (D773): saved, refused as changed meanwhile, or saved in part
    else drawDrawer(); // refused with nothing saved: the changes are kept to put right
  }

  // ---------------------------------------------------------------- drawing
  function draw() {
    if (!host) return;
    const h = hold();
    host.textContent = "";
    const box = el("div", "wdpage"); host.appendChild(box);
    if (st.view === "grid") drawGrid(box); else drawServer(box);
    drawerBox = el("div", "wdwrap" + (st.edit ? "" : " hidden")); host.appendChild(drawerBox);
    drawDrawer();
    give(h);
  }
  function mount(main) {
    host = main; main.classList.add("wdmain");
    draw();
    if (st.view === "grid") loadGrid(); else loadServer(false);
  }
  function unmount(main) { if (host && host === main) { main.classList.remove("wdmain"); host = null; drawerBox = null; } }
  // from More on a phone (D765) and from elsewhere: the grid
  function home() { if (st.edit && changes(st.edit).count) return; st.view = "grid"; st.edit = null; } // unsaved edits stay open (audit)
  // every live tick: a server whose regions changed (its regionsAt moved, in game or from another page) is read again (D781)
  const seenAt = new Map();
  function onLive(live) {
    (live && live.servers || []).forEach((s) => {
      if (typeof s.regionsAt !== "number") return;
      const k = s.server.toLowerCase(), was = seenAt.get(k);
      if (was !== undefined && was !== s.regionsAt && st.edit && st.server && st.server.toLowerCase() === k && host
          && (st.edit.busy || (drawerBox && drawerBox.contains(document.activeElement)))) return; // a save out, or someone typing: on a later tick (audit)
      seenAt.set(k, s.regionsAt);
      if (was === undefined || was === s.regionsAt) return;
      if (typeof MapRegions !== "undefined") MapRegions.regionsChanged(s.server);
      if (host && st.view === "server" && st.server && st.server.toLowerCase() === k) loadServer(true); // unsaved edits are kept by loadServer
      else if (host && st.view === "grid") loadGrid();
    });
  }
  addEventListener("resize", () => { if (host && st.view === "server") drawTable(); });
  // the alerts changed: the grid's tiles and RECENT ALERTS are drawn again (SU-168); never under an open editor
  if (typeof AlertsView !== "undefined") AlertsView.onChange(() => { if (host && st.view === "grid" && !st.edit) draw(); });
  addEventListener("keydown", (ev) => { if (ev.key === "Escape" && host && st.edit) closeEditor(false); });
  return { mount, unmount, home, edit, onLive };
})();
