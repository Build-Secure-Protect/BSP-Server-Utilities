"use strict";
// More › Market and More › Lottery (SU-52; D400 to D415, look A of set Y). The Market Board's lots and the Wanted Board's
// orders under one switch, read from a game server through BSP-Core 1.16 (kept 30 s by the panel, REFRESH reads again);
// the Lottery page led by the pot with DRAW beside it (Admins and Owners, SURE? within 3 s), the names in and the last
// wins; a draw anywhere is counted in the pot card's corner on every open panel, then its winner shown, with a gold line
// at the top of every page until it is closed (D415 R1). Text from the network is set with textContent only.

const fmtN = (n) => Number(n || 0).toLocaleString("en-GB");
function spanOf(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return s + " s";
  const m = Math.round(s / 60);
  if (m < 60) return m + " min";
  const h = Math.floor(m / 60);
  if (h < 24) return h + " h";
  const d = Math.floor(h / 24), rh = h % 24;
  return d + " d" + (rh && d < 7 ? " " + rh + " h" : "");
}
function personKey(id, name, cls) {
  const p = live.players.find((x) => x.id === id);
  const b = el("button", "mkwho" + (cls ? " " + cls : "")); b.type = "button";
  name = name || "?"; // a lot or order without a name never blanks the page (audit)
  b.appendChild(Players.face(p || { id, name }, 18));
  const n = el("span", "nm", name || "?"); b.appendChild(n);
  if (p) b.appendChild(el("span", "mkdot"));
  b.title = p ? name + " is online on " + p.server : name + " is offline";
  b.addEventListener("click", () => {
    if (p) { openPlayerSheet(id); return; }
    openOffline({ id, name }); // D415 R2: their Find a player page
  });
  return b;
}
function stackSlot(it, selected, onTap) {
  const s = el("button", "islot mkslot" + (selected ? " sel" : "")); s.type = "button";
  const pic = Pictures.icon(Pictures.indexOf(it.id, it.v), 24);
  s.appendChild(pic || el("span", "ilt", Items.letters(it)));
  if (it.n > 1) s.appendChild(el("span", "ct", it.n > 9999 ? Math.round(it.n / 1000) + "k" : String(it.n)));
  if (it.ench && it.ench.length) s.classList.add("glint");
  s.title = Items.name(it) + (it.n > 1 ? " × " + it.n : "");
  if (onTap) s.addEventListener("click", onTap); else s.disabled = true;
  return s;
}

