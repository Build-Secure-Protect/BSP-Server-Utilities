"use strict";
// The SHOPS tab (SU-161; D788 to D814, sets BG A and BH B refined, R1 to R5): every shop on the network as a tile (its
// first cards, on or off, open or when it opens, what it needs, the servers with its stalls), then a shop's page with its
// sections as a tree and three tabs: Cards (drawn as players see them, by the shop's own columns, rows and pages),
// Stalls (every server's, read-only) and Sales. Every rank reads (D788). SU-162 (R9 to R12): Admins and Owners make shops
// and sections (made OFF, D795, D796), copy them (D797), turn them on and off (D805), change every setting on one page
// (D803, D804) and delete them with two confirms (D807); each change goes as it is made with the stamp it was read at
// (D794). SU-163 (R6 to R8): the card editor on a computer (D814): the item browser, dragging items onto the page and
// cards about it or into the bin (D798, D799), and a drawer with every card setting (D800 to D802). Text from the network
// is set with textContent only.

const ShopsTab = (() => {
  const KINDS = { shop: ["Shop", "#7fb2ff"], sub: ["Section", "#9aa3b5"], rotating: ["Rotating", "#b58cff"], offer: ["Special offer", "#ffd23a"] };
  const TIER = ["C", "G", "D", "N", "I"], TIER_NAME = ["copper", "gold", "diamond", "netherite", "illyrium"];
  const VENUE = { stall: ["Market Stall", "Market Stalls"], post: ["Vendor Post", "Vendor Posts"], display: ["Stall Display", "Stall Displays"] };
  const FILTERS = ["All", "On", "Off", "Network", "Rotating", "Offer"];
  const REFRESH_MS = 60000; // D811: until BSP-Core says when shops change, open pages read again every minute
  const st = { view: "grid", grid: null, gridErr: "", q: "", filter: "All", id: null, page: null, pageErr: "", tab: "Cards", pg: 0,
    hidden: true, pool: true, sales: null, salesErr: "", days: 7, venuesOf: "shop", readAt: 0, tok: 0, msg: null, busy: false, armed: { key: "", at: 0, step: 0 } };
  let host = null;

  // ---------------------------------------------------------------- words
  const num = (n) => Number(n || 0).toLocaleString("en-GB");
  function ago(ms) {
    const m = Math.max(0, Math.round(ms / 60000));
    return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? Math.floor(m / 60) + " h ago" : Math.floor(m / 1440) + " d ago";
  }
  function inTime(ms) {
    const m = Math.max(0, Math.round(ms / 60000));
    return m < 60 ? m + " min" : m < 1440 ? Math.floor(m / 60) + " h " + (m % 60) + " min" : Math.floor(m / 1440) + " d " + Math.floor((m % 1440) / 60) + " h";
  }
  const pad = (n) => String(n).padStart(2, "0");
  // a time in the shop's hours: minutes on the real clock, or ticks of the Minecraft day (0 is 06:00)
  function clock(v, mode) {
    if (mode === "GAME") { const h = (Math.floor(v / 1000) + 6) % 24, m = Math.min(59, Math.round((v % 1000) * 60 / 1000)); return pad(h) + ":" + pad(m); }
    return pad(Math.floor(v / 60) % 24) + ":" + pad(v % 60);
  }
  function hoursText(h) {
    if (!h || h.mode === "ALWAYS" || h.open === h.close) return "Always open";
    return (h.mode === "GAME" ? "Minecraft day " : "Real clock ") + clock(h.open, h.mode) + " to " + clock(h.close, h.mode);
  }
  function price(verb, v, cur) { return verb + " " + num(v) + (cur >= 0 && cur < TIER.length ? " " + TIER[cur] : ""); }
  function needsText(r) {
    if (!r || (!r.permission && !(r.playtimeHours > 0))) return "";
    const parts = [];
    if (r.permission) parts.push("permission " + r.permission);
    if (r.playtimeHours > 0) parts.push(r.playtimeHours + " hours played");
    return (r.reason ? r.reason + ": " : "") + parts.join(" and ") + (r.hide ? " (hidden from others)" : " (shown locked to others)");
  }
  function refillText(rule) {
    const r = String(rule || "never");
    if (r === "never") return "never fills again";
    if (r === "same") return "as everyone's";
    if (r === "rotation") return "at each draw";
    if (/^\d+[mhd]$/.test(r)) return "after " + r.replace("m", " min").replace("h", " h").replace("d", " d") + " from running out";
    return "every " + r.replace(" ", " at ");
  }
  function kindTag(kind) {
    const k = KINDS[kind] || [kind, "#9aa3b5"], t = el("span", "wdkt", k[0]);
    t.style.color = k[1]; t.style.borderColor = k[1] + "66"; t.style.background = k[1] + "22"; return t;
  }
  function pic(item, v, size) {
    const ix = typeof Pictures !== "undefined" ? Pictures.indexOf(item, v) : -1;
    const p = ix >= 0 ? Pictures.icon(ix, size) : null;
    if (p) return p;
    const l = el("span", "shletters", String(item || "?").replace(/^.*:/, "").split("_").map((w) => w.charAt(0)).join("").slice(0, 2).toUpperCase());
    l.style.width = l.style.height = size + "px"; return l;
  }
  function itemName(item, v) {
    const ix = typeof Pictures !== "undefined" ? Pictures.indexOf(item, v) : -1, cat = typeof Pictures !== "undefined" ? Pictures.ready() : null;
    if (cat && ix >= 0 && cat.items[ix] && cat.items[ix].name) return cat.items[ix].name;
    const w = String(item || "").replace(/^.*:/, "").replace(/_/g, " ");
    return w.charAt(0).toUpperCase() + w.slice(1);
  }
  function key(text, cls, fn) { const b = el("button", "wdkey" + (cls ? " " + cls : ""), text); b.type = "button"; if (fn) b.addEventListener("click", fn); return b; }
  function lamp(s) {
    if (!s.enabled) return el("span", "shlamp off", "OFF: closed for changes");
    if (s.openNow) return el("span", "shlamp op", "Open now");
    return el("span", "shlamp cl", "Opens " + (s.opensAt || "later"));
  }
  function stateSwitch(on) {
    const w = el("span", "shsw" + (on ? " on" : "")); w.title = on ? "On" : "Off: closed for changes"; w.setAttribute("aria-label", w.title); return w;
  }
  const netMark = () => el("span", "shnet", "Network");

  // ---------------------------------------------------------------- reading
  async function loadGrid(refresh) {
    const tok = ++st.tok;
    const r = await call("/api/shops/grid", { refresh: !!refresh });
    if (tok !== st.tok) return;
    if (r.ok) { st.grid = r; st.gridErr = ""; st.readAt = Date.now(); } else st.gridErr = r.message || "The shops could not be read.";
    draw();
  }
  async function loadShop(refresh) {
    const id = st.id, tok = ++st.tok;
    const r = await call("/api/shops/shop", { id, refresh: !!refresh });
    if (tok !== st.tok || st.id !== id) return;
    if (r.ok) { st.page = r; st.pageErr = ""; st.readAt = Date.now(); } else { st.pageErr = r.message || "The shop could not be read."; }
    draw();
    if (st.tab === "Sales" && st.page && (st.salesFor !== st.page.shop.id + "|" + st.days)) loadSales();
  }
  async function loadSales() {
    if (!st.page) return;
    const id = st.page.shop.id, days = st.days;
    st.sales = null; st.salesErr = ""; st.salesFor = id + "|" + days; drawBody();
    const r = await call("/api/shops/sales", { id, days });
    if (!st.page || st.page.shop.id !== id || st.days !== days) return;
    if (r.ok) st.sales = r; else st.salesErr = r.message || "The sales could not be read.";
    drawBody();
  }
  function open(id, view) { st.view = view || "shop"; st.sel = null; ed.removing = null; st.armed = { key: "", at: 0, step: 0 }; st.id = id; st.page = null; st.pageErr = ""; st.pg = 0; st.sales = null; draw(); loadShop(false); }
  function back() { st.view = "grid"; st.id = null; st.page = null; st.msg = null; draw(); loadGrid(false); }

  // ---------------------------------------------------------------- drawing
  function draw() {
    if (!host) return;
    const h = hold();
    const a = document.activeElement, typing = a && a.tagName === "INPUT" && a.dataset.keep && host.contains(a) ? [a.dataset.keep, a.value] : null; // before the page goes (audit)
    host.textContent = "";
    const box = el("div", "wdpage shpage"); host.appendChild(box);
    if (st.view === "grid") drawGrid(box); else if (st.view === "settings" && st.page) drawSettings(box);
    else if (st.view === "edit" && st.page && onComputer()) drawEditor(box); else drawShop(box);
    give(h);
    if (typing) { const n = [...host.querySelectorAll("input")].find((x) => x.dataset.keep === typing[0]); if (n && n.value !== typing[1]) n.value = typing[1]; } // what is being typed survives a read
  }

  // R1: the grid
  function matches(s) {
    const f = st.filter;
    if (f === "On" && !s.enabled) return false;
    if (f === "Off" && s.enabled) return false;
    if (f === "Network" && !s.remote) return false;
    if (f === "Rotating" && s.kind !== "rotating") return false;
    if (f === "Offer" && s.kind !== "offer") return false;
    const q = st.q.trim().toLowerCase();
    if (!q) return true;
    const hit = (x) => String(x || "").toLowerCase().includes(q);
    return hit(s.name) || hit(s.id) || (s.sections || []).some((c) => hit(c.name) || hit(c.id));
  }
  function drawGrid(box) {
    const head = el("div", "wdhead");
    head.appendChild(el("span", "wdtitle", "SHOPS"));
    const all = st.grid ? st.grid.shops : [];
    head.appendChild(el("span", "mu wdsmall", all.length ? all.length + " shops · " + all.filter((s) => s.enabled).length + " on" : ""));
    head.appendChild(el("span", "grow"));
    const q = el("input", "search wdsearch"); q.type = "text"; q.placeholder = "Search shops and sections"; q.value = st.q; q.dataset.keep = "sh:q";
    q.addEventListener("input", () => { st.q = q.value; drawTiles(); });
    head.appendChild(q);
    head.appendChild(adminMark(key("+ NEW SHOP", "pri", () => newShop())));
    box.appendChild(head);
    flash(box);
    const chips = el("div", "wdchips");
    FILTERS.forEach((f) => {
      const n = all.filter((s) => { const was = st.filter, wq = st.q; st.filter = f; st.q = ""; const m = matches(s); st.filter = was; st.q = wq; return m; }).length;
      const c = el("button", "wdchip" + (st.filter === f ? " on" : "")); c.type = "button"; c.dataset.keep = "sh:f:" + f;
      c.appendChild(document.createTextNode(f + " ")); c.appendChild(el("span", "mu mono", String(n)));
      c.addEventListener("click", () => { st.filter = f; draw(); });
      chips.appendChild(c);
    });
    chips.appendChild(el("span", "grow"));
    if (st.grid) chips.appendChild(el("span", "mu wdsmall", "A to Z · answered by " + st.grid.server + " " + ago(Date.now() - st.grid.at)));
    chips.appendChild(key("REFRESH", "ghost", () => loadGrid(true)));
    box.appendChild(chips);
    if (st.gridErr) box.appendChild(el("div", "err", st.gridErr));
    if (!st.grid) { if (!st.gridErr) box.appendChild(el("div", "note", "Reading the shops…")); return; }
    const errs = st.grid.venueErrors || {};
    Object.keys(errs).forEach((s) => box.appendChild(el("div", "wderr", s + " did not say where its stalls are: " + errs[s])));
    const tiles = el("div", "wdtiles shtiles"); tiles.id = "shtiles"; box.appendChild(tiles);
    drawTiles();
  }
  function drawTiles() {
    const tiles = $("shtiles"); if (!tiles || !st.grid) return;
    tiles.textContent = "";
    const list = st.grid.shops.filter(matches);
    list.forEach((s) => tiles.appendChild(tile(s)));
    if (!list.length) tiles.appendChild(el("div", "note", st.grid.shops.length ? "No shop matches." : "There are no shops yet."));
  }
  function tile(s) {
    const t = el("button", "wdtile shtile" + (s.enabled ? "" : " shoff")); t.type = "button"; t.dataset.keep = "sh:tile:" + s.id;
    const top = el("div", "wdtrow");
    const nm = el("div", "grow"); nm.appendChild(el("div", "wdname", s.name || s.id)); nm.appendChild(el("div", "mono mu shid", s.id));
    top.appendChild(nm); top.appendChild(switchFor(s, s.id)); t.appendChild(top);
    const meta = el("div", "wdtrow shmeta"); meta.appendChild(kindTag(s.kind));
    const secs = (s.sections || []).length;
    meta.appendChild(el("span", "mu wdsmall", num(s.cards) + (s.cards === 1 ? " card" : " cards") + (secs ? " · " + secs + (secs === 1 ? " section" : " sections") : "")));
    if (s.remote) meta.appendChild(netMark());
    t.appendChild(meta);
    const pv = el("div", "shpeek");
    for (let i = 0; i < 4; i++) { const c = el("span"); const p = (s.peek || [])[i]; if (p) c.appendChild(pic(p.item, p.v, 24)); pv.appendChild(c); }
    t.appendChild(pv);
    t.appendChild(lamp(s));
    if (s.kind === "offer" && s.offerPercent > 0) t.appendChild(el("div", "mu wdsmall", s.offerPercent + "% off" + (s.offerEnd ? " · ends " + new Date(s.offerEnd).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "")));
    if (s.kind === "rotating") t.appendChild(el("div", "mu wdsmall", (s.displaySlots || 0) + " on show" + (s.nextChange ? " · next change in " + inTime(s.nextChange - Date.now()) : "")));
    const need = needsText(s.requires); if (need) t.appendChild(el("div", "shneed", "Needs " + need));
    const sv = el("div", "shsrvs");
    (s.venues || []).forEach((v) => sv.appendChild(serverChip(v)));
    if (!(s.venues || []).length) sv.appendChild(el("span", "shsrv none", "No stalls known"));
    t.appendChild(sv);
    t.addEventListener("click", () => open(s.id));
    return t;
  }
  function serverChip(v) {
    const total = (v.stall || 0) + (v.post || 0) + (v.display || 0);
    const c = el("span", "shsrv" + (v.live ? "" : " gone")); c.appendChild(document.createTextNode(v.server + " ")); c.appendChild(el("b", null, String(total)));
    const lines = [];
    ["stall", "post", "display"].forEach((k) => { if (v[k]) lines.push(v[k] + " " + VENUE[k][v[k] === 1 ? 0 : 1]); });
    if (v.forSections) lines.push(v.forSections + " of them for its sections");
    if (!v.live) lines.push("off · last heard " + ago(Date.now() - (v.heardAt || 0)));
    const tip = el("span", "shtip"); tip.appendChild(el("b", null, v.server + ": " + total)); lines.forEach((l) => tip.appendChild(el("span", null, l)));
    c.appendChild(tip); c.setAttribute("aria-label", v.server + ": " + lines.join(", "));
    return c;
  }

  // R2 to R5: a shop's page
  function drawShop(box) {
    const p = st.page;
    const head = el("div", "wdhead");
    const crumb = el("span", "wdcrumb shcrumb");
    const up = key("SHOPS", "link", back); crumb.appendChild(up);
    (p ? p.path : [{ id: st.id, name: st.id }]).forEach((x, i, arr) => {
      crumb.appendChild(el("span", "mu", "›"));
      if (i === arr.length - 1) crumb.appendChild(el("b", null, x.name || x.id));
      else crumb.appendChild(key(x.name || x.id, "link", () => open(x.id)));
    });
    head.appendChild(crumb);
    if (p) head.appendChild(kindTag(p.shop.kind));
    head.appendChild(el("span", "grow"));
    if (p) {
      head.appendChild(switchFor(p.shop, p.top)); head.appendChild(lamp(p.shop));
      head.appendChild(adminMark(key("+ SECTION", null, () => newSection())));
      head.appendChild(key("SETTINGS", null, () => { st.view = "settings"; st.msg = null; draw(); }));
      head.appendChild(copyKey(p));
      head.appendChild(key("EXPORT", null, () => exportShop()));
      const edit = adminMark(key("EDIT", "pri", () => { st.view = "edit"; st.sel = null; st.msg = null; draw(); }));
      if (!onComputer() && !edit.disabled) { edit.disabled = true; edit.title = "The card editor is for a computer."; }
      head.appendChild(edit);
    }
    head.appendChild(key("REFRESH", "ghost", () => loadShop(true)));
    box.appendChild(head);
    if (st.pageErr) box.appendChild(el("div", "err", st.pageErr));
    if (!p) { if (!st.pageErr) box.appendChild(el("div", "note", "Reading the shop…")); return; }
    offBar(box, p);
    flash(box);
    const wrap = el("div", "shwrap");
    wrap.appendChild(tree(p));
    const main = el("div", "shmain");
    const tabs = el("div", "shtabs");
    const cards = (p.shop.cards || []).length, stalls = venuesFor(p, "shop").length;
    [["Cards", cards], ["Stalls", stalls], ["Sales", ""]].forEach(([t, n]) => {
      const b = el("button", "shtab" + (st.tab === t ? " on" : ""), t + (n !== "" ? " " + n : "")); b.type = "button"; b.dataset.keep = "sh:tab:" + t;
      b.addEventListener("click", () => { st.tab = t; draw(); if (t === "Sales" && !st.sales) loadSales(); });
      tabs.appendChild(b);
    });
    main.appendChild(tabs);
    const body = el("div", "shbody"); body.id = "shbody"; main.appendChild(body);
    wrap.appendChild(main);
    box.appendChild(wrap);
    drawBody();
  }
  // the sections: a tree on a computer, a dropdown on a phone (D814)
  function tree(p) {
    const t = el("div", "shtree");
    const n = Math.max(0, p.tree.length - 1);
    t.appendChild(el("div", "shtreeh", n ? "Sections " + n : "No sections"));
    p.tree.forEach((n) => {
      const b = el("button", "shtn" + (n.id === p.shop.id ? " on" : "")); b.type = "button"; b.dataset.keep = "sh:tn:" + n.id;
      b.style.paddingLeft = (10 + n.depth * 14) + "px";
      b.appendChild(el("span", "grow", n.name || n.id)); b.appendChild(el("span", "mono mu", String(n.cards ?? "")));
      b.addEventListener("click", () => open(n.id));
      t.appendChild(b);
    });
    const sel = el("select", "shtreesel"); sel.setAttribute("aria-label", "Section");
    p.tree.forEach((n) => { const o = el("option", null, " ".repeat(n.depth * 3) + (n.name || n.id) + " (" + (n.cards ?? 0) + ")"); o.value = n.id; if (n.id === p.shop.id) o.selected = true; sel.appendChild(o); });
    sel.addEventListener("change", () => open(sel.value));
    t.appendChild(sel);
    return t;
  }
  function drawBody() {
    const body = $("shbody"); if (!body || !st.page) return;
    body.textContent = "";
    if (st.tab === "Cards") drawCards(body);
    else if (st.tab === "Stalls") drawStalls(body);
    else drawSales(body);
  }
  // the cards a row: the shop's own, or automatic as BSP-Core works it out from the longest price (Shop.effectiveColumns)
  function columns(s) {
    if (s.columns >= 4) return Math.min(7, s.columns);
    let longest = 6;
    (s.cards || []).forEach((c) => {
      if (c.section) return;
      if (c.buy >= 0) longest = Math.max(longest, price("buy", c.buy, c.currency).length);
      if (c.sell >= 0) longest = Math.max(longest, price("sell", c.sell, c.currency).length);
    });
    const need = Math.ceil(longest * 6 * 0.75) + 4;
    return Math.max(4, Math.min(7, Math.floor(195 / (need + 1))));
  }
  function drawCards(body) {
    const s = st.page.shop, byId = new Map(st.page.tree.map((n) => [n.id, n]));
    const info = el("div", "wdtrow shinfo");
    info.appendChild(el("span", "mono mu", s.id));
    const parts = [columns(s) + " a row" + (s.columns >= 4 ? "" : " (automatic)"), Math.max(2, Math.min(3, s.rows || 3)) + " rows", styleName(s.style)];
    if (s.remote) parts.push("on the Shopping Network"); if (s.buyOnly) parts.push("buy only");
    parts.push(hoursText(s.hours).toLowerCase());
    info.appendChild(el("span", "mu wdsmall grow", "· " + parts.join(" · ")));
    const hid = el("button", "wdchip" + (st.hidden ? " on" : ""), "Show hidden"); hid.type = "button"; hid.dataset.keep = "sh:hid";
    hid.addEventListener("click", () => { st.hidden = !st.hidden; st.pg = 0; drawBody(); });
    info.appendChild(hid);
    body.appendChild(info);
    const need = needsText(s.requires); if (need) body.appendChild(el("div", "shneed", "The whole shop needs " + need + "; its sections with it."));
    if (s.kind === "offer" && s.offerPercent > 0) body.appendChild(el("div", "shneed", s.offerPercent + "% off every card" + (s.offerEnd ? ", until " + new Date(s.offerEnd).toLocaleString("en-GB") : ", with no end")));
    const rot = s.kind === "rotating";
    if (rot) body.appendChild(drawStrip(s));
    let list = (s.cards || []).filter((c) => st.hidden || !c.hidden);
    const shown = new Set(s.onShow || []);
    if (rot) {
      const row = el("div", "wdchips");
      [["The pool", true], ["On show only", false]].forEach(([l, v]) => { const c = el("button", "wdchip" + (st.pool === v ? " on" : ""), l); c.type = "button"; c.addEventListener("click", () => { st.pool = v; st.pg = 0; drawBody(); }); row.appendChild(c); });
      row.appendChild(el("span", "grow")); row.appendChild(el("span", "mu wdsmall", "w: how likely the draw picks the card (0 never)"));
      body.appendChild(row);
      if (!st.pool) list = list.filter((c) => shown.has(c.i));
    }
    const cols = columns(s), per = rot && st.pool ? list.length || 1 : cols * Math.max(2, Math.min(3, s.rows || 3)); // the pool on one page; a page as players see it
    const pages = Math.max(1, Math.ceil(list.length / per));
    st.pg = Math.min(st.pg, pages - 1);
    const shownCols = matchMedia("(min-width: 600px)").matches ? cols : Math.min(cols, 4); // a phone wraps the page four a row; a page holds what it holds in game
    const grid = el("div", "shcards"); grid.style.gridTemplateColumns = "repeat(" + shownCols + ", minmax(0, 1fr))";
    list.slice(st.pg * per, st.pg * per + per).forEach((c) => grid.appendChild(card(c, s, byId, rot, shown)));
    if (!list.length) grid.appendChild(el("div", "note", (s.cards || []).length ? "Every card here is hidden." : "This shop has no cards yet."));
    body.appendChild(grid);
    if (pages > 1) {
      const pg = el("div", "shpager");
      pg.appendChild(key("‹", "ghost", () => { st.pg = Math.max(0, st.pg - 1); drawBody(); }));
      pg.appendChild(el("span", "mu wdsmall", "Page " + (st.pg + 1) + " of " + pages + " · " + list.length + (list.length === 1 ? " card" : " cards")));
      pg.appendChild(key("›", "ghost", () => { st.pg = Math.min(pages - 1, st.pg + 1); drawBody(); }));
      body.appendChild(pg);
    }
    body.appendChild(el("div", "note", "Blue cards open a section. Dimmed: hidden from every player. A padlock: shown locked until the player meets what it needs. "
      + "C, G, D, N, I: copper, gold, diamond, netherite and illyrium coins; no letter: pieces."));
  }
  function styleName(st2) { return { cash_machine: "Cash Machine", passbook: "Passbook", market_board: "Market Board" }[st2] || st2 || "Cash Machine"; }
  function drawStrip(s) {
    const box = el("div", "shstrip");
    const cell = (label, value, cls) => { const d = el("div"); d.appendChild(el("div", "shlab", label)); d.appendChild(el("b", cls || null, value)); box.appendChild(d); };
    cell("ON SHOW", (s.onShow || []).length + " of " + (s.cards || []).length);
    cell("CHANGES", s.rotateAt && s.rotateAt !== "none" ? "Every " + s.rotateAt.charAt(0).toUpperCase() + s.rotateAt.slice(1).replace(" ", " at ") + (st.page.timeZone ? " (" + st.page.timeZone + ")" : "") : "Every " + (s.intervalHours || 0) + " h");
    if (s.nextChange) cell("NEXT", "in " + inTime(s.nextChange - Date.now()), "gd");
    cell("NO REPEATS", s.noRepeat ? "On" + (typeof s.cycleLeft === "number" ? " · " + s.cycleLeft + " left this cycle" : "") : "Off", s.noRepeat ? "tl" : null);
    cell("TELLS EVERY PLAYER", s.broadcast ? "On" : "Off", s.broadcast ? "tl" : null);
    if ((s.draws || []).length) { // BSP-Core keeps only the draw on show (D822)
      const d = el("div", "shdraws"); d.appendChild(el("div", "shlab", "ON SHOW SINCE"));
      s.draws.slice(0, 1).forEach((dr) => {
        const r = el("div", "wdtrow"); r.appendChild(el("span", "mu wdsmall", new Date(dr.at).toLocaleString("en-GB", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })));
        (dr.cards || []).forEach((i) => { const c = (s.cards || []).find((x) => x.i === i); if (c) { const p = pic(c.item, c.v, 18); p.title = itemName(c.item, c.v); r.appendChild(p); } });
        d.appendChild(r);
      });
      box.appendChild(d);
    }
    return box;
  }
  function card(c, s, byId, rot, shown, editing) {
    const sec = !!c.section, locked = !!needsText(c.requires);
    const d = el("div", "shcard" + (sec ? " sec" : "") + (c.hidden ? " hid" : "") + (locked ? " lock" : "") + (rot && shown.has(c.i) ? " lit" : rot ? " dimc" : ""));
    if (c.count > 1 && !sec) d.appendChild(el("span", "shlot", "×" + c.count));
    if (rot && !sec) d.appendChild(el("span", "shwt", "w" + (c.weight ?? 1)));
    if (locked) d.appendChild(el("span", "shlk", "🔒"));
    if (c.hidden) d.appendChild(el("span", "shhid", "HIDDEN"));
    d.appendChild(pic(c.item, c.v, 32));
    const section = sec ? byId.get(c.section) : null;
    d.appendChild(el("span", "shnm", sec ? (section ? section.name : c.section) : itemName(c.item, c.v)));
    if (sec) d.appendChild(el("span", "shpr sec", section ? "› " + (section.cards ?? 0) + (section.cards === 1 ? " card" : " cards") : "› missing"));
    else {
      if (c.buy >= 0) d.appendChild(el("span", "shpr", price("buy", s.kind === "offer" && s.offerPercent > 0 ? Math.floor(c.buy * (100 - s.offerPercent) / 100) : c.buy, c.currency)));
      if (c.sell >= 0) d.appendChild(el("span", "shpr s", price("sell", c.sell, c.currency)));
      if (c.buy < 0 && c.sell < 0) d.appendChild(el("span", "shpr mu", "nothing set"));
    }
    d.title = describe(c, s, section);
    if (sec && section && !editing) { d.classList.add("click"); d.tabIndex = 0; d.addEventListener("click", () => open(c.section)); d.addEventListener("keydown", (e) => { if (e.key === "Enter") open(c.section); }); }
    return d;
  }
  // a card's full settings, shown when it is hovered (R2)
  function describe(c, s, section) {
    if (c.section) return "Opens the section " + (section ? section.name + " (" + c.section + ")" : c.section + ", which is missing");
    const L = [itemName(c.item, c.v) + " · card " + (c.i + 1) + (c.count > 1 ? " · lots of " + c.count : "")];
    const cur = c.currency >= 0 ? TIER_NAME[c.currency] + " coins" : "pieces";
    L.push((c.buy >= 0 ? "buy " + num(c.buy) : "buying off") + " · " + (c.sell >= 0 ? "sell " + num(c.sell) : "selling off") + " · in " + cur);
    const lim = (all, each, left, rule, eachRule, word) => {
      if (all < 0 && each < 0) return "no " + word + " limit";
      const parts = [];
      if (all >= 0) parts.push(num(all) + " for everyone" + (typeof left === "number" && left >= 0 ? " (" + num(left) + " left)" : "") + ", " + refillText(rule));
      if (each >= 0) parts.push(num(each) + " each player, " + refillText(eachRule));
      return word + ": " + parts.join("; ");
    };
    if (c.buy >= 0) L.push(lim(c.stockGlobal, c.stockPlayer, c.stockLeft, c.stockRefill, c.stockPlayerRefill, "stock"));
    if (c.sell >= 0) L.push(lim(c.sellGlobal, c.sellPlayer, c.sellLeft, c.sellRefill, c.sellPlayerRefill, "sell room"));
    if (c.sell >= 0) L.push(c.match === "item" ? "selling takes the item with any data" : "selling takes this exact item");
    if (c.instant) L.push("instant: no confirm");
    if (c.hidden) L.push("hidden from every player");
    if (s.kind === "rotating") L.push("weight " + (c.weight ?? 1));
    const need = needsText(c.requires); if (need) L.push("needs " + need);
    (c.note || []).forEach((n) => L.push("note: " + n));
    L.push(c.item + (c.v ? " (" + c.v + ")" : ""));
    return L.join("\n");
  }
  function venuesFor(p, which) {
    const ids = new Set(which === "section" ? [p.shop.id] : p.tree.map((n) => n.id));
    return (p.venues || []).filter((v) => ids.has(v.shop));
  }
  function drawStalls(body) {
    const p = st.page;
    const row = el("div", "wdchips");
    [["shop", "Whole shop"], ["section", p.shop.id === p.top ? "This shop only" : "This section"]].forEach(([k, l]) => {
      const c = el("button", "wdchip" + (st.venuesOf === k ? " on" : ""), l); c.type = "button"; c.addEventListener("click", () => { st.venuesOf = k; drawBody(); }); row.appendChild(c);
    });
    body.appendChild(row);
    Object.keys(p.venueErrors || {}).forEach((s) => body.appendChild(el("div", "wderr", s + " did not say where its stalls are: " + p.venueErrors[s])));
    const list = venuesFor(p, st.venuesOf).sort((a, b) => a.server.localeCompare(b.server) || a.kind.localeCompare(b.kind) || a.x - b.x);
    if (!list.length) body.appendChild(el("div", "note", "No stall, Vendor Post or Stall Display is known for this " + (st.venuesOf === "section" ? "one" : "shop") + "."));
    else {
      const wrapT = el("div", "wdtable"); const t = el("table", "wdtbl"); const hr = el("tr");
      ["Server", "Kind", "World", "x y z", "Serves", "Hours"].forEach((h) => hr.appendChild(el("th", null, h)));
      const thead = el("thead"); thead.appendChild(hr); t.appendChild(thead);
      const tb = el("tbody");
      list.forEach((v) => {
        const tr = el("tr", v.live ? "" : "shgone");
        tr.appendChild(el("td", null, v.server + (v.live ? "" : " (off, " + ago(Date.now() - (v.heardAt || 0)) + ")")));
        tr.appendChild(el("td", null, (VENUE[v.kind] || [v.kind])[0]));
        tr.appendChild(el("td", null, dimName(v.dim)));
        tr.appendChild(el("td", "mono", v.x + " " + v.y + " " + v.z));
        tr.appendChild(el("td", v.shop === p.shop.id ? "tl" : null, v.shopName || v.shop));
        tr.appendChild(el("td", "mu", v.kind === "display" ? (v.pin ? "pinned: " + itemName(v.pin) : "shows its cards in turn") : v.hours && v.hours.mode !== "ALWAYS" ? hoursText(v.hours) : "the shop's"));
        tb.appendChild(tr);
      });
      t.appendChild(tb); wrapT.appendChild(t); body.appendChild(wrapT);
    }
    body.appendChild(el("div", "note", "Each server answers for its own stalls. A stall's own hours win over the shop's and are changed at the stall in game. "
      + "Stalls placed before BSP-Core kept this list show up the first time their chunk loads."));
  }
  function dimName(d) {
    d = String(d || "");
    if (d === "minecraft:overworld") return "Overworld";
    if (d === "minecraft:the_nether") return "Nether";
    if (d === "minecraft:the_end") return "End";
    const w = d.replace(/^.*:/, "").replace(/_/g, " ");
    return w.charAt(0).toUpperCase() + w.slice(1);
  }
  function drawSales(body) {
    const row = el("div", "wdchips");
    [1, 7, 30].forEach((d) => { const c = el("button", "wdchip" + (st.days === d ? " on" : ""), d === 1 ? "1 day" : d + " days"); c.type = "button"; c.addEventListener("click", () => { st.days = d; loadSales(); }); row.appendChild(c); });
    row.appendChild(el("span", "grow"));
    row.appendChild(el("span", "mu wdsmall", (st.page.shop.id === st.page.top ? "The whole shop with its sections" : "This section and those under it") + " · from BSP-Core's shop report"));
    body.appendChild(row);
    if (st.salesErr) { body.appendChild(el("div", "err", st.salesErr)); return; }
    const r = st.sales;
    if (!r) { body.appendChild(el("div", "note", "Reading the sales…")); return; }
    const tot = el("div", "shtotals");
    const big = (label, v, cls) => { const d = el("div"); d.appendChild(el("div", "shlab", label)); d.appendChild(el("div", "shbig " + (cls || ""), v)); tot.appendChild(d); };
    big("PIECES PAID IN BY PLAYERS", num(r.piecesIn), "tl"); big("PIECES PAID OUT TO PLAYERS", num(r.piecesOut), "gd"); big("NET", num((r.piecesIn || 0) - (r.piecesOut || 0)));
    big("COINS IN", coins(r.coinsIn) || "none"); big("COINS OUT", coins(r.coinsOut) || "none"); // D820: per tier, never turned into pieces
    body.appendChild(tot);
    if (r.capped) body.appendChild(el("div", "note", "The bank log keeps fewer days than that: these are all the days it has (" + r.days + ")."));
    const coinSum = (x) => (x || []).reduce((a, b) => a + (+b || 0), 0);
    const rows = (r.rows || []).slice().sort((a, b) => (b.piecesIn + b.piecesOut + coinSum(b.coinsIn) + coinSum(b.coinsOut)) - (a.piecesIn + a.piecesOut + coinSum(a.coinsIn) + coinSum(a.coinsOut)));
    if (!rows.length) { body.appendChild(el("div", "note", "Nothing was bought or sold here in that time.")); return; }
    const wrapT = el("div", "wdtable"); const t = el("table", "wdtbl"); const hr = el("tr");
    ["", "Card", "Section", "Bought", "Sold", "Pieces in", "Pieces out", "Coins in", "Coins out"].forEach((h, i) => { const th = el("th", i > 2 ? "num" : null, h); hr.appendChild(th); });
    const thead = el("thead"); thead.appendChild(hr); t.appendChild(thead);
    const tb = el("tbody");
    rows.forEach((x) => {
      const tr = el("tr"); const td = el("td"); td.appendChild(pic(x.item, x.v, 20)); tr.appendChild(td);
      tr.appendChild(el("td", null, itemName(x.item, x.v)));
      tr.appendChild(el("td", "mu", x.shopName || x.shop));
      tr.appendChild(el("td", "num mono", num(x.bought))); tr.appendChild(el("td", "num mono", num(x.sold)));
      tr.appendChild(el("td", "num mono", num(x.piecesIn))); tr.appendChild(el("td", "num mono", num(x.piecesOut)));
      tr.appendChild(el("td", "num mono", coins(x.coinsIn) || "–")); tr.appendChild(el("td", "num mono", coins(x.coinsOut) || "–"));
      tb.appendChild(tr);
    });
    t.appendChild(tb); wrapT.appendChild(t); body.appendChild(wrapT);
    if ((r.sellers || []).length) { // D821: the ten players paid most
      const box = el("div", "shsellers"); box.appendChild(el("div", "shlab", "PLAYERS PAID MOST"));
      r.sellers.forEach((p, k) => { const l = el("div", "wdtrow"); l.appendChild(el("span", "mono dim", (k + 1) + ".")); l.appendChild(el("span", "grow", p.name)); l.appendChild(el("span", "mono", num(p.pieces) + " pieces")); box.appendChild(l); });
      body.appendChild(box);
    }
  }
  // five counts, copper to illyrium, as "3 G · 1 D" (D820)
  function coins(list) {
    return (Array.isArray(list) ? list : []).map((n, k) => (+n > 0 ? num(n) + " " + TIER[k] : "")).filter((x) => x).join(" · ");
  }

  // ---------------------------------------------------------------- changes (SU-162)
  const admin = () => typeof me !== "undefined" && me && (me.rank === "ADMIN" || me.rank === "OWNER");
  function adminMark(b) { if (!admin()) { b.disabled = true; b.appendChild(el("span", "rk ADMIN", "Admin")); b.title = "Changing shops is for Admins and Owners."; } return b; }
  function say(text, ok) { st.msg = text ? { text, ok: !!ok, at: Date.now() } : null; }
  function flash(box) { if (st.msg && Date.now() - st.msg.at < 12000) box.appendChild(el("div", st.msg.ok ? "shok" : "err", st.msg.text)); }
  // two taps within three seconds (SURE?); a step count for the delete's two (D807)
  function sure(k, steps) {
    const a = st.armed, now = Date.now();
    if (a.key === k && now - a.at < 3000) { a.step++; a.at = now; if (a.step >= steps) { st.armed = { key: "", at: 0, step: 0 }; return true; } return false; }
    st.armed = { key: k, at: now, step: 1 }; return steps <= 0;
  }
  // a shop's newest stamp the page knows (a window opened before a change sends the stamp of now)
  function stampOf(id, fallback) {
    if (st.page && st.page.shop.id === id) return st.page.shop.edited;
    const t = st.grid ? st.grid.shops.find((x) => x.id === id) : null;
    return t ? t.edited : fallback;
  }
  // a shop and every section under it, in tree order (the tree is depth first)
  function subtree(p, id) {
    const i = p.tree.findIndex((n) => n.id === id); if (i < 0) return [];
    const out = [p.tree[i]];
    for (let k = i + 1; k < p.tree.length && p.tree[k].depth > p.tree[i].depth; k++) out.push(p.tree[k]);
    return out;
  }
  const slug = (t) => String(t || "").toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 32);
  function takenIds() {
    const ids = new Set();
    (st.grid ? st.grid.shops : []).forEach((s) => { ids.add(s.id); (s.sections || []).forEach((c) => ids.add(c.id)); });
    if (st.page) st.page.tree.forEach((n) => ids.add(n.id));
    return ids;
  }
  // a change sent; the answer said; the page read again (D794). A stale one says why and shows the shop as it is now.
  // changes go one after another: one made while another is saving waits, then goes with the stamp that one brought back
  let queue = Promise.resolve();
  function change(path, body, after) {
    const run = queue.then(() => send(path, body, after));
    queue = run.catch(() => null);
    return run;
  }
  // a stamp moved on only by the panel's own last change: a change decided before it goes with the stamp it brought back,
  // never with one a read brought from someone else's change (D794; audit)
  let lastOwn = null;
  async function send(path, body, after) {
    if (typeof body.edited === "number" && lastOwn && lastOwn.shop === body.shop && lastOwn.from === body.edited) body.edited = lastOwn.to;
    st.busy = true; st.ownUntil = Date.now() + 15000;
    const sentWith = body.edited;
    const r = await call(path, body, "change the shop");
    st.busy = false;
    if (r.ok && typeof r.edited === "number" && typeof sentWith === "number") lastOwn = { shop: r.shop || body.shop, from: sentWith, to: r.edited };
    // the answer's stamp at once, so the next change is not refused while the page is read again
    if (r.ok && typeof r.edited === "number" && st.page && st.page.shop.id === (r.shop || body.shop)) st.page.shop.edited = r.edited;
    if (r.ok && typeof r.edited === "number" && st.grid) st.grid.shops.forEach((x) => { if (x.id === (r.shop || body.shop)) x.edited = r.edited; });
    say(r.message || (r.ok ? "Saved." : "It did not change."), r.ok);
    if (after) after(r);
    else if (st.view === "grid") loadGrid(false); else loadShop(false);
    return r;
  }
  // the ON/OFF switch (D805): a top shop only (its sections go with it); turning off asks once
  function switchFor(s, topId) {
    const w = stateSwitch(s.enabled);
    if (!admin() || s.id !== topId) { if (s.id !== topId) w.title = "A section is on or off with its shop."; return w; }
    w.classList.add("click"); w.tabIndex = 0; w.setAttribute("role", "switch"); w.setAttribute("aria-checked", String(!!s.enabled));
    const go = (e) => { e.stopPropagation(); if (s.enabled) askOff(s); else change("/api/shops/enable", { shop: s.id, on: true, edited: s.edited }); };
    w.addEventListener("click", go); w.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(e); } });
    return w;
  }
  function offBar(box, p) {
    const top = p.tree[0];
    if (p.shop.enabled) return;
    const bar = el("div", "shoffbar");
    bar.appendChild(el("span", "grow", "OFF: closed for changes. Players cannot open it anywhere" + (p.shop.remote ? ", and it is off the Shopping Network" : "")
      + (p.tree.length > 1 ? ", with its " + (p.tree.length - 1) + (p.tree.length === 2 ? " section." : " sections.") : ".")));
    if (admin() && p.shop.id === p.top) bar.appendChild(key("TURN ON", "pri", () => change("/api/shops/enable", { shop: p.top, on: true, edited: p.shop.edited })));
    else if (top && p.shop.id !== p.top) bar.appendChild(key("OPEN " + String(top.name || top.id).toUpperCase(), null, () => open(p.top))); // a section is on and off with its shop
    box.appendChild(bar);
  }

  // a window over the page: Esc or the shade closes it
  function modal(title, build, okText, okFn, okCls) {
    if (document.querySelector(".shmodalwrap")) return () => {}; // one window at a time (a double click opened two; audit)
    const wrap = el("div", "shmodalwrap"), shade = el("div", "shshade"), m = el("div", "shmodal");
    m.setAttribute("role", "dialog"); m.setAttribute("aria-label", title);
    const head = el("div", "shmh"); head.appendChild(el("span", "grow", title)); const x = key("✕", "ghost", () => close()); x.setAttribute("aria-label", "Close"); head.appendChild(x);
    const body = el("div", "shmb"), foot = el("div", "shmf"), err = el("div", "err hidden");
    m.appendChild(head); m.appendChild(body); m.appendChild(err); m.appendChild(foot);
    const ok = key(okText, okCls || "pri", async () => {
      ok.disabled = true; const why = await okFn(); ok.disabled = false;
      if (why) { err.textContent = why; err.classList.remove("hidden"); } else close();
    });
    foot.appendChild(key("CANCEL", "ghost", () => close())); foot.appendChild(ok);
    const keyFn = (e) => { if (e.key === "Escape") close(); };
    function close() { wrap.remove(); document.removeEventListener("keydown", keyFn); }
    shade.addEventListener("click", close); document.addEventListener("keydown", keyFn);
    wrap.appendChild(shade); wrap.appendChild(m); document.body.appendChild(wrap);
    build(body, ok);
    const first = body.querySelector("input"); if (first) first.focus();
    return close;
  }
  function field(label, input, hint) {
    const f = el("label", "shfield"); f.appendChild(el("span", "shfl", label)); f.appendChild(input); if (hint) f.appendChild(hint); return f;
  }
  function input(value, cls, ph) { const i = el("input", "wdin shin" + (cls ? " " + cls : "")); i.type = "text"; i.value = value == null ? "" : value; if (ph) i.placeholder = ph; return i; }
  // the id box filled from the name until it is typed in; "free" or "taken" as it changes
  function idPair(nameIn, idIn, prefix) {
    const tag = el("span", "shfree");
    let typed = false;
    const check = () => {
      const v = idIn.value, ok = /^[a-z0-9_]{1,32}$/.test(v), taken = takenIds().has(v);
      tag.textContent = !v ? "" : !ok ? "letters, digits and _ only" : taken ? "taken" : "free";
      tag.className = "shfree " + (!ok || taken ? "rd" : "tl");
      return ok && !taken;
    };
    nameIn.addEventListener("input", () => { if (!typed) { idIn.value = slug((prefix ? prefix + "_" : "") + nameIn.value); } check(); });
    idIn.addEventListener("input", () => { typed = true; idIn.value = idIn.value.toLowerCase(); check(); });
    return { tag, check };
  }
  async function ensureGrid() { if (!st.grid) { const r = await call("/api/shops/grid", {}); if (r.ok) st.grid = r; } }

  // R10: a new shop (D795)
  async function newShop() {
    await ensureGrid();
    let kind = "shop";
    const nameIn = input("", null, "Harbour Fish Stall"), idIn = input("", "mono", "harbour_fish_stall"), pair = idPair(nameIn, idIn);
    modal("NEW SHOP", (body) => {
      const g = el("div", "shg2"); g.appendChild(field("NAME (64 CHARACTERS)", nameIn)); g.appendChild(field("ID (LETTERS, DIGITS AND _, 32)", idIn, pair.tag)); body.appendChild(g);
      const kinds = el("div", "shkinds");
      [["shop", "Shop", "Pages of cards that are always there, with sections inside."], ["rotating", "Rotating", "A few cards of its pool on show, drawn again on a schedule."],
        ["offer", "Special offer", "A discount on every card until a date."]].forEach(([k, l, d]) => {
        const c = el("button", "shkc" + (k === kind ? " on" : "")); c.type = "button"; c.appendChild(el("b", null, l)); c.appendChild(el("span", null, d));
        c.addEventListener("click", () => { kind = k; kinds.querySelectorAll(".shkc").forEach((x) => x.classList.toggle("on", x === c)); });
        kinds.appendChild(c);
      });
      body.appendChild(field("KIND", kinds));
      body.appendChild(el("div", "shwarn", "It is made OFF: fill it, then turn it on. Players see nothing until then. Stalls are pointed at it in game."));
    }, "MAKE SHOP", async () => {
      if (!nameIn.value.trim()) return "A name, please.";
      if (!pair.check()) return "Choose a free id of letters, digits and _.";
      const r = await call("/api/shops/create", { name: nameIn.value.trim(), id: idIn.value, kind }, "make the shop");
      if (!r.ok) return r.message || "It was not made.";
      say(r.message || "Made, off.", true); st.grid = null; open(idIn.value, "settings"); return "";
    });
  }

  // R9: a new section of the shop or section shown (D796): its name, id and the picture on its card
  async function newSection() {
    const p = st.page; if (!p) return;
    await ensureGrid();
    let picked = null;
    const nameIn = input("", null, "Lighting"), idIn = input("", "mono"), pair = idPair(nameIn, idIn, p.shop.id);
    const q = input("", null, "Search items or type an id"), hits = el("div", "shpick"), chosen = el("div", "wdtrow shchosen");
    const drawHits = () => {
      hits.textContent = "";
      const cat = typeof Pictures !== "undefined" ? Pictures.ready() : null, t = q.value.trim().toLowerCase();
      if (/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(t)) { picked = { item: t }; }
      if (!cat || t.length < 2) { drawChosen(); return; }
      cat.items.map((it, i) => [it, i]).filter(([it]) => String(it.name || "").toLowerCase().includes(t) || it.id.includes(t)).slice(0, 18).forEach(([it]) => {
        const b = el("button", "shpk"); b.type = "button"; b.title = it.name || it.id; b.appendChild(pic(it.id, it.v, 28));
        b.addEventListener("click", () => { picked = { item: it.id, v: it.v || "" }; drawChosen(); });
        hits.appendChild(b);
      });
      drawChosen();
    };
    const drawChosen = () => { chosen.textContent = ""; if (picked) { chosen.appendChild(pic(picked.item, picked.v, 28)); chosen.appendChild(el("span", "mu wdsmall", itemName(picked.item, picked.v) + " · " + picked.item)); } };
    q.addEventListener("input", drawHits);
    modal("NEW SECTION IN " + String(p.shop.name || p.shop.id).toUpperCase(), (body) => {
      const g = el("div", "shg2"); g.appendChild(field("NAME", nameIn)); g.appendChild(field("ID", idIn, pair.tag)); body.appendChild(g);
      body.appendChild(field("PICTURE ON ITS CARD", q)); body.appendChild(hits); body.appendChild(chosen);
      body.appendChild(el("div", "note", "The section card goes at the end of this page. The section is off and on with its shop, and has its own settings."));
    }, "MAKE SECTION", async () => {
      if (!nameIn.value.trim()) return "A name, please.";
      if (!pair.check()) return "Choose a free id of letters, digits and _.";
      if (!picked) return "Choose the item its card shows.";
      const r = await change("/api/shops/create", { name: nameIn.value.trim(), id: idIn.value, parent: p.shop.id, edited: stampOf(p.shop.id, p.shop.edited), picture: picked.item, v: picked.v || "", shop: p.shop.id }, () => {});
      if (!r.ok) { say(null); loadShop(true); return r.message || "It was not made."; }
      say(r.message || "Section made.", true); st.grid = null; open(idIn.value); return "";
    });
  }

  // COPY: a top shop only, as BSP-Core copies (D823)
  function copyKey(p) {
    const b = adminMark(key("COPY", null, () => copyShop()));
    if (p.shop.id !== p.top && !b.disabled) { b.disabled = true; b.title = "Copy its shop instead: a section is copied with its shop."; }
    return b;
  }

  // EXPORT (D809): the shop and its sections as BSP-Core's import file, downloaded; importing stays in game
  async function exportShop() {
    const p = st.page; if (!p) return;
    const r = await call("/api/shops/export", { id: p.shop.id }, "export the shop");
    if (!r.ok) { say(r.message || "It could not be exported.", false); draw(); return; }
    const blob = new Blob([JSON.stringify(r.json, null, 2) + "\n"], { type: "application/json" });
    const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = r.file || p.shop.id + ".json";
    document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    const ns = r.shops || 0, nc = r.cards || 0;
    say("Exported " + ns + (ns === 1 ? " shop" : " shops") + " with " + nc + (nc === 1 ? " card" : " cards") + " as " + a.download + ". In game it goes in config/bsp_core/shops/ for /bsp shop import.", true); draw();
  }

  // R12: a copy with every section under new ids (D797)
  async function copyShop() {
    const p = st.page; if (!p) return;
    await ensureGrid();
    const from = p.shop.id, tree = p.tree.slice(p.tree.findIndex((n) => n.id === from));
    const under = []; const d0 = tree.length ? tree[0].depth : 0;
    for (let i = 1; i < tree.length && tree[i].depth > d0; i++) under.push(tree[i]);
    const nameIn = input(String(p.shop.name || from) + " copy"), idIn = input(slug(from + "_copy"), "mono"), pair = idPair(nameIn, idIn);
    const list = el("div", "shcopylist");
    const drawList = () => {
      list.textContent = "";
      list.appendChild(el("div", "shlab", "COPIED WITH IT: " + under.length + (under.length === 1 ? " SECTION" : " SECTIONS") + ", " + (p.shop.cards || []).length + " CARDS ON ITS OWN PAGE"));
      under.forEach((n) => { const r = el("div", "wdtrow"); r.appendChild(el("span", "grow", n.name || n.id)); r.appendChild(el("span", "mono dim", n.id)); list.appendChild(r); });
      if (under.length) list.appendChild(el("div", "mu wdsmall", "Each gets a new id, " + (idIn.value || "<new id>") + "_1, _2…; the copy says which.")); // BSP-Core numbers them (audit: the guess could be wrong)
    };
    idIn.addEventListener("input", drawList); nameIn.addEventListener("input", drawList);
    modal("COPY " + String(p.shop.name || from).toUpperCase(), (body) => {
      const g = el("div", "shg2"); g.appendChild(field("NEW NAME", nameIn)); g.appendChild(field("NEW ID", idIn, pair.tag)); body.appendChild(g);
      body.appendChild(list); drawList(); pair.check();
      body.appendChild(el("div", "note", "Every setting and card is copied; counts and sales start fresh. The copy is made OFF. Stalls stay with " + (p.shop.name || from) + "."));
    }, "COPY", async () => {
      if (!nameIn.value.trim()) return "A name, please.";
      if (!pair.check()) return "Choose a free id of letters, digits and _.";
      const r = await call("/api/shops/copy", { shop: from, newId: idIn.value, name: nameIn.value.trim() }, "copy the shop");
      if (!r.ok) return r.message || "It was not copied.";
      const made = r.ids ? Object.keys(r.ids).filter((k) => k !== from).map((k) => k + " → " + r.ids[k]) : [];
      say((r.message || "Copied, off.") + (made.length ? " Sections: " + made.join(", ") + "." : ""), true); st.grid = null; open(r.shop || idIn.value); return "";
    });
  }

  // turning off asks once (D805), naming what goes with it
  function askOff(s) {
    const secs = (s.sections || (st.page && st.page.shop.id === s.id ? st.page.tree.slice(1) : [])).length;
    modal("TURN OFF?", (body) => {
      body.appendChild(el("div", "note", (s.name || s.id) + (secs ? " and its " + secs + (secs === 1 ? " section" : " sections") : "") + " close for every player: its stalls show closed"
        + (s.remote ? " and it leaves the Shopping Network" : "") + ". You can still change it here and test it in game, then turn it on again."));
    }, "TURN OFF", async () => {
      const r = await change("/api/shops/enable", { shop: s.id, on: false, edited: stampOf(s.id, s.edited) });
      if (!r.ok) say(null); // said in the window, not twice
      return r.ok ? "" : r.message || "It did not change.";
    }, "gold");
  }

  // R11: every setting on one page (D803), each saved as it is changed
  function drawSettings(box) {
    const p = st.page, s = p.shop, can = admin(), isSub = s.kind === "sub";
    if (!st.grid && !st.gridAsked) { st.gridAsked = true; ensureGrid().then(() => { st.gridAsked = false; if (st.view === "settings" && st.grid) draw(); }); } // the shops for "inside"
    const head = el("div", "wdhead");
    const crumb = el("span", "wdcrumb shcrumb"); crumb.appendChild(key("SHOPS", "link", back));
    p.path.forEach((x) => { crumb.appendChild(el("span", "mu", "›")); crumb.appendChild(key(x.name || x.id, "link", () => open(x.id))); });
    crumb.appendChild(el("span", "mu", "›")); crumb.appendChild(el("b", null, "Settings"));
    head.appendChild(crumb); head.appendChild(kindTag(s.kind)); head.appendChild(el("span", "grow"));
    head.appendChild(switchFor(s, p.top)); head.appendChild(lamp(s));
    head.appendChild(copyKey(p));
    head.appendChild(key("CARDS", null, () => { st.view = "shop"; st.msg = null; st.needsDraft = null; draw(); }));
    const edit = adminMark(key("EDIT CARDS", "pri", () => { st.view = "edit"; st.sel = null; st.msg = null; st.needsDraft = null; draw(); }));
    if (!onComputer() && !edit.disabled) { edit.disabled = true; edit.title = "The card editor is for a computer."; }
    head.appendChild(edit);
    box.appendChild(head);
    offBar(box, p);
    flash(box);
    if (!can) box.appendChild(el("div", "note", "Changing shops is for Admins and Owners: every setting is shown, none can be changed."));
    const wrap = el("div", "shset");
    const nav = el("div", "shsnav"), grid = el("div", "shsgrid");
    wrap.appendChild(nav); wrap.appendChild(grid); box.appendChild(wrap);
    const section = (id, title, show) => {
      const b = el("div", "shsbox"); b.id = "shs-" + id; b.appendChild(el("div", "sect", title));
      const n = el("button", "shsn", title.charAt(0) + title.slice(1).toLowerCase()); n.type = "button";
      if (!show) n.classList.add("dim"); n.addEventListener("click", () => { const t = $("shs-" + id); if (t) t.scrollIntoView({ block: "start", behavior: "smooth" }); });
      nav.appendChild(n); if (show) grid.appendChild(b); return b;
    };
    const set = (fieldName, value) => change("/api/shops/set", { shop: s.id, field: fieldName, value, edited: st.page.shop.edited });
    const chips = (current, list, fn) => {
      const c = el("div", "wdchips");
      list.forEach(([v, l]) => { const b = el("button", "wdchip" + (v === current ? " on" : ""), l); b.type = "button"; b.disabled = !can; b.addEventListener("click", () => { if (v !== current) fn(v); }); c.appendChild(b); });
      return c;
    };
    const tick = (label, on, fieldName) => {
      const t = el("label", "shtick"); const c = el("input"); c.type = "checkbox"; c.checked = !!on; c.disabled = !can; c.dataset.keep = "shs:" + fieldName;
      c.addEventListener("change", () => set(fieldName, c.checked)); t.appendChild(c); t.appendChild(el("span", null, label)); return t;
    };
    const text = (value, fieldName, check, cls) => { // saved on Enter or on leaving the box, when it changed
      const i = input(value, cls); i.disabled = !can; i.dataset.keep = "shs:" + fieldName;
      let sent = value;
      const go = () => { const v = check ? check(i.value) : i.value.trim(); if (v === null || String(v) === String(sent)) return; sent = v; set(fieldName, v); }; // Enter then leaving the box sends it once (audit)
      i.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); go(); } }); i.addEventListener("change", go);
      return i;
    };
    // GENERAL
    const g = section("general", "GENERAL", true);
    const g2 = el("div", "shg2");
    g2.appendChild(field("NAME", text(s.name, "name", (v) => v.trim() ? v.trim() : null)));
    const idRo = input(s.id, "mono"); idRo.disabled = true; g2.appendChild(field("ID", idRo));
    g.appendChild(g2);
    if (!isSub) g.appendChild(field("KIND", chips(s.kind, [["shop", "Shop"], ["rotating", "Rotating"], ["offer", "Special offer"]], (v) => set("kind", v))));
    const par = el("select", "wdin shin"); par.disabled = !can; par.dataset.keep = "shs:parent";
    const mine = new Set(subtree(p, s.id).map((n) => n.id));
    if (!s.parent) { const o = el("option", null, "Top level"); o.value = ""; par.appendChild(o); } // a shop inside another stays inside one (BSP-Core; audit)
    (st.grid ? st.grid.shops : []).forEach((t) => [t, ...(t.sections || [])].forEach((x) => { if (mine.has(x.id)) return; const o = el("option", null, (x.name || x.id) + " (" + x.id + ")"); o.value = x.id; par.appendChild(o); }));
    if (s.parent && ![...par.options].some((o) => o.value === s.parent)) { const o = el("option", null, s.parent); o.value = s.parent; par.appendChild(o); }
    par.value = s.parent || "";
    par.addEventListener("change", () => set("parent", par.value));
    g.appendChild(field("INSIDE (PARENT)", par));
    const ticks = el("div", "shg2"); ticks.appendChild(tick("On the Shopping Network", s.remote, "remote")); ticks.appendChild(tick("Buy only (no selling)", s.buyOnly, "buyOnly")); g.appendChild(ticks);
    // LOOK
    const l = section("look", "LOOK", true);
    l.appendChild(field("STYLE", chips(s.style || "cash_machine", [["cash_machine", "Cash Machine"], ["passbook", "Passbook"], ["market_board", "Market Board"]], (v) => set("style", v))));
    const l2 = el("div", "shg2");
    l2.appendChild(field("CARDS A ROW", chips(s.columns >= 4 ? s.columns : 0, [[0, "Auto"], [4, "4"], [5, "5"], [6, "6"], [7, "7"]], (v) => set("columns", v))));
    l2.appendChild(field("ROWS", chips(Math.max(2, Math.min(3, s.rows || 3)), [[2, "2"], [3, "3"]], (v) => set("rows", v))));
    l.appendChild(l2);
    // OPENING HOURS (D804)
    const h = section("hours", "OPENING HOURS", true), hrs = s.hours || { mode: "ALWAYS", open: 0, close: 0 };
    h.appendChild(chips(hrs.mode, [["ALWAYS", "Always"], ["REAL", "Real clock"], ["GAME", "Minecraft day"]], (v) => set("hours", { mode: v, open: v === "GAME" ? 0 : 9 * 60, close: v === "GAME" ? 14000 : 21 * 60 })));
    if (hrs.mode !== "ALWAYS") {
      const toVal = (t) => { const m = /^([01]?\d|2[0-3]):([0-5]\d)$/.exec(t.trim()); if (!m) return null; const hh = +m[1], mm = +m[2];
        return hrs.mode === "GAME" ? ((hh - 6 + 24) % 24) * 1000 + Math.round(mm * 1000 / 60) : hh * 60 + mm; };
      const oIn = input(clock(hrs.open, hrs.mode), "mono"), cIn = input(clock(hrs.close, hrs.mode), "mono"); oIn.disabled = cIn.disabled = !can; oIn.dataset.keep = "shs:open"; cIn.dataset.keep = "shs:close";
      const go = () => { const o = toVal(oIn.value), c = toVal(cIn.value); if (o === null || c === null) { say("A time as 09:00, please.", false); draw(); return; }
        if (o !== hrs.open || c !== hrs.close) set("hours", { mode: hrs.mode, open: o, close: c }); };
      [oIn, cIn].forEach((i) => { i.addEventListener("change", go); i.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); go(); } }); });
      const h2 = el("div", "shg2"); const zone = hrs.mode === "REAL" ? (p.timeZone || "the server's time") : "the Minecraft day";
      h2.appendChild(field("OPENS", oIn, el("span", "mu wdsmall", zone))); h2.appendChild(field("CLOSES", cIn)); h.appendChild(h2);
    }
    const strip = el("div", "shstripday");
    for (let i = 0; i < 24; i++) {
      const at = hrs.mode === "GAME" ? ((i - 6 + 24) % 24) * 1000 : i * 60;
      const open = hrs.mode === "ALWAYS" || hrs.open === hrs.close || (hrs.open < hrs.close ? at >= hrs.open && at < hrs.close : at >= hrs.open || at < hrs.close);
      strip.appendChild(el("i", open ? "o" : ""));
    }
    h.appendChild(strip);
    const marks = el("div", "shhrs"); ["00", "06", "12", "18", "24"].forEach((t) => marks.appendChild(el("span", null, t))); h.appendChild(marks);
    h.appendChild(el("div", "note", "Stalls and Vendor Posts with hours of their own keep them (see Stalls on the shop's page)."));
    // ROTATING
    const r = section("rotating", "ROTATING", s.kind === "rotating");
    if (s.kind === "rotating") {
      const r2 = el("div", "shg2");
      r2.appendChild(field("CARDS ON SHOW", text(s.displaySlots ?? 4, "displaySlots", (v) => /^\d+$/.test(v.trim()) ? +v.trim() : null, "mono")));
      r2.appendChild(field("A NEW DRAW EVERY (HOURS)", text(s.intervalHours ?? 12, "intervalHours", (v) => /^\d+$/.test(v.trim()) ? +v.trim() : null, "mono")));
      r.appendChild(r2);
      r.appendChild(field("OR AT A FIXED TIME (DAY OR WEEKDAY, 24-HOUR)", text(s.rotateAt || "none", "rotateAt", (v) => v.trim().toLowerCase() || "none", "mono"),
        el("span", "mu wdsmall", "none, day 06:00, monday 00:00 … in " + (p.timeZone || "the server's time"))));
      const r3 = el("div", "shg2"); r3.appendChild(tick("No repeats: every card once a cycle", s.noRepeat, "noRepeat")); r3.appendChild(tick("Tell every player at each draw", s.broadcast, "broadcast")); r.appendChild(r3);
    }
    // SPECIAL OFFER
    const o = section("offer", "SPECIAL OFFER", s.kind === "offer");
    if (s.kind === "offer") {
      const pad2 = (n) => String(n).padStart(2, "0"), d = s.offerEnd ? new Date(s.offerEnd) : null;
      const endIn = el("input", "wdin shin mono"); endIn.type = "datetime-local"; endIn.disabled = !can; endIn.dataset.keep = "shs:offerEnd";
      if (d) endIn.value = d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()) + "T" + pad2(d.getHours()) + ":" + pad2(d.getMinutes());
      endIn.addEventListener("change", () => { const t = new Date(endIn.value).getTime(); if (t > 0) set("offerEnd", t); });
      const o2 = el("div", "shg2");
      o2.appendChild(field("DISCOUNT (%)", text(s.offerPercent ?? 0, "offerPercent", (v) => /^\d+$/.test(v.trim()) ? +v.trim() : null, "mono")));
      o2.appendChild(field("ENDS (THIS BROWSER'S TIME)", endIn)); o.appendChild(o2);
      const never = el("label", "shtick"); const nc = el("input"); nc.type = "checkbox"; nc.checked = !s.offerEnd; nc.disabled = !can || !s.offerEnd;
      nc.addEventListener("change", () => { if (nc.checked) set("offerEnd", 0); }); never.appendChild(nc); never.appendChild(el("span", null, "Never ends")); o.appendChild(never);
      o.appendChild(el("div", "note", "When it ends the shop stays, at full prices. Sell prices are not discounted."));
    }
    // NEEDS (the whole shop and its sections)
    const n = section("needs", "NEEDS (THE WHOLE " + (isSub ? "SECTION" : "SHOP") + ")", true), rq = s.requires || {};
    const dr = st.needsDraft && st.needsDraft.shop === s.id ? st.needsDraft : null; // typed and not saved yet: kept across reads
    const perm = input(dr ? dr.permission : rq.permission || "", "mono", "none"), hrsIn = input(dr ? dr.hours : rq.playtimeHours || 0, "mono"), reason = input(dr ? dr.reason : rq.reason || "", null, "Gold rank and up");
    let hide = dr ? dr.hide : !!rq.hide;
    const keepDraft = () => { st.needsDraft = { shop: s.id, permission: perm.value, hours: hrsIn.value, reason: reason.value, hide }; };
    [[perm, "perm"], [hrsIn, "pt"], [reason, "reason"]].forEach(([i, k]) => { i.disabled = !can; i.dataset.keep = "shs:needs:" + k; i.addEventListener("input", keepDraft); });
    const n2 = el("div", "shg2"); n2.appendChild(field("PERMISSION (LUCKPERMS NODE)", perm)); n2.appendChild(field("HOURS PLAYED", hrsIn)); n.appendChild(n2);
    const hideChips = chips(hide, [[false, "Sees it locked"], [true, "Does not see it"]], (v) => { hide = v; keepDraft(); hideChips.querySelectorAll(".wdchip").forEach((b, i) => b.classList.toggle("on", (i === 1) === v)); });
    n.appendChild(field("A PLAYER WHO DOES NOT MEET IT", hideChips));
    n.appendChild(field("REASON SHOWN (64 CHARACTERS)", reason));
    const saveNeeds = adminMark(key("SAVE NEEDS", "pri", () => {
      if (!/^\d*$/.test(hrsIn.value.trim())) { say("Hours played is a whole number.", false); draw(); return; }
      set("requires", { permission: perm.value.trim(), playtimeHours: +(hrsIn.value.trim() || 0), hide, reason: reason.value.trim() }).then((r) => { if (r && r.ok) { st.needsDraft = null; draw(); } });
    }));
    const nr = el("div", "wdtrow"); nr.appendChild(saveNeeds); nr.appendChild(el("span", "mu wdsmall", "Covers " + (isSub ? "this section" : "the shop") + " and every section under it.")); n.appendChild(nr);
    // DELETE (D807)
    const del = section("delete", "DELETE", true);
    del.classList.add("shdel");
    const secs = Math.max(0, subtree(p, s.id).length - 1);
    const stalls = (p.venues || []).filter((v) => v.shop === s.id || mine.has(v.shop)).length;
    del.appendChild(el("div", "note", (secs ? "Its " + secs + (secs === 1 ? " section goes" : " sections go") + " with it" : "No sections") + "; " + (stalls ? stalls + (stalls === 1 ? " stall is" : " stalls are") + " left without a shop." : "no stalls point at it.")
      + " It cannot be undone here; an export first keeps a copy."));
    const armedNow = st.armed.key === "del:" + s.id && Date.now() - st.armed.at < 3000 ? st.armed.step : 0;
    const db = adminMark(key(armedNow ? "SURE? DELETE (" + armedNow + " of 2)" : "DELETE", armedNow ? "sure" : "dg", () => {
      if (!sure("del:" + s.id, 3)) { draw(); setTimeout(() => { if (st.view === "settings") draw(); }, 3100); return; }
      const parent = s.parent;
      change("/api/shops/delete", { shop: s.id, edited: s.edited }, (res) => { if (res.ok) { st.grid = null; if (parent) open(parent); else { back(); say(res.message || "Deleted.", true); draw(); } } else loadShop(false); });
    }));
    del.appendChild(db);
  }

  // ---------------------------------------------------------------- the card editor (SU-163; R6 to R8, D798 to D802)
  const onComputer = () => matchMedia("(min-width: 900px)").matches; // D814: the editor is for a computer
  const ed = { q: "", tab: -1, typed: "", removing: null };
  function cardChange(path, body, keepSel) {
    // a card's place is read off the page: one card change at a time, so a second click never acts on the card that slid into its place (audit)
    if (st.cardBusy) { say("The last change to a card is still saving: try again in a moment.", false); draw(); return Promise.resolve(null); }
    st.cardBusy = true;
    return change(path, Object.assign({ shop: st.page.shop.id, edited: st.page.shop.edited }, body), (r) => {
      if (r.ok && typeof r.index === "number") st.sel = r.index;
      else if (r.ok && keepSel === false) st.sel = null;
      if (r.ok && r.countsRestart) say("Saved. This card's counts start again, as in game.", true);
      ed.removing = null;
      loadShop(false).then(() => { st.cardBusy = false; }, () => { st.cardBusy = false; });
    });
  }
  const addAt = (it, at) => cardChange("/api/shops/card/add", { item: it.item, v: it.v || "", nbt: it.nbt || "", count: 1, at });
  const moveTo = (from, to) => { if (from !== to) cardChange("/api/shops/card/move", { from, to }); };
  function drawEditor(box) {
    const p = st.page, s = p.shop;
    const head = el("div", "wdhead");
    const crumb = el("span", "wdcrumb shcrumb"); crumb.appendChild(key("SHOPS", "link", back));
    p.path.forEach((x) => { crumb.appendChild(el("span", "mu", "›")); crumb.appendChild(key(x.name || x.id, "link", () => open(x.id, "edit"))); });
    crumb.appendChild(el("span", "mu", "›")); crumb.appendChild(el("b", null, "Editing"));
    head.appendChild(crumb); head.appendChild(kindTag(s.kind)); head.appendChild(el("span", "grow"));
    head.appendChild(switchFor(s, p.top)); head.appendChild(lamp(s));
    head.appendChild(key("+ SECTION", null, () => newSection()));
    head.appendChild(key("SETTINGS", null, () => { st.view = "settings"; st.msg = null; draw(); }));
    head.appendChild(key("DONE", "pri", () => { st.view = "shop"; st.sel = null; st.msg = null; draw(); }));
    box.appendChild(head);
    offBar(box, p);
    flash(box);
    const wrap = el("div", "shed");
    wrap.appendChild(browserBox());
    wrap.appendChild(pageBox(p));
    if (st.sel !== null && st.sel !== undefined && (s.cards || [])[st.sel]) wrap.appendChild(drawer(p, s.cards[st.sel]));
    box.appendChild(wrap);
  }
  // the item browser (D798): the item pictures, searched by name or id, a chip per creative tab; a click adds at the end
  function browserBox() {
    const b = el("div", "shbrowser");
    b.appendChild(el("div", "shlab", "ITEMS"));
    const cat = typeof Pictures !== "undefined" ? Pictures.ready() : null;
    if (!cat) {
      b.appendChild(el("div", "note", "No item pictures yet (an admin's /su icons makes them). Type an item id to add it."));
      const i = input(ed.typed, "mono", "minecraft:iron_ingot"); i.dataset.keep = "shed:typed"; i.addEventListener("input", () => { ed.typed = i.value.trim().toLowerCase(); });
      b.appendChild(i);
      b.appendChild(key("ADD AT THE END", "pri", () => { if (/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(ed.typed)) addAt({ item: ed.typed }, (st.page.shop.cards || []).length); else { say("An item id is like minecraft:iron_ingot.", false); draw(); } }));
      return b;
    }
    const q = input(ed.q, null, "Search items or ids"); q.dataset.keep = "shed:q";
    q.addEventListener("input", () => { ed.q = q.value; drawItems(); });
    b.appendChild(q);
    const tabs = el("div", "wdchips shbtabs");
    [-1, ...cat.tabs.map((_, i) => i)].forEach((i) => {
      const c = el("button", "wdchip" + (ed.tab === i ? " on" : ""), i < 0 ? "All" : cat.tabs[i].name); c.type = "button";
      c.addEventListener("click", () => { ed.tab = i; tabs.querySelectorAll(".wdchip").forEach((x) => x.classList.toggle("on", x === c)); drawItems(); });
      tabs.appendChild(c);
    });
    b.appendChild(tabs);
    const count = el("div", "mu wdsmall"); count.id = "shedcount"; b.appendChild(count);
    const grid = el("div", "shitems"); grid.id = "shitems"; b.appendChild(grid);
    b.appendChild(el("div", "note", "Drag an item onto the page, or click it to add it at the end. Items with data keep it."));
    setTimeout(drawItems, 0);
    return b;
  }
  function drawItems() {
    const grid = $("shitems"), count = $("shedcount"), cat = Pictures.ready(); if (!grid || !cat) return;
    grid.textContent = "";
    const t = ed.q.trim().toLowerCase();
    let list = ed.tab >= 0 && cat.tabs[ed.tab] ? cat.tabs[ed.tab].items.map((i) => [cat.items[i], i]) : cat.items.map((it, i) => [it, i]);
    if (t) list = list.filter(([it]) => it && (String(it.name || "").toLowerCase().includes(t) || it.id.includes(t)));
    count.textContent = list.length + (list.length === 1 ? " item" : " items") + (list.length > 120 ? " · the first 120 shown" : "");
    list.slice(0, 120).forEach(([it]) => {
      if (!it) return;
      const c = el("button", "shitem"); c.type = "button"; c.title = (it.name || it.id) + "\n" + it.id; c.draggable = true;
      c.appendChild(pic(it.id, it.v, 32));
      const data = { item: it.id, v: it.v || "", nbt: it.nbt || "" };
      c.addEventListener("dragstart", (e) => { e.dataTransfer.setData("text/plain", "item:" + JSON.stringify(data)); e.dataTransfer.effectAllowed = "copy"; });
      c.addEventListener("click", () => addAt(data, (st.page.shop.cards || []).length));
      grid.appendChild(c);
    });
  }
  // the page: every card in order, a line where each page of the game begins; drop targets on every card and at the end
  function pageBox(p) {
    const s = p.shop, byId = new Map(p.tree.map((n) => [n.id, n])), cards = s.cards || [];
    const box = el("div", "shedpage");
    const cols = columns(s), per = cols * Math.max(2, Math.min(3, s.rows || 3));
    const top = el("div", "wdtrow"); top.appendChild(el("b", null, (s.name || s.id) + " · " + cards.length + (cards.length === 1 ? " card" : " cards"))); top.appendChild(el("span", "grow"));
    top.appendChild(el("span", "mu wdsmall", "Each change saves as it is made")); box.appendChild(top);
    const grid = el("div", "shcards shedcards"); // as many a row as fit beside the browser and the drawer; PAGE lines mark the game's pages
    const rot = s.kind === "rotating", shown = new Set(s.onShow || []);
    const target = (node, at) => {
      node.addEventListener("dragover", (e) => { e.preventDefault(); node.classList.add("shdrop"); });
      node.addEventListener("dragleave", () => node.classList.remove("shdrop"));
      node.addEventListener("drop", (e) => {
        e.preventDefault(); node.classList.remove("shdrop");
        const d = e.dataTransfer.getData("text/plain") || "";
        if (d.startsWith("item:")) { try { addAt(JSON.parse(d.slice(5)), at); } catch (x) { /* not ours */ } }
        else if (d.startsWith("card:")) moveTo(+d.slice(5), Math.min(at, cards.length - 1));
      });
    };
    cards.forEach((c, i) => {
      if (i > 0 && i % per === 0 && !rot) grid.appendChild(el("div", "shpgline", "PAGE " + (i / per + 1)));
      const d = card(c, s, byId, rot, shown, true);
      d.classList.add("shedc"); if (i === st.sel) d.classList.add("sel");
      d.draggable = true; d.tabIndex = 0; d.dataset.keep = "shed:c:" + i;
      d.addEventListener("dragstart", (e) => { e.dataTransfer.setData("text/plain", "card:" + i); e.dataTransfer.effectAllowed = "move"; });
      d.addEventListener("click", () => { st.sel = i; ed.removing = null; draw(); });
      d.addEventListener("keydown", (e) => { if (e.key === "Enter") { st.sel = i; draw(); } });
      target(d, i);
      grid.appendChild(d);
    });
    const end = el("div", "shcard shend", "+ drop here for the end"); target(end, cards.length); grid.appendChild(end);
    box.appendChild(grid);
    box.appendChild(el("div", "note", "Drop an item on a card to put it there; the cards after it move along. Drag a card onto another to move it there. "
      + "Cards that move start their counts again, as in game."));
    // the bin (D799): a card dropped here asks SURE? REMOVE for three seconds
    const bin = el("div", "shbin");
    const arm = ed.removing && Date.now() - ed.removing.at < 3000 && cards[ed.removing.index] ? ed.removing : null;
    if (arm) {
      bin.classList.add("hot");
      bin.appendChild(el("span", null, "Remove " + (cards[arm.index].section ? "the card of section " + cards[arm.index].section : itemName(cards[arm.index].item, cards[arm.index].v)) + "?"));
      bin.appendChild(key("SURE? REMOVE", "sure", () => { ed.removing = null; cardChange("/api/shops/card/remove", { index: arm.index }, false); }));
      setTimeout(() => { if (ed.removing === arm && st.view === "edit") { ed.removing = null; draw(); } }, 3100);
    } else bin.appendChild(el("span", null, "🗑 Drag a card here to remove it"));
    bin.addEventListener("dragover", (e) => { e.preventDefault(); bin.classList.add("hot"); });
    bin.addEventListener("dragleave", () => { if (!arm) bin.classList.remove("hot"); });
    bin.addEventListener("drop", (e) => { e.preventDefault(); const d = e.dataTransfer.getData("text/plain") || ""; if (d.startsWith("card:")) { ed.removing = { index: +d.slice(5), at: Date.now() }; draw(); } });
    box.appendChild(bin);
    return box;
  }
  // the card drawer (D800): CARD, LIMITS, NEEDS; each field saved as it is changed
  function drawer(p, c) {
    const s = p.shop, i = st.sel, sec = !!c.section, rot = s.kind === "rotating";
    const d = el("div", "shdrawer");
    const h = el("div", "wdtrow shdh"); h.appendChild(pic(c.item, c.v, 40));
    const t = el("div", "grow"); t.appendChild(el("b", null, sec ? "Section card: " + (p.tree.find((n) => n.id === c.section) || { name: c.section }).name : itemName(c.item, c.v)));
    t.appendChild(el("div", "mono dim wdsmall", c.item + " · card " + (i + 1))); h.appendChild(t);
    h.appendChild(key("✕", "ghost", () => { st.sel = null; draw(); })); d.appendChild(h);
    const tabs = el("div", "shtabs");
    ["CARD", "LIMITS", "NEEDS"].forEach((x) => { const b = el("button", "shtab" + ((st.dtab || "CARD") === x ? " on" : ""), x); b.type = "button"; b.addEventListener("click", () => { st.dtab = x; draw(); }); tabs.appendChild(b); });
    d.appendChild(tabs);
    const body = el("div", "shdbody"); d.appendChild(body);
    const set = (field, value) => cardChange("/api/shops/card/set", { index: i, field, value });
    const numIn = (value, field, opts) => { // a whole number; empty is "off" or "none" where that is allowed
      const n = input(value === -1 || value === undefined ? "" : num(value), "mono", opts && opts.empty); n.dataset.keep = "shc:" + field;
      let sent = value;
      const go = () => { const raw = n.value.replace(/[ ,]/g, ""); if (raw === "" && opts && opts.empty) { if (sent !== -1) { sent = -1; set(field, -1); } return; }
        if (!/^\d+$/.test(raw)) { say("A whole number, please.", false); draw(); return; } if (+raw !== sent) { sent = +raw; set(field, +raw); } };
      n.addEventListener("change", go); n.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); go(); } });
      return n;
    };
    const chipsOf = (current, list, fn) => { const r = el("div", "wdchips"); list.forEach(([v, l]) => { const b = el("button", "wdchip" + (v === current ? " on" : ""), l); b.type = "button"; b.addEventListener("click", () => { if (v !== current) fn(v); }); r.appendChild(b); }); return r; };
    const tick = (label, on, field) => { const l = el("label", "shtick"); const x = el("input"); x.type = "checkbox"; x.checked = !!on; x.dataset.keep = "shc:" + field; x.addEventListener("change", () => set(field, x.checked)); l.appendChild(x); l.appendChild(el("span", null, label)); return l; };
    const tab = st.dtab || "CARD";
    if (sec && tab !== "NEEDS") {
      body.appendChild(el("div", "note", "This card opens its section; its picture is chosen when the section is made. Hide it, or set what it needs."));
      body.appendChild(tick("Hidden from players", c.hidden, "hidden"));
      body.appendChild(key("OPEN THE SECTION", null, () => open(c.section, "edit")));
    } else if (tab === "CARD") {
      const g = el("div", "shg3");
      g.appendChild(field("LOT SIZE", numIn(c.count, "count"), el("span", "mu wdsmall", "1 to a stack")));
      g.appendChild(field("BUY FOR", numIn(c.buy, "buy", { empty: "off" })));
      g.appendChild(field("SELL FOR", numIn(c.sell, "sell", { empty: "off" })));
      body.appendChild(g);
      body.appendChild(field("PAID IN", chipsOf(c.currency, [[-1, "Pieces"], [0, "Copper"], [1, "Gold"], [2, "Diamond"], [3, "Netherite"], [4, "Illyrium"]], (v) => set("currency", v))));
      const pv = el("div", "wdtrow");
      if (c.buy >= 0) pv.appendChild(el("span", "shprev", price("buy", c.buy, c.currency)));
      if (c.sell >= 0) pv.appendChild(el("span", "shprev s", price("sell", c.sell, c.currency)));
      pv.appendChild(el("span", "mu wdsmall", c.buy < 0 && c.sell < 0 ? "Set a price: an empty box turns that side off." : "as players see it"));
      body.appendChild(pv);
      const t2 = el("div", "shg2"); t2.appendChild(tick("Instant (no confirm)", c.instant, "instant")); t2.appendChild(tick("Hidden from players", c.hidden, "hidden")); body.appendChild(t2);
      body.appendChild(field("SELLING TAKES", chipsOf(c.match || "exact", [["exact", "This exact item"], ["item", "The item, any data"]], (v) => set("match", v))));
      if (rot) body.appendChild(field("WEIGHT IN THE DRAW (0 NEVER, 1 USUAL, UP TO 100)", numIn(c.weight ?? 1, "weight")));
      const lines = [0, 1, 2].map((k) => { const l = input((c.note || [])[k] || "", null, "line " + (k + 1)); l.maxLength = 60; l.dataset.keep = "shc:note" + k; return l; });
      const saveNote = () => { const v = lines.map((l) => l.value.trim()).filter((x) => x); if (JSON.stringify(v) !== JSON.stringify(c.note || [])) set("note", v); };
      lines.forEach((l) => { l.addEventListener("change", saveNote); l.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); saveNote(); } }); });
      const nb = el("div", "shnote"); lines.forEach((l) => nb.appendChild(l));
      body.appendChild(field("NOTE FOR PLAYERS (3 LINES OF 60)", nb));
      body.appendChild(el("div", "note", "Changing the lot size or what selling takes starts this card's counts again."));
    } else if (tab === "LIMITS") {
      const side = (title, off, all, each, left, rule, eachRule, fAll, fEach, fRule, fEachRule) => {
        body.appendChild(el("div", "shsect", title + (off ? " · this side is off on this card" : "")));
        const g = el("div", "shg2");
        g.appendChild(field("FOR EVERYONE (LOTS)", numIn(all, fAll, { empty: "none" })));
        g.appendChild(field("FOR EACH PLAYER (LOTS)", numIn(each, fEach, { empty: "none" })));
        body.appendChild(g);
        body.appendChild(field("EVERYONE'S FILLS AGAIN", ruleEditor(rule, false, rot, (v) => set(fRule, v), fRule)));
        body.appendChild(field("EACH PLAYER'S FILLS AGAIN", ruleEditor(eachRule, true, rot, (v) => set(fEachRule, v), fEachRule)));
        if (all >= 0) body.appendChild(el("div", "mu wdsmall", "Left now for everyone: " + (typeof left === "number" && left >= 0 ? num(left) + " of " + num(all) : "not read")));
      };
      side("STOCK (BUYING)", c.buy < 0, c.stockGlobal, c.stockPlayer, c.stockLeft, c.stockRefill, c.stockPlayerRefill, "stockGlobal", "stockPlayer", "stockRefill", "stockPlayerRefill");
      side("SELL ROOM (SELLING)", c.sell < 0, c.sellGlobal, c.sellPlayer, c.sellLeft, c.sellRefill, c.sellPlayerRefill, "sellGlobal", "sellPlayer", "sellRefill", "sellPlayerRefill");
      const r = el("div", "wdtrow"); r.appendChild(key("REFILL NOW", null, () => cardChange("/api/shops/card/refill", { index: i })));
      r.appendChild(el("span", "mu wdsmall", "Fills every count of this card again, for everyone.")); body.appendChild(r);
      body.appendChild(el("div", "shwarn", "Changing a limit or a rule starts this card's counts again, as in game."));
    }
    if (tab === "NEEDS") {
      const rq = c.requires || {}, dk = s.id + "|" + i, dr = st.cardNeedsDraft && st.cardNeedsDraft.key === dk ? st.cardNeedsDraft : null; // typed, not saved: kept across reads (audit)
      const perm = input(dr ? dr.permission : rq.permission || "", "mono", "none"), hrs = input(dr ? dr.hours : rq.playtimeHours || 0, "mono"), reason = input(dr ? dr.reason : rq.reason || "", null, "Veterans only");
      let hide = dr ? dr.hide : !!rq.hide;
      const keepDraft = () => { st.cardNeedsDraft = { key: dk, permission: perm.value, hours: hrs.value, reason: reason.value, hide }; };
      [[perm, "p"], [hrs, "h"], [reason, "r"]].forEach(([x, k]) => { x.dataset.keep = "shc:needs:" + k; x.addEventListener("input", keepDraft); });
      body.appendChild(el("div", "note", "What a player needs to trade this card; both, when both are set. A whole shop's needs are in its settings and cover its sections."));
      const g = el("div", "shg2"); g.appendChild(field("PERMISSION (LUCKPERMS NODE)", perm)); g.appendChild(field("HOURS PLAYED", hrs)); body.appendChild(g);
      const hc = chipsOf(hide, [[false, "Sees it locked"], [true, "Does not see it"]], (v) => { hide = v; keepDraft(); hc.querySelectorAll(".wdchip").forEach((b, k) => b.classList.toggle("on", (k === 1) === v)); });
      body.appendChild(field("A PLAYER WHO DOES NOT MEET IT", hc));
      body.appendChild(field("REASON SHOWN (64 CHARACTERS)", reason));
      body.appendChild(key("SAVE NEEDS", "pri", () => {
        if (!/^\d*$/.test(hrs.value.trim())) { say("Hours played is a whole number.", false); draw(); return; }
        set("requires", { permission: perm.value.trim(), playtimeHours: +(hrs.value.trim() || 0), hide, reason: reason.value.trim() }).then((r) => { if (r && r.ok) st.cardNeedsDraft = null; });
      }));
      const need = needsText(rq); if (need) body.appendChild(el("div", "shneed", "Now: needs " + need + "."));
    }
    // moving without dragging, and removing (D799)
    const foot = el("div", "wdtrow shdfoot");
    const n = (s.cards || []).length;
    const l = key("◀ MOVE", null, () => moveTo(i, i - 1)); l.disabled = i <= 0;
    const r = key("MOVE ▶", null, () => moveTo(i, i + 1)); r.disabled = i >= n - 1;
    foot.appendChild(l); foot.appendChild(r); foot.appendChild(el("span", "grow"));
    const armed = st.armed.key === "rm:" + i && Date.now() - st.armed.at < 3000;
    foot.appendChild(key(armed ? "SURE? REMOVE" : "REMOVE", armed ? "sure" : "dg", () => {
      if (!sure("rm:" + i, 2)) { draw(); setTimeout(() => { if (st.view === "edit") draw(); }, 3100); return; }
      cardChange("/api/shops/card/remove", { index: i }, false);
    }));
    d.appendChild(foot);
    return d;
  }
  // a refill rule as chips with the number, unit, day and time beside (D801)
  function ruleEditor(rule, perPlayer, rot, save, keyName) {
    rule = String(rule || (perPlayer ? "same" : "never"));
    const days = ["day", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"];
    const m = /^(\d+)([mhd])$/.exec(rule), at = /^(\S+) (\d\d:\d\d)$/.exec(rule);
    const mode = rule === "never" || rule === "same" || rule === "rotation" ? rule : m ? "after" : at && at[1] === "day" ? "daily" : at ? "weekly" : "never";
    const box = el("div", "shrule");
    const opts = [...(perPlayer ? [["same", "Same as everyone"]] : []), ["never", "Never"], ["after", "After…"], ["daily", "Every day at…"], ["weekly", "Each week on…"], ...(rot ? [["rotation", "Each rotation"]] : [])];
    const chips = el("div", "wdchips");
    opts.forEach(([v, l]) => { const b = el("button", "wdchip" + (v === mode ? " on" : ""), l); b.type = "button";
      b.addEventListener("click", () => { if (v === mode) return; save(v === "after" ? "12h" : v === "daily" ? "day 06:00" : v === "weekly" ? "monday 06:00" : v); }); chips.appendChild(b); });
    box.appendChild(chips);
    const row = el("div", "wdtrow");
    if (mode === "after") {
      const n = input(m[1], "mono shsmallin"); n.dataset.keep = "shc:r:" + keyName;
      const go = (unit) => { const v = n.value.trim() + unit; if (v === rule) return; if (/^[1-9]\d{0,5}$/.test(n.value.trim())) save(v); else { say("A whole number, please.", false); draw(); } }; // an unchanged rule is not sent: it would restart the counts (audit)
      n.addEventListener("change", () => go(m[2]));
      row.appendChild(n);
      [["m", "minutes"], ["h", "hours"], ["d", "days"]].forEach(([u, w]) => { const b = el("button", "wdchip" + (u === m[2] ? " on" : ""), w); b.type = "button"; b.addEventListener("click", () => go(u)); row.appendChild(b); });
      row.appendChild(el("span", "mu wdsmall", "from running out"));
    } else if (mode === "daily" || mode === "weekly") {
      let day = at[1];
      if (mode === "weekly") {
        const sel = el("select", "wdin shin shsmallin"); days.slice(1).forEach((d) => { const o = el("option", null, d.charAt(0).toUpperCase() + d.slice(1)); o.value = d; if (d === day) o.selected = true; sel.appendChild(o); });
        sel.addEventListener("change", () => save(sel.value + " " + tm.value.trim())); row.appendChild(sel);
      }
      const tm = input(at[2], "mono shsmallin"); tm.dataset.keep = "shc:t:" + keyName;
      tm.addEventListener("change", () => { if (/^([01]\d|2[0-3]):[0-5]\d$/.test(tm.value.trim())) save(day + " " + tm.value.trim()); else { say("A time as 06:00, please.", false); draw(); } });
      row.appendChild(tm); row.appendChild(el("span", "mu wdsmall", (st.page && st.page.timeZone) || "the server's time"));
    }
    if (row.childNodes.length) box.appendChild(row);
    return box;
  }

  // ---------------------------------------------------------------- the frame
  function mount(main) {
    host = main;
    if (typeof Pictures !== "undefined") Pictures.load().then(() => { if (host) draw(); });
    draw();
    if (st.view === "grid") loadGrid(false); else loadShop(false);
  }
  function unmount(main) { if (host && host === main) host = null; }
  function home() { st.view = "grid"; st.id = null; st.page = null; }
  // every live tick: a server's shopsAt moved (a shop or a stall changed, D811), or a minute since the last read: read again
  const seenAt = new Map();
  function onLive(live) {
    let moved = false;
    (live && live.servers || []).forEach((s) => {
      if (typeof s.shopsAt !== "number" && typeof s.stallsAt !== "number") return;
      const k = s.server.toLowerCase(), now = (s.shopsAt || 0) + ":" + (s.stallsAt || 0), was = seenAt.get(k);
      seenAt.set(k, now);
      if (was !== undefined && was !== now) moved = true; // the desk reads again only what changed
    });
    if (!host) return;
    if (moved && st.ownUntil && Date.now() < st.ownUntil) moved = false; // the page's own change, already read again (audit)
    const a = document.activeElement;
    if ((st.view === "settings" || st.view === "edit") && a && host.contains(a) && /^(INPUT|SELECT)$/.test(a.tagName) && !moved) return; // not while someone types (a change in game still reads)
    if (moved || Date.now() - st.readAt > REFRESH_MS) {
      st.readAt = Date.now(); // one read per change or minute, whatever the ticks
      if (st.view === "grid") loadGrid(false); else loadShop(false);
    }
  }
  return { mount, unmount, home, onLive };
})();
