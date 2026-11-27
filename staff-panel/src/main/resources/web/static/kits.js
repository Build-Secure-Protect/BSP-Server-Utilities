"use strict";
// The KITS tab (SU-172 to SU-174; D838 to D867, mock-up set BM, K1 to K7): every kit on the network as a tile in the kit
// browser's order (D841), a kit's page with its contents, the card players see, its rules and its record (K2), NEW KIT and
// COPY (K5, made off), the switch, GIVE TO… and DELETE with two confirms (K6, D864, D865), and on a computer the editor
// (K3, K4): the item browser, the contents as a list of stacks, a drawer with KIT and WHO AND WHERE. Every rank reads
// (D839); Admins and Owners change, each change with the stamp the kit was read at. Servers with kits switched off are
// named above the grid (D863). Text from the network is set with textContent only.

const KitsTab = (() => {
  const FILTERS = ["All", "On", "Off", "In the browser", "Hidden", "First join", "Free"];
  const REFRESH_MS = 60000; // a minute, as SHOPS, besides every kitsAt that moves (D845)
  const ITEMS_WAIT_MS = 2500; // item changes made close together go as one save (ten changes a minute per staff member)
  const RETRY_MS = 10000; // the panel's own "ten a minute" refusal: the contents wait and go again
  const st = { view: "grid", grid: null, gridErr: "", q: "", filter: "All", id: null, page: null, pageErr: "", readAt: 0, tok: 0, msg: null,
    armed: { key: "", at: 0 }, tab: "KIT", sel: null, ownUntil: 0 };
  const ed = { q: "", tab: -1, typed: "", draft: null, sent: null, timer: null, changes: 0, saving: false };
  const ownRefusal = (m) => !!m && (m.startsWith("That is ") && m.includes(" in a minute") || m.startsWith("The panel is waiting"));
  let host = null;

  // ---------------------------------------------------------------- words
  const num = (n) => Number(n || 0).toLocaleString("en-GB");
  function ago(ms) {
    const m = Math.max(0, Math.round(ms / 60000));
    return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? Math.floor(m / 60) + " h ago" : Math.floor(m / 1440) + " d ago";
  }
  function span(sec) { // a wait in words: "7 d", "1 d 12 h", "3 h", "30 min"
    const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60);
    return [d ? d + " d" : "", h ? h + " h" : "", !d && m ? m + " min" : ""].filter(Boolean).join(" ") || sec + " s";
  }
  const when = (ms) => new Date(ms).toLocaleString("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
  const cap = (s) => String(s || "").charAt(0).toUpperCase() + String(s || "").slice(1);
  const timing = (k) => k.once ? "Once" : k.cooldownSeconds > 0 ? "Every " + span(k.cooldownSeconds) : "Any time";
  const priceText = (k) => k.price > 0 ? num(k.price) + " pieces" : "Free";
  function ranksText(k, c) {
    const r = k.ranks || []; if (!r.length) return "";
    const ladder = c && c.ranksFromLadder ? (c.ranks || []).map((x) => String(x).toLowerCase()) : null;
    if (ladder) { const i = ladder.indexOf(r[0]); if (i >= 0 && r.length === ladder.length - i && r.every((x, j) => x === ladder[i + j])) return cap(r[0]) + (r.length > 1 ? " and up" : ""); }
    return r.map(cap).join(", ");
  }
  function condText(key, c, short) {
    const f = c && (c.conditions || []).find((x) => x.key === key);
    if (short && key === "no_totem") return "No totem";
    return f && f.label ? f.label : key.replace(/_/g, " ");
  }
  function firstJoinText(k) {
    if (k.grant === "NETWORK") return "First join · network";
    if (k.grant === "SERVERS") return "First join · " + ((k.grantServers || []).length ? k.grantServers.join(", ") : "every server");
    return "";
  }
  function pic(item, size) {
    const ix = item && typeof Pictures !== "undefined" ? Pictures.indexOf(item, "") : -1;
    const p = ix >= 0 ? Pictures.icon(ix, size) : null;
    if (p) return p;
    const l = el("span", "shletters", String(item || "?").replace(/^.*:/, "").split("_").map((w) => w.charAt(0)).join("").slice(0, 2).toUpperCase());
    l.style.width = l.style.height = size + "px"; return l;
  }
  function itemName(item) {
    const ix = typeof Pictures !== "undefined" ? Pictures.indexOf(item, "") : -1, cat = typeof Pictures !== "undefined" ? Pictures.ready() : null;
    if (cat && ix >= 0 && cat.items[ix] && cat.items[ix].name) return cat.items[ix].name;
    const w = String(item || "").replace(/^.*:/, "").replace(/_/g, " ");
    return w.charAt(0).toUpperCase() + w.slice(1);
  }
  const iconOf = (k) => k.icon && k.icon.item ? k.icon.item : (k.items || [])[0] ? k.items[0].item : "";
  function key(text, cls, fn) { const b = el("button", "wdkey" + (cls ? " " + cls : ""), text); b.type = "button"; if (fn) b.addEventListener("click", fn); return b; }
  const admin = () => typeof me !== "undefined" && me && (me.rank === "ADMIN" || me.rank === "OWNER");
  function adminMark(b) { if (!admin()) { b.disabled = true; b.appendChild(el("span", "rk ADMIN", "Admin")); b.title = "Changing kits is for Admins and Owners."; } return b; }
  const onComputer = () => matchMedia("(min-width: 900px)").matches; // the editor is for a computer (as SHOPS, D814)
  function say(text, ok) { st.msg = text ? { text, ok: !!ok, at: Date.now() } : null; }
  function flash(box) { if (st.msg && Date.now() - st.msg.at < 12000) box.appendChild(el("div", st.msg.ok ? "shok" : "err", st.msg.text)); }
  // the answering server, its kits switched off, a kit holding items it lacks (D867)
  const missingWords = (srv) => "Holds items from a mod " + (srv || "this server") + " does not have: change it on a server that has the mod.";
  function lockedFor(k) { return k && k.missing > 0; }

  // ---------------------------------------------------------------- reading
  async function loadGrid(refresh) {
    const tok = ++st.tok;
    const r = await call("/api/kits/grid", { refresh: !!refresh });
    if (tok !== st.tok) return;
    if (r.ok) { st.grid = r; st.gridErr = ""; st.readAt = Date.now(); } else st.gridErr = r.message || "The kits could not be read.";
    draw();
  }
  async function loadKit(refresh) {
    const id = st.id, tok = ++st.tok;
    const r = await call("/api/kits/kit", { id, refresh: !!refresh });
    if (tok !== st.tok || st.id !== id) return;
    if (r.ok) { st.page = r; st.pageErr = ""; st.readAt = Date.now(); if (!ed.timer && !ed.saving) ed.draft = null; } else st.pageErr = r.message || "The kit could not be read.";
    draw();
  }
  function open(id, view) { flushItems(); st.view = view || "kit"; st.sel = null; st.armed = { key: "", at: 0 }; st.id = id; st.page = null; st.pageErr = ""; ed.draft = null; draw(); loadKit(false); }
  function back() { flushItems(); st.view = "grid"; st.id = null; st.page = null; st.msg = null; draw(); loadGrid(false); }

  // ---------------------------------------------------------------- drawing
  function draw() {
    if (!host) return;
    const h = hold();
    const a = document.activeElement, typing = a && /^(INPUT|TEXTAREA)$/.test(a.tagName) && a.dataset.keep && host.contains(a) ? [a.dataset.keep, a.value] : null;
    host.textContent = "";
    const box = el("div", "wdpage shpage ktpage"); host.appendChild(box);
    if (st.view === "grid") drawGrid(box); else if (st.view === "edit" && st.page && onComputer()) drawEditor(box); else drawKit(box);
    give(h);
    if (typing) { const n = [...host.querySelectorAll("input,textarea")].find((x) => x.dataset.keep === typing[0]); if (n) { if (n.value !== typing[1]) n.value = typing[1]; n.focus(); } }
  }
  function offStrip(box, off) { // D863
    if (!off || !off.length) return;
    box.appendChild(el("div", "shwarn", "Kits are switched off on " + off.join(", ") + ": players there cannot take or receive kits; changes still apply everywhere."));
  }

  // K1: the grid
  function matches(k) {
    const f = st.filter;
    if (f === "On" && !k.enabled) return false;
    if (f === "Off" && k.enabled) return false;
    if (f === "In the browser" && !k.inBrowser) return false;
    if (f === "Hidden" && k.inBrowser) return false;
    if (f === "First join" && k.grant === "NONE") return false;
    if (f === "Free" && k.price > 0) return false;
    const q = st.q.trim().toLowerCase();
    if (!q) return true;
    const hit = (x) => String(x || "").toLowerCase().includes(q);
    return hit(k.name) || hit(k.id) || (k.items || []).some((it) => hit(it.item) || hit(itemName(it.item)));
  }
  function drawGrid(box) {
    const head = el("div", "wdhead");
    head.appendChild(el("span", "wdtitle", "KITS"));
    const all = st.grid ? st.grid.kits : [];
    head.appendChild(el("span", "mu wdsmall", all.length ? all.length + " kits · " + all.filter((k) => k.enabled).length + " on" : ""));
    head.appendChild(el("span", "grow"));
    const q = el("input", "search wdsearch"); q.type = "text"; q.placeholder = "Search kits or items"; q.value = st.q; q.dataset.keep = "kt:q";
    q.addEventListener("input", () => { st.q = q.value; drawTiles(); });
    head.appendChild(q);
    head.appendChild(adminMark(key("+ NEW KIT", "pri", () => newKit())));
    box.appendChild(head);
    flash(box);
    const chips = el("div", "wdchips");
    FILTERS.forEach((f) => {
      const was = st.filter; st.filter = f; const n = all.filter((k) => { const wq = st.q; st.q = ""; const m = matches(k); st.q = wq; return m; }).length; st.filter = was;
      const c = el("button", "wdchip" + (st.filter === f ? " on" : "")); c.type = "button"; c.dataset.keep = "kt:f:" + f;
      c.appendChild(document.createTextNode(f + " ")); c.appendChild(el("span", "mu mono", String(n)));
      c.addEventListener("click", () => { st.filter = f; draw(); });
      chips.appendChild(c);
    });
    chips.appendChild(el("span", "grow"));
    if (st.grid) chips.appendChild(el("span", "mu wdsmall", "The browser's order · answered by " + st.grid.server + " " + ago(Date.now() - st.grid.at)));
    chips.appendChild(key("REFRESH", "ghost", () => loadGrid(true)));
    box.appendChild(chips);
    if (st.gridErr) box.appendChild(el("div", "err", st.gridErr));
    if (!st.grid) { if (!st.gridErr) box.appendChild(el("div", "note", "Reading the kits…")); return; }
    offStrip(box, st.grid.off);
    const tiles = el("div", "wdtiles shtiles kttiles"); tiles.id = "kttiles"; box.appendChild(tiles);
    drawTiles();
  }
  function drawTiles() {
    const tiles = $("kttiles"); if (!tiles || !st.grid) return;
    tiles.textContent = "";
    const list = st.grid.kits.filter(matches);
    list.forEach((k) => tiles.appendChild(tile(k, st.grid.choices, st.grid.server)));
    if (!list.length) tiles.appendChild(el("div", "note", st.grid.kits.length ? "No kit matches." : "There are no kits yet. + NEW KIT makes one."));
  }
  function stateSwitch(k, page) {
    const w = el("span", "shsw" + (k.enabled ? " on" : "")); w.title = k.enabled ? "On" : "Off"; w.setAttribute("aria-label", w.title);
    if (!admin()) return w;
    if (lockedFor(k)) { w.title = missingWords(page ? page.server : st.grid && st.grid.server); return w; }
    w.classList.add("click"); w.tabIndex = 0; w.setAttribute("role", "switch"); w.setAttribute("aria-checked", String(!!k.enabled));
    const go = (e) => { e.stopPropagation(); change("/api/kits/enable", { id: k.id, on: !k.enabled, edited: k.edited }); };
    w.addEventListener("click", go); w.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(e); } });
    return w;
  }
  function tag(text, cls) { return el("span", "kttag" + (cls ? " " + cls : ""), text); }
  function tile(k, c, server) {
    const t = el("button", "wdtile shtile kttile" + (k.enabled ? "" : " shoff")); t.type = "button"; t.dataset.keep = "kt:tile:" + k.id;
    const top = el("div", "wdtrow");
    const ic = el("span", "ktbig"); ic.appendChild(pic(iconOf(k), 30)); top.appendChild(ic);
    const nm = el("div", "grow"); nm.appendChild(el("div", "wdname", k.name || k.id)); nm.appendChild(el("div", "mono mu shid", k.id));
    top.appendChild(nm); top.appendChild(stateSwitch(k)); t.appendChild(top);
    const pv = el("div", "ktpeek"), items = k.items || [], more = items.length > 8;
    for (let i = 0; i < 8; i++) {
      const s = el("span"); const it = items[i];
      if (more && i === 7) s.appendChild(el("span", "mono mu ktmore", "+" + (items.length - 7)));
      else if (it) { s.appendChild(pic(it.item, 22)); if (it.count > 1) s.appendChild(el("span", "ktn", String(it.count))); s.title = itemName(it.item) + " × " + it.count; }
      else s.className = "e";
      pv.appendChild(s);
    }
    t.appendChild(pv);
    const tags = el("div", "wdtrow shmeta");
    tags.appendChild(tag(priceText(k), k.price > 0 ? "cost" : "free")); tags.appendChild(tag(timing(k)));
    if (!k.inBrowser) tags.appendChild(tag("Hidden from the browser", "hidn"));
    if (firstJoinText(k)) tags.appendChild(tag(firstJoinText(k), "first"));
    t.appendChild(tags);
    const needs = el("div", "wdtrow shmeta");
    if (ranksText(k, c)) needs.appendChild(tag(ranksText(k, c), "need"));
    if ((k.servers || []).length) needs.appendChild(tag("On " + k.servers.join(", "), "need"));
    (k.conditions || []).forEach((x) => needs.appendChild(tag(condText(x, c, true), "need")));
    if (needs.childNodes.length) t.appendChild(needs);
    if (!k.enabled) t.appendChild(el("span", "shlamp off", "OFF: not in the browser, not handed out"));
    if (k.missing > 0) t.appendChild(el("div", "shgone", k.missing + (k.missing === 1 ? " item " : " items ") + (server || "this server") + " cannot give (a mod not loaded there)"));
    t.appendChild(el("div", "ktfoot mono", "Taken " + num(k.taken) + (k.lastTakenBy ? " · " + k.lastTakenBy + ", " + ago(Date.now() - k.lastTakenMs) : "")));
    t.addEventListener("click", () => open(k.id));
    return t;
  }

  // K2: a kit's page
  function crumb(k, last) {
    const c = el("span", "wdcrumb shcrumb"); c.appendChild(key("KITS", "link", back));
    c.appendChild(el("span", "mu", "›"));
    if (last) { c.appendChild(key(k ? k.name || k.id : st.id, "link", () => { flushItems(); st.view = "kit"; st.msg = null; draw(); })); c.appendChild(el("span", "mu", "›")); c.appendChild(el("b", null, last)); }
    else c.appendChild(el("b", null, k ? k.name || k.id : st.id));
    return c;
  }
  function slots(k, opts) {
    opts = opts || {};
    const max = Math.max((st.page && st.page.choices && st.page.choices.maxItems) || 36, (k.items || []).length);
    const g = el("div", "ktslots" + (opts.small ? " sm" : ""));
    const items = opts.items || k.items || [];
    for (let i = 0; i < max; i++) {
      const it = items[i], s = el(opts.editing ? "button" : "span", "ktslot" + (it ? "" : " e") + (opts.editing && st.sel === i && it ? " sel" : ""));
      if (opts.editing) s.type = "button";
      if (it) {
        s.appendChild(pic(it.item, opts.small ? 18 : 28));
        if (it.count > 1) s.appendChild(el("span", "ktn", String(it.count)));
        s.title = itemName(it.item) + " × " + it.count + "\n" + it.item + (it.nbt ? "\n" + (it.nbt.length > 300 ? it.nbt.slice(0, 300) + "…" : it.nbt) : "");
        s.setAttribute("aria-label", itemName(it.item) + " × " + it.count);
      }
      if (opts.editing) editSlot(s, i, it, items);
      g.appendChild(s);
    }
    if (k.missing > 0) g.appendChild(el("div", "shgone ktspan", k.missing + (k.missing === 1 ? " item is" : " items are") + " not shown: " + (st.page && st.page.server || "this server") + " cannot read them (a mod not loaded there)."));
    return g;
  }
  function browserCard(k, c) {
    const card = el("div", "ktmc");
    const ic = el("span", "ic"); ic.appendChild(pic(iconOf(k), 32)); card.appendChild(ic);
    const tx = el("span", "tx");
    tx.appendChild(el("span", "t1", k.name || k.id));
    if (k.description) tx.appendChild(el("span", "t2", k.description));
    tx.appendChild(el("span", "t3", [priceText(k), timing(k).toLowerCase(), ranksText(k, c) || "anyone"].join(" · ")));
    card.appendChild(tx);
    return card;
  }
  function kv(rows) {
    const g = el("div", "wdkv");
    rows.forEach(([a, b, cls]) => { if (b === null) return; g.appendChild(el("span", "mu", a)); g.appendChild(el("span", cls || null, b)); });
    return g;
  }
  function sbox(title, inner) { const b = el("div", "shsbox"); b.appendChild(el("div", "sect", title)); b.appendChild(inner); return b; }
  function drawKit(box) {
    const p = st.page;
    const head = el("div", "wdhead");
    const k = p && p.kit;
    head.appendChild(crumb(k));
    head.appendChild(el("span", "grow"));
    if (k) {
      head.appendChild(stateSwitch(k, p));
      head.appendChild(el("span", "shlamp " + (k.enabled ? "op" : "off"), k.enabled ? (k.inBrowser ? "On · in the browser" : "On · hidden from the browser") : "Off"));
      const locked = lockedFor(k), why = missingWords(p.server);
      const copy = adminMark(key("COPY", null, () => copyKit())); if (locked && !copy.disabled) { copy.disabled = true; copy.title = why; } head.appendChild(copy);
      head.appendChild(adminMark(key("GIVE TO…", null, () => giveKit())));
      head.appendChild(deleteKey(k));
      const edit = adminMark(key("EDIT", "pri", () => { st.view = "edit"; st.sel = null; st.msg = null; draw(); }));
      if (!edit.disabled && locked) { edit.disabled = true; edit.title = why; }
      if (!edit.disabled && !onComputer()) { edit.disabled = true; edit.title = "Changing a kit is on a computer."; }
      head.appendChild(edit);
    }
    box.appendChild(head);
    flash(box);
    if (st.pageErr) box.appendChild(el("div", "err", st.pageErr));
    if (!p) { if (!st.pageErr) box.appendChild(el("div", "note", "Reading the kit…")); return; }
    offStrip(box, p.off);
    offBar(box, k);
    if (lockedFor(k)) box.appendChild(el("div", "shwarn", missingWords(p.server) + " It can still be deleted here."));
    const c = p.choices;
    const wrap = el("div", "ktwrap");
    const left = el("div", "ktcol");
    const cont = el("div", "shsbox");
    const ch = el("div", "wdtrow"); ch.appendChild(el("span", "sect grow", "CONTENTS")); ch.appendChild(el("span", "mono mu wdsmall", (k.items || []).length + " of " + ((c && c.maxItems) || 36) + " stacks")); cont.appendChild(ch);
    cont.appendChild(slots(k));
    cont.appendChild(el("div", "sect", "AS PLAYERS SEE IT IN THE KIT BROWSER"));
    cont.appendChild(browserCard(k, c));
    left.appendChild(cont);
    const right = el("div", "ktcol ktside");
    right.appendChild(sbox("RULES", kv([
      ["In the browser", k.inBrowser ? "Shown" : "Hidden"],
      ["Price", priceText(k), k.price > 0 ? "gd" : "tl"],
      ["Taken again", k.once ? "Never: once only" : k.cooldownSeconds > 0 ? "Every " + span(k.cooldownSeconds) : "Any time"],
      ["Ranks", ranksText(k, c) ? ranksText(k, c) + (c && c.ranksFromLadder ? " (RankUp's ladder)" : "") : "Anyone"],
      ["Servers", (k.servers || []).length ? k.servers.join(", ") : "Everywhere"],
      ["Needs", (k.conditions || []).length ? k.conditions.map((x) => condText(x, c)).join("; ") : "Nothing more"],
      ["First join", firstJoinText(k) || "Never by itself"],
      ["Description", k.description || "None"],
    ])));
    right.appendChild(sbox("RECORD", kv([
      ["Taken", num(k.taken) + (k.taken === 1 ? " time" : " times") + " on the network", "mono"],
      ["Last taken", k.lastTakenBy ? k.lastTakenBy + ", " + ago(Date.now() - k.lastTakenMs) : "Never"],
      ["Waiting", typeof k.owed === "number" ? k.owed + (k.owed === 1 ? " player" : " players") + " (as " + p.server + " counts them)" : null], // D865
      ["Made", k.created ? when(k.created) : "Before records began"],
      ["Last edited", k.edited ? when(k.edited) : "Before stamps began"],
      ["Answered by", p.server + ", " + ago(Date.now() - p.at)],
    ])));
    right.appendChild(el("div", "note", "Who has taken it and when they can again is on each player's page (PLAYERS › a player › KITS)."));
    wrap.appendChild(left); wrap.appendChild(right);
    box.appendChild(wrap);
  }
  function offBar(box, k) {
    if (k.enabled) return;
    const bar = el("div", "shoffbar");
    bar.appendChild(el("span", "grow", "OFF: not in the kit browser and not handed out at first join. A kit given with GIVE TO… still reaches the player."));
    if (admin() && !lockedFor(k)) bar.appendChild(key("TURN ON", "pri", () => change("/api/kits/enable", { id: k.id, on: true, edited: k.edited })));
    box.appendChild(bar);
  }
  // DELETE (D865): SURE? within three seconds, saying first who is still owed it
  function deleteKey(k) {
    const armed = st.armed.key === "delete:" + k.id && Date.now() - st.armed.at < 3000;
    const b = adminMark(key(armed ? "SURE? DELETE" : "DELETE", armed ? "sure" : "dg", () => {
      if (st.armed.key === "delete:" + k.id && Date.now() - st.armed.at < 3000) {
        st.armed = { key: "", at: 0 };
        change("/api/kits/delete", { id: k.id, edited: k.edited }, (r) => { if (r.ok) { st.grid = null; back(); say(r.message || "Deleted.", true); draw(); } else loadKit(false); });
        return;
      }
      st.armed = { key: "delete:" + k.id, at: Date.now() };
      const owed = typeof k.owed === "number" && k.owed > 0 ? " At least " + k.owed + (k.owed === 1 ? " player is" : " players are") + " still owed it and will not get it." : "";
      say("Deleting " + (k.name || k.id) + " takes it from the browser on every server and forgets every player's record of it." + owed + " This cannot be undone: click SURE? within three seconds.", false);
      draw();
      setTimeout(() => { if (st.armed.key === "delete:" + k.id) { st.armed = { key: "", at: 0 }; draw(); } }, 3100);
    }));
    return b;
  }

  // ---------------------------------------------------------------- changes
  // a change sent; the answer said; the page read again. A stale one says why and shows the kit as it is now.
  let queue = Promise.resolve();
  function change(path, body, after) {
    const run = queue.then(() => send(path, body, after));
    queue = run.catch(() => null);
    return run;
  }
  // a change decided before the panel's own last ones goes with the newest stamp they brought back: every stamp the panel
  // itself started from or produced for a kit is its own, however many changes were queued (audit)
  const own = new Map();
  async function send(path, body, after) {
    const mine = own.get(body.id);
    if (typeof body.edited === "number" && mine && mine.stamps.has(body.edited)) body.edited = mine.newest;
    const sentWith = body.edited;
    const r = await call(path, body, "change the kit");
    if (r.ok) st.ownUntil = Date.now() + 15000; // only a change that went through moves kitsAt (audit)
    if (r.ok && typeof r.edited === "number" && typeof sentWith === "number") {
      const m = own.get(body.id) || { stamps: new Set(), newest: 0 };
      if (m.stamps.size > 200) m.stamps.clear();
      m.stamps.add(sentWith); m.stamps.add(r.edited); m.newest = r.edited; own.set(body.id, m);
    }
    if (r.ok && typeof r.edited === "number" && st.page && st.page.kit && st.page.kit.id === body.id) st.page.kit.edited = r.edited;
    if (r.ok && typeof r.edited === "number" && st.grid) st.grid.kits.forEach((x) => { if (x.id === body.id) x.edited = r.edited; });
    say(r.message || (r.ok ? "Saved." : "It did not change."), r.ok);
    if (after) after(r);
    else if (st.view === "grid") loadGrid(false); else loadKit(false);
    return r;
  }
  function stamp() { return st.page && st.page.kit ? st.page.kit.edited : -1; }
  const set = (field, value) => change("/api/kits/set", { id: st.id, field, value, edited: stamp() });

  // a window over the page: Esc or the shade closes it (the SHOPS tab's)
  function modal(title, build, okText, okFn, okCls) {
    if (document.querySelector(".shmodalwrap")) return () => {};
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
  function field(label, input, hint) { const f = el("label", "shfield"); f.appendChild(el("span", "shfl", label)); f.appendChild(input); if (hint) f.appendChild(hint); return f; }
  function input(value, cls, ph) { const i = el("input", "wdin shin" + (cls ? " " + cls : "")); i.type = "text"; i.value = value == null ? "" : value; if (ph) i.placeholder = ph; return i; }
  const slug = (t) => String(t || "").toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 24);
  function takenIds() { const s = new Set(); (st.grid ? st.grid.kits : []).forEach((k) => s.add(k.id)); if (st.page && st.page.kit) s.add(st.page.kit.id); return s; }
  // the id box filled from the name until it is typed in; "free" or "taken" as it changes
  function idPair(nameIn, idIn) {
    const tagEl = el("span", "shfree");
    let typed = false;
    const check = () => {
      const v = idIn.value, ok = /^[a-z0-9_]{2,24}$/.test(v), taken = takenIds().has(v);
      tagEl.textContent = !v ? "" : !ok ? "2 to 24 of a to z, 0 to 9 and _" : taken ? "taken: choose another" : "free";
      tagEl.className = "shfree " + (!ok || taken ? "rd" : "tl");
      return ok && !taken;
    };
    nameIn.addEventListener("input", () => { if (!typed) idIn.value = slug(nameIn.value); check(); });
    idIn.addEventListener("input", () => { typed = true; idIn.value = idIn.value.toLowerCase(); check(); });
    return { tag: tagEl, check };
  }
  async function ensureGrid() { if (!st.grid) { const r = await call("/api/kits/grid", {}); if (r.ok) st.grid = r; } }

  // K5: a new kit, made off and empty, opened in the editor
  async function newKit() {
    await ensureGrid();
    let nameIn, idIn, pair;
    modal("NEW KIT", (body) => {
      nameIn = input("", null, "Nether Run"); nameIn.maxLength = 24; idIn = input("", "mono"); idIn.maxLength = 24; pair = idPair(nameIn, idIn);
      body.appendChild(field("NAME (UP TO 24)", nameIn));
      body.appendChild(field("ID (2 TO 24: a to z, 0 to 9 and _)", idIn, pair.tag));
      body.appendChild(el("div", "note", "Made OFF and empty, then opened in the editor: add its items and rules, then turn it on."));
    }, "MAKE", async () => {
      if (!nameIn.value.trim()) return "A name, please.";
      if (!pair.check()) return "Choose a free id of 2 to 24 lower-case letters, digits and _.";
      const r = await call("/api/kits/create", { id: idIn.value, name: nameIn.value.trim() });
      if (!r.ok) return r.message || "It was not made.";
      say(r.message || "Made, off and empty.", true); st.grid = null; open(idIn.value, onComputer() ? "edit" : "kit"); return "";
    });
  }
  // K5: a copy, made off, with no counts, last taker or records
  async function copyKit() {
    const k = st.page.kit;
    await ensureGrid();
    let nameIn, idIn, pair;
    modal("COPY " + String(k.name || k.id).toUpperCase(), (body) => {
      nameIn = input(String(k.name + " II").slice(0, 24)); nameIn.maxLength = 24; idIn = input(slug(k.id + "_2"), "mono"); idIn.maxLength = 24; pair = idPair(nameIn, idIn);
      body.appendChild(field("NAME", nameIn)); body.appendChild(field("ID", idIn, pair.tag));
      pair.check();
      body.appendChild(el("div", "note", "Its items and every rule are copied. The copy is OFF, never taken, with no last taker and no players' records; it opens in the editor."));
    }, "COPY", async () => {
      if (!nameIn.value.trim()) return "A name, please.";
      if (!pair.check()) return "Choose a free id of 2 to 24 lower-case letters, digits and _.";
      const r = await call("/api/kits/copy", { id: k.id, newId: idIn.value, name: nameIn.value.trim() });
      if (!r.ok) return r.message || "It was not copied.";
      say(r.message || "Copied, off.", true); st.grid = null; open(idIn.value, onComputer() ? "edit" : "kit"); return "";
    });
  }
  // K6: GIVE TO… any player the panel knows (D864)
  function giveKit() {
    const k = st.page.kit;
    let chosen = null, list, q, note;
    const pick = (p, row) => { chosen = p; list.querySelectorAll(".lrow").forEach((x) => x.classList.toggle("on", x === row));
      note.textContent = (p.server ? p.name + " is online on " + p.server + ": it lands now if kits are on there and there is room, otherwise the next time they join a server with kits on."
        : p.name + " is not online: it goes to them the next time they join a server with kits on.") + " No price, rank, wait, server or switch applies; it is recorded as given by you."; };
    let tok = 0;
    const find = async () => {
      const t = ++tok, v = q.value.trim(); list.textContent = "";
      if (!/^[A-Za-z0-9_]{1,16}$/.test(v)) return;
      const r = await call("/api/players/find", { q: v });
      if (t !== tok) return;
      (r.players || []).forEach((p) => {
        const row = el("button", "lrow ktprow"); row.type = "button";
        row.appendChild(el("span", "grow", p.name));
        row.appendChild(el("span", "shlamp " + (p.server ? "op" : "off"), p.server ? p.server : "offline"));
        row.addEventListener("click", () => pick(p, row));
        list.appendChild(row);
      });
      if (!(r.players || []).length) list.appendChild(el("div", "note", "Nobody by that name."));
    };
    modal("GIVE " + String(k.name || k.id).toUpperCase() + " TO", (body) => {
      q = input("", null, "A player's name"); q.addEventListener("input", find); body.appendChild(q);
      list = el("div", "ktplist"); body.appendChild(list);
      note = el("div", "note", "It goes to them now if they are online on a server with kits on and have room; otherwise the next time they join one."); body.appendChild(note);
    }, "GIVE", async () => {
      if (!chosen) return "Choose a player.";
      const r = await call("/api/kits/give", { id: k.id, player: chosen.id, name: chosen.name });
      if (!r.ok) return r.message || "It was not given.";
      say(r.message || "Given.", true); loadKit(false); return "";
    });
  }

  // ---------------------------------------------------------------- K3, K4: the editor (a computer)
  // always a copy: an edit never changes the list a save is waiting on (audit)
  function items() { return (ed.draft || st.page.kit.items || []).map((x) => ({ item: x.item, count: x.count, nbt: x.nbt || "" })); }
  // the contents change here at once and go to the game a moment later, as one save (BSP-Core takes the whole list)
  function edit(list) {
    const max = (st.page.choices && st.page.choices.maxItems) || 36;
    if (list.length > max) { say("A kit holds at most " + max + " stacks.", false); draw(); return; }
    ed.draft = list; ed.changes++;
    if (ed.timer) clearTimeout(ed.timer);
    ed.timer = setTimeout(flushItems, ITEMS_WAIT_MS);
    draw();
  }
  function flushItems() {
    if (ed.timer) { clearTimeout(ed.timer); ed.timer = null; }
    if (!ed.draft || !st.page || !st.page.kit || ed.draft === ed.sent) return; // nothing new, or this very list is on its way (audit)
    const list = ed.draft, one = ed.changes === 1, id = st.page.kit.id;
    ed.changes = 0; ed.saving = true; ed.sent = list;
    change("/api/kits/items", { id, items: list, edited: stamp(), one }, (r) => {
      ed.saving = false; ed.sent = null;
      const same = st.page && st.page.kit && st.page.kit.id === id;
      if (!r.ok && ownRefusal(r.message) && same && ed.draft === list) { // the panel's own allowance: keep the contents, go again shortly
        ed.timer = setTimeout(flushItems, RETRY_MS); draw(); return;
      }
      if (same && r.ok) st.page.kit.items = list;
      if (same && ed.draft === list) ed.draft = null; // a newer edit made meanwhile stays, and goes on its own timer
      if (same && ed.draft && !ed.timer) ed.timer = setTimeout(flushItems, ITEMS_WAIT_MS);
      loadKit(false);
    });
  }
  function editSlot(s, i, it, list) {
    if (it) {
      s.draggable = true;
      s.addEventListener("dragstart", (e) => { e.dataTransfer.setData("text/plain", "slot:" + i); e.dataTransfer.effectAllowed = "move"; });
      s.addEventListener("click", () => { st.sel = st.sel === i ? null : i; draw(); });
      s.addEventListener("wheel", (e) => { // the wheel sets the count, as the in-game editor
        e.preventDefault();
        const l = items(), c = l[i]; if (!c) return;
        c.count = Math.max(1, Math.min(64, c.count + (e.deltaY < 0 ? 1 : -1))); edit(l);
      }, { passive: false });
    }
    s.addEventListener("dragover", (e) => { e.preventDefault(); s.classList.add("drop"); });
    s.addEventListener("dragleave", () => s.classList.remove("drop"));
    s.addEventListener("drop", (e) => {
      e.preventDefault(); s.classList.remove("drop");
      const d = e.dataTransfer.getData("text/plain"), l = items(), at = Math.min(i, l.length);
      if (d.startsWith("slot:")) { const from = Number(d.slice(5)); if (from === at || !l[from]) return; const [x] = l.splice(from, 1); l.splice(from < at ? at - 1 : at, 0, x); st.sel = from < at ? at - 1 : at; edit(l); }
      else if (d.startsWith("item:")) { const x = JSON.parse(d.slice(5)); l.splice(at, 0, { item: x.item, count: 1, nbt: x.nbt || "" }); st.sel = at; edit(l); }
    });
  }
  function drawEditor(box) {
    const p = st.page, k = p.kit;
    const head = el("div", "wdhead");
    head.appendChild(crumb(k, "EDIT"));
    head.appendChild(el("span", "grow"));
    head.appendChild(el("span", "mu wdsmall", ed.timer || ed.saving ? "Saving…" : "Saved"));
    head.appendChild(stateSwitch(k, p));
    head.appendChild(key("DONE", "pri", () => { flushItems(); st.view = "kit"; st.sel = null; st.msg = null; draw(); }));
    box.appendChild(head);
    flash(box);
    offStrip(box, p.off);
    offBar(box, k);
    if (lockedFor(k)) { box.appendChild(el("div", "shwarn", missingWords(p.server))); return; }
    const wrap = el("div", "shed");
    wrap.appendChild(browserBox());
    const mid = el("div", "shedpage");
    const list = items();
    const ch = el("div", "wdtrow"); ch.appendChild(el("span", "sect grow", "CONTENTS")); ch.appendChild(el("span", "mono mu wdsmall", list.length + " of " + ((p.choices && p.choices.maxItems) || 36) + " stacks")); mid.appendChild(ch);
    mid.appendChild(slots(k, { editing: true, items: list }));
    mid.appendChild(el("div", "note", "Drop an item between stacks to put it there, or on an empty slot to add it at the end; drag a stack to move it; the wheel over a stack sets its count. A kit is a list of stacks, as in game."));
    const sel = st.sel !== null && list[st.sel] ? list[st.sel] : null;
    if (sel) {
      const row = el("div", "wdtrow ktselrow");
      row.appendChild(pic(sel.item, 24)); row.appendChild(el("span", "grow", itemName(sel.item) + " · " + sel.item));
      const n = el("input", "wdin shin mono ktcount"); n.type = "number"; n.min = 1; n.max = 64; n.value = sel.count; n.dataset.keep = "kt:count";
      n.addEventListener("change", () => { const v = Math.round(Number(n.value)); if (v >= 1 && v <= 64) { const l = items(); l[st.sel].count = v; edit(l); } else { say("A count of 1 to 64, please.", false); draw(); } });
      row.appendChild(el("span", "mu wdsmall", "Count")); row.appendChild(n);
      row.appendChild(key("MAKE IT THE ICON", null, () => change("/api/kits/icon", { id: k.id, icon: { item: sel.item, nbt: (sel.nbt || "").length <= 4096 ? sel.nbt || "" : "" }, edited: stamp() })));
      mid.appendChild(row);
      if (sel.nbt) mid.appendChild(el("div", "mono mu wdsmall ktnbt", sel.nbt.length > 400 ? sel.nbt.slice(0, 400) + "…" : sel.nbt));
    }
    // the bin: SURE? REMOVE within three seconds
    const armed = st.armed.key === "remove" && Date.now() - st.armed.at < 3000;
    const bin = el("div", "shbin" + (armed ? " hot" : ""), armed ? "SURE? REMOVE: drop it again, or click here" : "Drop a stack here to take it out (the stacks after it move up)");
    bin.addEventListener("dragover", (e) => { e.preventDefault(); bin.classList.add("hot"); });
    bin.addEventListener("dragleave", () => { if (!armed) bin.classList.remove("hot"); });
    const removeAt = (from) => {
      if (st.armed.key === "remove" && st.armed.from === from && Date.now() - st.armed.at < 3000) { st.armed = { key: "", at: 0 }; const l = items(); l.splice(from, 1); st.sel = null; edit(l); return; }
      st.armed = { key: "remove", at: Date.now(), from }; draw();
      setTimeout(() => { if (st.armed.key === "remove") { st.armed = { key: "", at: 0 }; draw(); } }, 3100);
    };
    bin.addEventListener("drop", (e) => { e.preventDefault(); const d = e.dataTransfer.getData("text/plain"); if (d.startsWith("slot:")) removeAt(Number(d.slice(5))); });
    bin.addEventListener("click", () => { if (armed && typeof st.armed.from === "number") removeAt(st.armed.from); else if (sel) removeAt(st.sel); });
    mid.appendChild(bin);
    wrap.appendChild(mid);
    wrap.appendChild(drawer(p, k));
    box.appendChild(wrap);
  }
  function browserBox() {
    const b = el("div", "shbrowser");
    b.appendChild(el("div", "shlab", "ITEMS"));
    const cat = typeof Pictures !== "undefined" ? Pictures.ready() : null;
    const add = (it) => { const l = items(); l.push({ item: it.item, count: 1, nbt: it.nbt || "" }); st.sel = l.length - 1; edit(l); };
    if (!cat) {
      b.appendChild(el("div", "note", "No item pictures yet (an admin's /su icons makes them). Type an item id to add it."));
      const i = input(ed.typed, "mono", "minecraft:iron_ingot"); i.dataset.keep = "kted:typed"; i.addEventListener("input", () => { ed.typed = i.value.trim().toLowerCase(); });
      b.appendChild(i);
      b.appendChild(key("ADD AT THE END", "pri", () => { if (/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(ed.typed)) add({ item: ed.typed }); else { say("An item id is like minecraft:iron_ingot.", false); draw(); } }));
      return b;
    }
    const q = input(ed.q, null, "Search items or ids"); q.dataset.keep = "kted:q";
    q.addEventListener("input", () => { ed.q = q.value; drawItems(add); });
    b.appendChild(q);
    const tabs = el("div", "wdchips shbtabs");
    [-1, ...cat.tabs.map((_, i) => i)].forEach((i) => {
      const c = el("button", "wdchip" + (ed.tab === i ? " on" : ""), i < 0 ? "All" : cat.tabs[i].name); c.type = "button";
      c.addEventListener("click", () => { ed.tab = i; tabs.querySelectorAll(".wdchip").forEach((x) => x.classList.toggle("on", x === c)); drawItems(add); });
      tabs.appendChild(c);
    });
    b.appendChild(tabs);
    const count = el("div", "mu wdsmall"); count.id = "ktedcount"; b.appendChild(count);
    const grid = el("div", "shitems"); grid.id = "ktitems"; b.appendChild(grid);
    b.appendChild(el("div", "note", "Drag an item onto the contents, or click it to add it at the end. Items with data keep it."));
    setTimeout(() => drawItems(add), 0);
    return b;
  }
  function drawItems(add) {
    const grid = $("ktitems"), count = $("ktedcount"), cat = Pictures.ready(); if (!grid || !cat) return;
    grid.textContent = "";
    const t = ed.q.trim().toLowerCase();
    let list = ed.tab >= 0 && cat.tabs[ed.tab] ? cat.tabs[ed.tab].items.map((i) => [cat.items[i], i]) : cat.items.map((it, i) => [it, i]);
    if (t) list = list.filter(([it]) => it && (String(it.name || "").toLowerCase().includes(t) || it.id.includes(t)));
    count.textContent = list.length + (list.length === 1 ? " item" : " items") + (list.length > 120 ? " · the first 120 shown" : "");
    list.slice(0, 120).forEach(([it]) => {
      if (!it) return;
      const c = el("button", "shitem"); c.type = "button"; c.title = (it.name || it.id) + "\n" + it.id; c.draggable = true;
      c.appendChild(pic(it.id, 32));
      const data = { item: it.id, nbt: it.nbt || "" };
      c.addEventListener("dragstart", (e) => { e.dataTransfer.setData("text/plain", "item:" + JSON.stringify(data)); e.dataTransfer.effectAllowed = "copy"; });
      c.addEventListener("click", () => add(data));
      grid.appendChild(c);
    });
  }
  // the drawer: KIT and WHO AND WHERE; each field saved as it is changed
  function chip(text, on, fn, disabled) { const c = el("button", "wdchip ktopt" + (on ? " on" : ""), text); c.type = "button"; c.disabled = !!disabled; c.addEventListener("click", fn); return c; }
  function tick(text, on, fn) { const t = el("label", "wdtick ktick"); const c = el("input"); c.type = "checkbox"; c.checked = !!on; c.addEventListener("change", () => fn(c.checked)); t.appendChild(c); t.appendChild(el("span", null, text)); return t; }
  function drawer(p, k) {
    const d = el("div", "shdrawer ktdrawer");
    const h = el("div", "shdh"); h.appendChild(pic(iconOf(k), 28)); const t = el("div", "grow"); t.appendChild(el("div", "wdname", k.name || k.id)); t.appendChild(el("div", "mono mu shid", k.id)); h.appendChild(t);
    d.appendChild(h);
    const tabs = el("div", "shtabs");
    ["KIT", "WHO AND WHERE"].forEach((x) => { const b = el("button", "shtab" + (st.tab === x ? " on" : ""), x); b.type = "button"; b.addEventListener("click", () => { st.tab = x; draw(); }); tabs.appendChild(b); });
    d.appendChild(tabs);
    const body = el("div", "shdbody");
    if (st.tab === "KIT") kitTab(body, p, k); else whoTab(body, p, k);
    d.appendChild(body);
    return d;
  }
  function kitTab(body, p, k) {
    const name = input(k.name, null); name.maxLength = 24; name.dataset.keep = "kt:name";
    name.addEventListener("change", () => { const v = name.value.trim(); if (v && v !== k.name) set("name", v); });
    body.appendChild(field("NAME (UP TO 24)", name));
    const desc = el("textarea", "wdin shin kttext"); desc.maxLength = 80; desc.value = k.description || ""; desc.dataset.keep = "kt:desc"; desc.rows = 2;
    const left = el("span", "mu wdsmall", (k.description || "").length + "/80");
    desc.addEventListener("input", () => { left.textContent = desc.value.length + "/80"; });
    desc.addEventListener("change", () => { if (desc.value.trim() !== (k.description || "")) set("description", desc.value.trim()); });
    body.appendChild(field("DESCRIPTION (AS THE BROWSER SHOWS IT)", desc, left));
    const iconRow = el("div", "wdtrow ktdrop");
    iconRow.appendChild(pic(iconOf(k), 24));
    iconRow.appendChild(el("span", "grow", k.icon && k.icon.item ? itemName(k.icon.item) : "The first item (" + ((k.items || [])[0] ? itemName(k.items[0].item) : "none yet") + ")"));
    iconRow.addEventListener("dragover", (e) => { e.preventDefault(); iconRow.classList.add("drop"); });
    iconRow.addEventListener("dragleave", () => iconRow.classList.remove("drop"));
    iconRow.addEventListener("drop", (e) => {
      e.preventDefault(); iconRow.classList.remove("drop");
      const dd = e.dataTransfer.getData("text/plain"); let it = null;
      if (dd.startsWith("item:")) it = JSON.parse(dd.slice(5)); else if (dd.startsWith("slot:")) it = items()[Number(dd.slice(5))];
      if (it) change("/api/kits/icon", { id: k.id, icon: { item: it.item, nbt: (it.nbt || "").length <= 4096 ? it.nbt || "" : "" }, edited: stamp() });
    });
    if (k.icon && k.icon.item) iconRow.appendChild(key("FIRST ITEM", "ghost", () => change("/api/kits/icon", { id: k.id, icon: null, edited: stamp() })));
    body.appendChild(field("ICON (DROP AN ITEM HERE)", iconRow));
    body.appendChild(tick("Shown in the kit browser", k.inBrowser, (v) => set("inBrowser", v)));
    const price = input(k.price > 0 ? String(k.price) : "", "mono", "free"); price.dataset.keep = "kt:price"; price.inputMode = "numeric";
    price.addEventListener("change", () => { const v = price.value.trim() === "" ? 0 : Math.round(Number(price.value)); if (Number.isFinite(v) && v >= 0 && v <= 1e12) { if (v !== k.price) set("price", v); } else { say("A price is a whole number of pieces, up to a trillion; empty for free.", false); draw(); } });
    body.appendChild(field("PRICE IN PIECES (EMPTY FOR FREE)", price));
    const mode = k.once ? "once" : k.cooldownSeconds > 0 ? "every" : "any"; // a once-only kit may keep a wait from before: it does not apply
    const chips = el("div", "wdchips");
    chips.appendChild(chip("Any time", mode === "any", () => { if (k.once) set("once", false); if (k.cooldownSeconds > 0) set("cooldownSeconds", 0); }));
    chips.appendChild(chip("Once", mode === "once", () => { if (!k.once) set("once", true); })); // once outranks a wait: one change, not two
    chips.appendChild(chip("Every…", mode === "every", () => { if (k.once) set("once", false); if (!(k.cooldownSeconds > 0)) set("cooldownSeconds", 86400); }));
    body.appendChild(field("TAKEN AGAIN", chips));
    if (mode === "every") {
      const units = [["minutes", 60], ["hours", 3600], ["days", 86400]];
      const unit = k.cooldownSeconds % 86400 === 0 ? 2 : k.cooldownSeconds % 3600 === 0 ? 1 : 0;
      const n = input(String(Math.round(k.cooldownSeconds / units[unit][1])), "mono ktn5"); n.dataset.keep = "kt:every"; n.inputMode = "numeric";
      const row = el("div", "wdtrow"); row.appendChild(n);
      let u = unit;
      const save = () => { const v = Math.round(Number(n.value)); if (v >= 1 && v * units[u][1] <= 1e12) { if (v * units[u][1] !== k.cooldownSeconds) set("cooldownSeconds", v * units[u][1]); } else { say("A whole number of at least 1, please.", false); draw(); } };
      n.addEventListener("change", save);
      units.forEach(([w], i) => row.appendChild(chip(w, i === u, () => { u = i; save(); })));
      body.appendChild(row);
    }
    body.appendChild(el("div", "note", "Each field is saved as it is changed. If the kit was changed in game since you opened it, the change is refused and the page reads again, saying so."));
  }
  function whoTab(body, p, k) {
    const c = p.choices || {};
    const ranks = (k.ranks || []);
    if (c.ranksFromLadder && (c.ranks || []).length) {
      const ladder = c.ranks.map((x) => String(x).toLowerCase()), from = ranks.length ? ladder.indexOf(ranks[0]) : -1;
      const steps = el("div", "ktladder");
      steps.appendChild(chip("Anyone", !ranks.length, () => { if (ranks.length) set("ranks", []); }));
      ladder.forEach((r, i) => steps.appendChild(chip(cap(r), i === from, () => set("ranks", ladder.slice(i)))));
      if (from >= 0) [...steps.children].forEach((x, i) => { if (i > from + 1) x.classList.add("up"); });
      body.appendChild(field("RANKS (RANKUP'S LADDER: A CLICK ON A STEP)", steps, el("span", "note", ranksText(k, c) ? ranksText(k, c) + "." : "Anyone may take it.")));
    } else if ((c.ranks || []).length) {
      const row = el("div", "wdchips");
      row.appendChild(chip("Anyone", !ranks.length, () => { if (ranks.length) set("ranks", []); }));
      c.ranks.forEach((r) => { const lr = String(r).toLowerCase(), on = ranks.includes(lr); row.appendChild(chip(r, on, () => set("ranks", on ? ranks.filter((x) => x !== lr) : [...ranks, lr]))); });
      body.appendChild(field("RANKS (LUCKPERMS GROUPS: ANY OF THEM)", row));
    } else {
      const i = input(ranks.join(", "), null, "anyone"); i.dataset.keep = "kt:ranks";
      i.addEventListener("change", () => set("ranks", i.value));
      body.appendChild(field("RANKS (GROUP NAMES, COMMAS BETWEEN)", i));
    }
    const servers = k.servers || [];
    const srow = el("div", "wdchips");
    srow.appendChild(chip("Everywhere", !servers.length, () => { if (servers.length) set("servers", []); }));
    (c.servers || []).forEach((s) => { const on = servers.includes(s); srow.appendChild(chip(s, on, () => set("servers", on ? servers.filter((x) => x !== s) : [...servers, s]))); });
    body.appendChild(field("SERVERS IT CAN BE TAKEN ON", srow));
    const conds = k.conditions || [];
    const needs = el("div", "ktcol");
    (c.conditions || []).forEach((x) => needs.appendChild(tick(x.label || x.key, conds.includes(x.key), (v) => set("conditions", v ? [...conds, x.key] : conds.filter((y) => y !== x.key)))));
    if (!(c.conditions || []).length) needs.appendChild(el("div", "note", "BSP-Core offers no conditions."));
    body.appendChild(field("NEEDS", needs, el("span", "note", "The conditions BSP-Core offers; more appear here as it adds them.")));
    const g = el("div", "wdchips");
    [["NONE", "Never by itself"], ["NETWORK", "Once on the network"], ["SERVERS", "On each chosen server"]].forEach(([v, w]) => g.appendChild(chip(w, k.grant === v, () => { if (k.grant !== v) set("grant", v); })));
    body.appendChild(field("FIRST JOIN", g));
    if (k.grant === "SERVERS") {
      const gs = k.grantServers || [], row = el("div", "wdchips");
      row.appendChild(chip("Every server", !gs.length, () => { if (gs.length) set("grantServers", []); }));
      (c.servers || []).forEach((s) => { const on = gs.includes(s); row.appendChild(chip(s, on, () => set("grantServers", on ? gs.filter((x) => x !== s) : [...gs, s]))); });
      body.appendChild(row);
    }
    body.appendChild(el("div", "note", "Handed out at first join, it lands once the player's inventory has arrived from the last server."));
  }

  // ---------------------------------------------------------------- SU-174: a player's kits, for the PLAYERS tab (D866)
  async function playerSection(host2, player, name) {
    host2.textContent = "";
    const box = el("div", "ktplayer");
    host2.appendChild(box);
    box.appendChild(el("div", "note", "Reading their kits…"));
    const r = await call("/api/kits/records", { player });
    box.textContent = "";
    if (!r.ok) { box.appendChild(el("div", "err", r.message || "Their kits could not be read.")); return; }
    const rows = r.rows || [];
    if (!r.fresh) box.appendChild(el("div", "shwarn", "From " + r.server + "'s own copy: the database did not answer."));
    if (!rows.length) { box.appendChild(el("div", "note", "No kit taken, and none waiting.")); return; }
    const now = Date.now();
    const t = el("div", "ktrecs");
    rows.forEach((x) => {
      const row = el("div", "ktrec");
      const nm = el("div", "grow");
      nm.appendChild(el("div", null, x.here ? (x.kitName || x.kit) : x.kit + " (deleted)"));
      const bits = [];
      if (x.scope) bits.push("Given at first join on " + x.scope);
      else {
        bits.push("Taken " + x.count + (x.count === 1 ? " time" : " times") + (x.lastMs > 0 ? ", last " + ago(now - x.lastMs) : ""));
        if (x.owed > 0) bits.push(x.owed + " waiting");
        if (typeof x.again === "number") bits.push(x.again < 0 ? "Never again (once only)" : x.again <= now ? "Any time" : "Again in " + span(Math.round((x.again - now) / 1000)));
      }
      nm.appendChild(el("div", "mu wdsmall", bits.join(" · ")));
      row.appendChild(nm);
      const k = "reset:" + player + ":" + x.kit + ":" + (x.scope || ""); // two rows of one kit never share a confirm (audit)
      const armed = st.armed.key === k && Date.now() - st.armed.at < 3000;
      const b = adminMark(key(armed ? "SURE? RESET" : "RESET", armed ? "sure" : "ghost", async () => {
        if (st.armed.key === k && Date.now() - st.armed.at < 3000) {
          st.armed = { key: "", at: 0 };
          const a = await call("/api/kits/reset", { player, id: x.kit, name }, "reset a kit");
          box.appendChild(el("div", a.ok ? "shok" : "err", a.message || (a.ok ? "Reset." : "It was not reset.")));
          setTimeout(() => { if (host2.dataset.shown !== "0") playerSection(host2, player, name); }, a.ok ? 900 : 2500);
          return;
        }
        st.armed = { key: k, at: Date.now() }; b.textContent = "SURE? RESET"; b.className = "wdkey sure";
        setTimeout(() => { if (st.armed.key === k) { st.armed = { key: "", at: 0 }; b.textContent = "RESET"; b.className = "wdkey ghost"; } }, 3100);
      }));
      b.title = "Forget this record: a once-only kit can be taken again, the wait starts fresh, a kit still owed is owed no longer.";
      row.appendChild(b);
      t.appendChild(row);
    });
    box.appendChild(t);
    box.appendChild(el("div", "note", "The next time counts the kit's own wait only: its rank, server and price rules still apply."));
    // RESET ALL: two confirms (D866)
    let step = 0, at = 0;
    const all = adminMark(key("RESET ALL", "dg", async () => {
      if (Date.now() - at > 3000) step = 0;
      step++; at = Date.now();
      if (step === 1) { all.textContent = "SURE? RESET ALL"; all.className = "wdkey sure"; }
      else if (step === 2) { all.textContent = "REALLY? EVERY KIT"; }
      else {
        step = 0;
        const a = await call("/api/kits/reset", { player, all: true, name }, "reset every kit");
        box.appendChild(el("div", a.ok ? "shok" : "err", a.message || (a.ok ? "Reset." : "It was not reset.")));
        setTimeout(() => { if (host2.dataset.shown !== "0") playerSection(host2, player, name); }, a.ok ? 900 : 2500);
        return;
      }
      setTimeout(() => { if (Date.now() - at >= 3000 && step > 0) { step = 0; all.textContent = "RESET ALL"; all.className = "wdkey dg"; } }, 3100);
    }));
    all.title = "Forget every kit record of this player, across the network.";
    const foot = el("div", "wdtrow"); foot.appendChild(el("span", "grow")); foot.appendChild(all); box.appendChild(foot);
  }

  // the KITS section on a player's card (D866): shut until opened, kept through the card's redraws
  const sections = new Map();
  function section(p) {
    let s = sections.get(p.id);
    if (s) return s;
    if (sections.size > 40) sections.clear();
    s = el("div", "ktpsec");
    const head = el("div", "wdtrow");
    head.appendChild(el("div", "sect grow", "KITS"));
    const inner = el("div");
    let shown = false;
    const refresh = key("REFRESH", "ghost", () => playerSection(inner, p.id, p.name)); refresh.classList.add("hidden");
    const toggle = key("SHOW", "ghost", () => {
      shown = !shown; toggle.textContent = shown ? "HIDE" : "SHOW"; refresh.classList.toggle("hidden", !shown);
      inner.dataset.shown = shown ? "1" : "0";
      if (shown) playerSection(inner, p.id, p.name); else inner.textContent = "";
    });
    toggle.dataset.keep = "ktsec:" + p.id;
    head.appendChild(refresh); head.appendChild(toggle);
    s.appendChild(head); s.appendChild(inner);
    sections.set(p.id, s);
    return s;
  }

  // ---------------------------------------------------------------- the frame
  function mount(main) {
    host = main;
    if (typeof Pictures !== "undefined") Pictures.load().then(() => { if (host) draw(); });
    draw();
    if (st.view === "grid") loadGrid(false); else loadKit(false);
  }
  function unmount(main) { if (host && host === main) { flushItems(); host = null; } }
  function home() { flushItems(); st.view = "grid"; st.id = null; st.page = null; }
  // every live tick: a server's kitsAt moved (a kit changed, D845), or a minute since the last read: read again
  const seenAt = new Map();
  function onLive(live) {
    let moved = false;
    (live && live.servers || []).forEach((s) => {
      if (typeof s.kitsAt !== "number") return;
      const k = s.server.toLowerCase(), was = seenAt.get(k);
      seenAt.set(k, s.kitsAt);
      if (was !== undefined && was !== s.kitsAt) moved = true;
    });
    if (!host) return;
    if (moved && st.ownUntil && Date.now() < st.ownUntil) moved = false; // the page's own change, already read again
    if (ed.timer || ed.saving) return; // contents waiting to be saved: never read over them
    const a = document.activeElement;
    if (st.view === "edit" && a && host.contains(a) && /^(INPUT|SELECT|TEXTAREA)$/.test(a.tagName) && !moved) return; // not while someone types
    if (moved || Date.now() - st.readAt > REFRESH_MS) {
      st.readAt = Date.now();
      if (st.view === "grid") loadGrid(false); else loadKit(false);
    }
  }
  return { mount, unmount, home, onLive, playerSection, section };
})();