// ---------------------------------------------------------------- the market (D401 to D405, D412, D414, D415 R2, R3)
const Market = (() => {
  const st = { data: null, loading: false, triedAt: 0, board: "lots", q: "", sort: "new", who: null, tip: null, answer: null, cancel: null, done: null, again: false, againRefresh: false };
  const SORTS = [["new", "Newest"], ["ends", "Ending soon"], ["high", "Price high to low"], ["low", "Price low to high"]];

  async function load(refresh) {
    if (st.loading) { st.again = true; st.againRefresh = st.againRefresh || !!refresh; return; } // asked while a read runs (a cancel's re-read): read once more after it (audit)
    st.loading = true; st.triedAt = Date.now();
    const r = await call("/api/market", refresh ? { refresh: true } : {});
    st.loading = false;
    if (r.ok || r.down || !st.data) st.data = r; else st.answer = r.message; // a refused REFRESH keeps the copy on screen
    if (st.again) { const again = st.againRefresh; st.again = false; st.againRefresh = false; load(again); }
    if (tab === "More" && moreView === "market") { App.refreshMore(); return; }
    const a = document.activeElement; // a sheet's market line: drawn once nothing in the sheet is being typed in
    whenFree(a && a.closest ? a.closest("#pdetail, .offsheet, .mcard") : null, () => App.refresh());
  }

  function mine(list, key) { return st.who ? list.filter((x) => x[key] === st.who.id) : list; }
  function matches(words, x) {
    if (!words) return true;
    const hay = [x.sellerName || x.buyerName || ""].concat((x.items || (x.item ? [x.item] : [])).map((it) => Items.name(it) + " " + (it.name || "") + " " + it.id)).join(" ").toLowerCase();
    return words.split(/\s+/).every((w) => hay.includes(w));
  }
  function sorted(list, price) {
    const l = list.slice();
    if (st.sort === "ends") l.sort((a, b) => a.ends - b.ends);
    else if (st.sort === "high") l.sort((a, b) => price(b) - price(a));
    else if (st.sort === "low") l.sort((a, b) => price(a) - price(b));
    else l.sort((a, b) => (b.listed || b.placed || 0) - (a.listed || a.placed || 0));
    return l;
  }

  function lotEl(l, now) {
    const c = el("div", "mklot");
    const slots = el("div", "mks");
    (l.items || []).forEach((it, i) => slots.appendChild(stackSlot(it, st.tip && st.tip.lot === l.id && st.tip.i === i,
      () => { st.tip = st.tip && st.tip.lot === l.id && st.tip.i === i ? null : { lot: l.id, i }; App.refreshMore(); })));
    slots.querySelectorAll(".islot").forEach((x, i) => { x.dataset.keep = "lot:" + l.id + ":" + i; });
    c.appendChild(slots);
    c.appendChild(personKey(l.seller, l.sellerName, "mkw"));
    const p = el("div", "mkp"); p.appendChild(el("span", "mkprice", fmtN(l.price))); p.appendChild(el("span", "mu", " pieces")); c.appendChild(p);
    c.appendChild(el("div", "mkt sub", "listed " + spanOf(now - l.listed) + " ago · " + (l.ends > now ? "ends in " + spanOf(l.ends - now) : "ended, closing soon")));
    const sv = el("div", "mksv"); sv.appendChild(el("span", "mksvn", l.server)); c.appendChild(sv);
    if (st.tip && st.tip.lot === l.id && l.items[st.tip.i]) {
      const it = Object.assign({ i: st.tip.i }, l.items[st.tip.i]);
      const t = Items.describe(it, "lot"); t.classList.add("mktip"); c.appendChild(t);
    }
    cancelPart(c, l.id, true, (l.items || []).map((it) => Items.name(it) + (it.n > 1 ? " × " + it.n : "")).join(", "), l.sellerName);
    return c;
  }
  // D507: CANCEL on every lot and order for Admins, Owners and Moderators with the bank power (the Bank & Market page's box)
  function cancelPart(c, id, lot, what, owner, held) {
    if (!Bank.mayAct()) return;
    const part = el("div", "mkcx");
    if (st.cancel && st.cancel.id === id) {
      part.appendChild(Bank.cancelBox(st.cancel, { owner: owner || "?", server: st.data && st.data.server || "", refused: (msg) => { if (!st.cancel) st.answer = msg; App.refreshMore(); load(true); },
        back: () => { st.cancel = null; App.refreshMore(); },
        done: (msg) => { st.cancel = null; st.done = msg; load(true); App.refreshMore(); } }));
    } else {
      const k = el("button", "key danger bkcancel", "CANCEL"); k.type = "button"; k.dataset.keep = "mkcancel:" + id;
      k.addEventListener("click", () => { st.cancel = { id, lot, what: what || "?", reason: "", armed: 0, busy: false, answer: null, held }; st.done = null; App.refreshMore(); });
      part.appendChild(k);
    }
    c.appendChild(part);
  }
  function orderEl(o, now) {
    const c = el("div", "mkord");
    c.appendChild(stackSlot(o.item, false, null));
    const g = el("div", "mkog");
    g.appendChild(el("div", "nm", Items.name(o.item)));
    const togo = Math.max(0, o.wanted - o.filled);
    g.appendChild(el("div", "sub", togo + " of " + o.wanted + " to go · " + (o.ends > now ? "ends in " + spanOf(o.ends - now) : "ended, closing soon")));
    const bar = el("div", "mkbar"); const fill = el("i"); fill.style.width = Math.round(100 * o.filled / Math.max(1, o.wanted)) + "%"; bar.appendChild(fill); g.appendChild(bar);
    c.appendChild(g);
    const pr = el("div", "mkop"); const each = el("div"); each.appendChild(el("span", "mkprice", fmtN(o.each))); each.appendChild(el("span", "mu", " each")); pr.appendChild(each);
    pr.appendChild(el("div", "sub", fmtN(o.each * o.wanted) + " in all")); c.appendChild(pr);
    const who = personKey(o.buyer, o.buyerName, "mkw"); who.prepend(el("span", "mu", "for ")); c.appendChild(who);
    cancelPart(c, o.id, false, (o.wanted > 1 ? o.wanted + " " : "") + Items.name(o.item), o.buyerName, Math.max(0, o.wanted - o.filled) * o.each);
    return c;
  }

  function view(main) {
    main.appendChild(el("h2", null, "Market"));
    const d = st.data;
    if (!d) { if (!st.loading) load(false); main.appendChild(el("div", "note", "Reading the market…")); return; }
    if (!d.ok && !d.down) {
      main.appendChild(el("div", "err", d.message));
      const again = el("button", "btn sm ghost", "Try again"); again.type = "button"; again.addEventListener("click", () => { st.data = null; load(false); App.refreshMore(); });
      main.appendChild(again); return;
    }
    const lots = mine(d.lots || [], "seller"), orders = mine(d.orders || [], "buyer");
    const seg = el("div", "seg mkseg");
    [["lots", "Market Board", lots.length], ["orders", "Wanted Board", orders.length]].forEach(([k, label, n]) => {
      const b = el("button", st.board === k ? "on" : "", label + (d.down ? "" : " · " + n)); b.type = "button"; b.dataset.keep = "mkboard:" + k;
      b.addEventListener("click", () => { st.board = k; st.tip = null; App.refreshMore(); });
      seg.appendChild(b);
    });
    main.appendChild(seg);
    if (st.who) {
      const chip = el("button", "chip mkchip"); chip.type = "button";
      chip.appendChild(el("span", null, st.who.name + " · seller or buyer")); chip.appendChild(el("b", "tl", "✕"));
      chip.addEventListener("click", () => { st.who = null; App.refreshMore(); });
      main.appendChild(chip);
    }
    const bar = el("div", "mkbarrow");
    const q = el("input", "search mkq"); q.type = "search"; q.placeholder = st.board === "lots" ? "Item or seller" : "Item or buyer"; q.value = st.q; q.maxLength = 40; q.dataset.keep = "mkq";
    const sort = el("select", "mksort"); sort.dataset.keep = "mksort";
    SORTS.forEach(([v, t]) => { const o = el("option", null, t); o.value = v; o.selected = v === st.sort; sort.appendChild(o); });
    bar.appendChild(q); bar.appendChild(sort); main.appendChild(bar);
    const age = el("div", "mkage");
    const readMs = st.board === "lots" ? d.lotsReadMs : d.ordersReadMs;
    age.appendChild(el("span", d.down ? "rd" : "mu", d.down ? "not read" : "market read " + spanOf(Date.now() - (readMs > 0 ? readMs : d.at)) + " ago · from " + d.server));
    const rf = el("button", "btn sm ghost mkrf", st.loading ? "Reading…" : "REFRESH"); rf.type = "button"; rf.disabled = st.loading; rf.dataset.keep = "mkrf";
    rf.addEventListener("click", () => { st.answer = null; load(true); App.refreshMore(); });
    age.appendChild(rf); main.appendChild(age);
    if (st.answer) main.appendChild(el("div", "err", st.answer));
    if (st.done) main.appendChild(el("div", "okm", st.done));
    if (d.down) { main.appendChild(el("div", "err", d.message + " An old copy is never shown in its place; REFRESH asks again.")); return; } // D414
    const box = el("div", "mklist");
    const draw = () => {
      box.textContent = "";
      const now = Date.now(), words = st.q.trim().toLowerCase();
      if (st.board === "lots") {
        const list = sorted(lots.filter((l) => matches(words, l)), (l) => l.price);
        if (list.length) { // shown from 900 px by the stylesheet, so a resize needs no redraw (audit)
          const h = el("div", "mklot mkhead");
          ["ITEMS", "SELLER", "PRICE", "LISTED · ENDS", "SERVER"].forEach((t) => h.appendChild(el("div", "sub", t)));
          box.appendChild(h);
        }
        list.forEach((l) => box.appendChild(lotEl(l, now)));
        if (!list.length) box.appendChild(el("div", "note", words ? "No lot matches “" + st.q.trim() + "”." : st.who ? st.who.name + " has no lots on the market." : "The Market Board is empty."));
      } else {
        const list = sorted(orders.filter((o) => matches(words, o)), (o) => o.each * o.wanted);
        list.forEach((o) => box.appendChild(orderEl(o, now)));
        if (!list.length) box.appendChild(el("div", "note", words ? "No order matches “" + st.q.trim() + "”." : st.who ? st.who.name + " has no buy orders open." : "The Wanted Board is empty."));
      }
      if (d.cut) box.appendChild(el("div", "note", "Only the newest lots and orders fit in one read; search narrows what is shown, not what was read."));
    };
    q.addEventListener("input", () => { st.q = q.value; draw(); }); // the list alone, so typing keeps its focus
    sort.addEventListener("change", () => { st.sort = sort.value; draw(); });
    draw();
    main.appendChild(box);
  }

  /** A player's lots and orders in the copy the panel holds; null before a read (D405). */
  function counts(id) {
    const d = st.data;
    if (!d || !d.ok) return null;
    return { lots: (d.lots || []).filter((l) => l.seller === id).length, orders: (d.orders || []).filter((o) => o.buyer === id).length };
  }
  /** "On the market: 3 lots, 1 order" for a player's sheet or Find a player page; nothing when they have none (D405). */
  function line(p) {
    if ((!st.data || Date.now() - (st.data.at || 0) > 60_000) && !st.loading && Date.now() - st.triedAt > 30_000) load(false);
    const c = counts(p.id);
    if (!c || (!c.lots && !c.orders)) return null;
    const b = el("button", "mkline"); b.type = "button";
    b.appendChild(el("span", "gd", "◆"));
    b.appendChild(el("span", "grow", "On the market: " + c.lots + (c.lots === 1 ? " lot, " : " lots, ") + c.orders + (c.orders === 1 ? " order" : " orders")));
    b.appendChild(el("span", "tl", "Open ›"));
    b.addEventListener("click", () => openMarket(p));
    return b;
  }
  function forPlayer(p) { st.who = { id: p.id, name: p.name }; st.board = counts(p.id) && !counts(p.id).lots && counts(p.id).orders ? "orders" : "lots"; st.q = ""; st.tip = null; }
  return { view, line, forPlayer, reset: () => { st.who = null; st.tip = null; st.answer = null; st.cancel = null; st.done = null; if (st.data && Date.now() - (st.data.at || 0) > 30_000) st.data = null; } };
})();

// ---------------------------------------------------------------- the lottery (D408 to D411, D413, D415 R1, R3)
const Lottery = (() => {
  const st = { data: null, loading: false, q: "", armed: 0, answer: null, draw: null, won: null, ticker: 0, drawing: false };

  async function load() {
    if (st.loading) return;
    st.loading = true;
    const r = await call("/api/lottery", {});
    st.loading = false; st.data = r;
    if (r.draw && !st.draw) st.draw = { since: r.draw.since || Date.now(), server: r.draw.server, seen: Date.now() };
    if (shown()) App.refreshMore();
  }
  function shown() { return tab === "More" && moreView === "lottery"; }

  function countdown() {
    const since = st.draw && st.draw.since ? st.draw.since : Date.now();
    return Math.min(5, Math.max(1, 5 - Math.floor((Date.now() - since) / 1000)));
  }
  function tick() { // only the ring's number changes while counting; the page is not redrawn each time
    const r = document.getElementById("lotring");
    if (!st.draw || !r) { clearInterval(st.ticker); st.ticker = 0; return; }
    r.textContent = String(countdown());
  }

  function potCard(d) {
    const c = el("div", "lotpot");
    const top = el("div", "lothead"); top.appendChild(el("span", "lotlab", "LOTTERY POT"));
    const counting = !!st.draw, won = st.won && st.won.until > Date.now() ? st.won : null;
    top.appendChild(el("span", "lotstate" + (counting ? " on" : ""), counting ? "Counting" : d.phase === "DRAWING" ? "Drawing" : "Idle"));
    c.appendChild(top);
    if (won) {
      const w = el("div", "lotwon");
      w.appendChild(Players.face(live.players.find((x) => x.name === won.name) || { name: won.name }, 34));
      const among = won.entrants >= 0 ? won.entrants : null;
      const g = el("div"); g.appendChild(el("div", "lotwinner", "★ " + won.name)); g.appendChild(el("div", "sub", among ? "won among " + among + (among === 1 ? " player in" : " players in") : "won the draw"));
      w.appendChild(g); c.appendChild(w);
      const big = el("div", "lotbig", fmtN(won.pieces)); big.appendChild(el("small", null, "pieces won")); c.appendChild(big);
      c.appendChild(el("div", "lotsub", "just now" + (won.by ? " · drawn by " + won.by : "") + " · the pot starts again"));
      return c;
    }
    const big = el("div", "lotbig", fmtN(d.pot)); big.appendChild(el("small", null, "pieces")); c.appendChild(big);
    const row = el("div", "lotrow");
    row.appendChild(el("span", "mu", (d.entrants || 0) + (d.entrants === 1 ? " player in" : " players in") + (d.entrants ? " · 1 in " + d.entrants : "")));
    if (counting) {
      const ring = el("div", "lotring"); const n = el("span", null, String(countdown())); n.id = "lotring"; ring.appendChild(n); c.appendChild(ring);
      c.appendChild(el("div", "lotsub", (st.draw.by ? "Drawn by " + st.draw.by : "A draw is running") + (st.draw.server ? " on " + st.draw.server : "") + " · the winner is picked as the count ends"));
      if (!st.ticker) st.ticker = setInterval(tick, 250);
    } else {
      row.appendChild(drawKey(d));
    }
    c.insertBefore(row, c.children[2] || null);
    if (st.answer) c.appendChild(el("div", st.answer.ok ? "okm" : "err", st.answer.message));
    return c;
  }
  function drawKey(d) {
    const can = me.rank !== "MODERATOR";
    const why = !d.ok ? "" : !d.pot ? "the pot is empty" : !d.entrants ? "nobody is in" : "";
    const sure = Date.now() - st.armed < 3000;
    const b = el("button", "btn sm lotdraw" + (sure && !st.drawing ? " sure" : "") + (!can || why ? " off" : ""), st.drawing ? "Drawing…" : sure ? "SURE? DRAW" : "DRAW"); b.type = "button"; b.dataset.keep = "lotdraw";
    b.disabled = st.drawing; // a redraw while it runs keeps the key off (audit)
    if (!can) b.appendChild(el("span", "rk ADMIN", "Admin"));
    else if (why) b.title = "DRAW: " + why;
    b.addEventListener("click", async () => {
      if (!can) { alert("Admins and Owners draw the lottery."); return; }
      if (why) { st.answer = { ok: false, message: "Not drawn: " + why + "." }; App.refreshMore(); return; }
      if (st.drawing) return;
      if (Date.now() - st.armed > 3000) {
        st.armed = Date.now();
        App.refreshMore().then(() => { const k = document.querySelector(".lotdraw"); if (k) k.focus(); }); // Enter twice works too
        setTimeout(() => { if (shown()) App.refreshMore(); }, 3100);
        return;
      }
      st.armed = 0; st.drawing = true; b.disabled = true; b.textContent = "Drawing…";
      const r = await call("/api/lottery/draw", {}, true);
      st.drawing = false;
      st.answer = { ok: r.ok, message: r.message };
      if (r.ok && !st.draw) st.draw = { since: Date.now(), by: me.mcName, server: r.server, seen: Date.now() };
      App.refreshMore();
    });
    return b;
  }

  function view(main) {
    main.appendChild(el("h2", null, "Lottery"));
    const d = st.data;
    if (!d) { load(); main.appendChild(el("div", "note", "Reading the lottery…")); return; }
    if (!d.ok) {
      main.appendChild(el("div", "err", d.message));
      const again = el("button", "btn sm ghost", "Try again"); again.type = "button"; again.dataset.keep = "lotagain"; again.addEventListener("click", () => { st.data = null; load(); App.refreshMore(); });
      main.appendChild(again); return;
    }
    const cols = el("div", "lotcols"), left = el("div", "lotcol"), right = el("div", "lotcol");
    left.appendChild(potCard(d));
    const names = d.names || [];
    const sect = el("div", "lotsect"); sect.appendChild(el("span", null, "IN THE DRAW")); sect.appendChild(el("span", "mu", names.length + " · as BSP-Core counts them")); left.appendChild(sect);
    const q = el("input", "search lotq"); q.type = "search"; q.placeholder = "Find a name"; q.value = st.q; q.maxLength = 16; q.dataset.keep = "lotq"; left.appendChild(q);
    const chips = el("div", "lotnames");
    const online = new Set(live.players.map((p) => p.name));
    const drawNames = () => {
      chips.textContent = "";
      const w = st.q.trim().toLowerCase();
      const shownNames = names.filter((n) => !w || n.toLowerCase().includes(w));
      shownNames.slice(0, 400).forEach((n) => chips.appendChild(el("span", "lotname" + (online.has(n) ? " on" : ""), n)));
      if (shownNames.length > 400) chips.appendChild(el("span", "mu", "+" + (shownNames.length - 400) + " more: search to narrow"));
      if (!shownNames.length) chips.appendChild(el("div", "note", w ? "Nobody in by that name." : "Nobody is in the lottery."));
    };
    q.addEventListener("input", () => { st.q = q.value; drawNames(); });
    drawNames(); left.appendChild(chips);
    const ws = el("div", "lotsect"); ws.appendChild(el("span", null, "LAST WINS")); ws.appendChild(el("span", "mu", String((d.wins || []).length))); right.appendChild(ws);
    const wins = el("div", "lotwins");
    (d.wins || []).forEach((w, i) => {
      const r = el("div", "lotwin" + (i === 0 && st.won && st.won.until > Date.now() ? " new" : ""));
      r.appendChild(el("span", "nm", w.name)); r.appendChild(el("span", "mkprice", fmtN(w.pieces)));
      r.appendChild(el("span", "sub", new Date(w.at).toLocaleDateString([], { day: "numeric", month: "short" })));
      wins.appendChild(r);
    });
    if (!(d.wins || []).length) wins.appendChild(el("div", "note", "No draws yet this season."));
    right.appendChild(wins);
    cols.appendChild(left); cols.appendChild(right); main.appendChild(cols);
  }

  /** A {@code lottery} event from the panel (D411): a draw began, or ended with a winner or without one. */
  function event(e) {
    if (e.kind === "counting") {
      st.draw = { since: e.since || Date.now(), by: e.by || null, server: e.server, seen: Date.now() };
      st.won = null; st.answer = null;
    } else {
      st.draw = null;
      if (e.kind === "won") {
        st.won = { name: e.name, pieces: e.pieces, by: e.by || null, entrants: typeof e.entrants === "number" ? e.entrants : -1, until: Date.now() + 60_000 };
        GoldLine.show("Lottery drawn" + (e.by ? " by " + e.by : "") + ": " + e.name + " won " + fmtN(e.pieces) + " pieces");
        setTimeout(() => { if (shown()) App.refreshMore(); }, 60_500); // back to the new pot after a minute
      }
      st.data = null; // read again: the new pot and the wins
    }
    if (shown()) { if (st.data) App.refreshMore(); else load(); }
  }
  /** Every live update: a draw counting somewhere that this page has not heard of yet (opened mid-draw). */
  function onLive(l) {
    if (l.lottery && !st.draw) { st.draw = { since: l.lottery.since || Date.now(), server: l.lottery.server, seen: Date.now() }; if (shown()) App.refreshMore(); }
    else if (!l.lottery && st.draw && Date.now() - st.draw.seen > 10_000) { // its end was missed (a sleeping phone, a reconnect): read again (audit)
      st.draw = null; st.data = null;
      if (shown()) load();
    }
  }
  /** After the live feed reconnects: what happened meanwhile (audit). */
  function resync() { if (!live.lottery) st.draw = null; st.data = null; if (shown()) load(); }
  return { view, event, onLive, resync, reset: () => { st.answer = null; st.q = ""; if (!st.draw) st.data = null; } };
})();

// ---------------------------------------------------------------- the gold line at the top of every page (D411, D415 R1)
const GoldLine = (() => {
  function show(text) {
    let g = document.getElementById("goldline");
    if (!g) { g = el("div", "goldline"); g.id = "goldline"; document.body.appendChild(g); }
    g.textContent = "";
    g.appendChild(el("span", null, "★"));
    g.appendChild(el("span", "grow", text));
    const x = el("button", null, "✕"); x.type = "button"; x.title = "Close"; x.addEventListener("click", () => g.remove());
    g.appendChild(x);
    if (typeof placeGold === "function") placeGold();
  }
  return { show };
})();
