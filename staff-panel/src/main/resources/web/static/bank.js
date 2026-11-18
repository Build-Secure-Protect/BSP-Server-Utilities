"use strict";
// A player's Bank & Market (SU-72; D502 to D520, look A of set AC): opened by the Bank & Market key on a player's sheet.
// The money at the top (pieces, the coins kept in their bank), then LOG, LOTS and REQUESTS. The log is filtered by chips,
// 50 rows at a time; tapping a row opens its refund under it (FULL REFUND or AN AMOUNT, a conversion's coins and pieces
// apart, a payment also on the other player, a reason, SURE? within 3 s naming what goes out). Lots and requests carry
// CANCEL the same way. Admins, Owners and Moderators with the bank power act; others look (D516). Read when opened, on
// REFRESH and after each change (D518). Text from the network is set with textContent only.

const Bank = (() => {
  const PAGE = 50;
  const CHIPS = [["all", "All", null], ["pay", "Payments", ["PAID", "RECEIVED"]], ["market", "Market", ["MARKET", "TAX"]],
    ["shops", "Shops", ["BOUGHT", "SOLD", "RENT", "FEE", "LOTTERY"]], ["coins", "Coins", ["DEPOSIT", "CONVERT", "WITHDRAW"]],
    ["staff", "Staff & refunds", ["GRANT", "ADMIN", "REFUND"]]]; // D520: a newer type under All only
  const OWN_COINS = ["DEPOSIT", "WITHDRAW"]; // D515: never refunded
  const TIER_COLOURS = { copper: "#c87533", gold: "#f2c94c", diamond: "#6fe3e1", netherite: "#4a4048", illyrium: "#b58cff" };
  const st = { who: null, data: null, at: 0, loading: false, again: false, page: "log", chip: "all", shown: PAGE, open: null, form: null, cancel: null, note: null, readErr: null };
  // one refund or cancel at a time, on this page and the Market page: a form drawn again (CLOSE, another row, a chip) can
  // never send a second change while the first has not answered (audit)
  let sending = false;

  const mayAct = () => typeof me !== "undefined" && me && me.bankPower === true;
  const cap = (s) => s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
  const tierName = (i) => { const t = st.data && st.data.tiers ? st.data.tiers[i] : null; return t || "coin"; };
  const coinWord = (n, tier) => n + " " + tierName(tier) + (n === 1 ? " coin" : " coins");
  const pieceWord = (n) => fmtN(n) + (n === 1 ? " piece" : " pieces");

  // read again every time the page is opened (D518; audit: a second visit showed the first read)
  function open(p) { st.page = "log"; st.chip = "all"; st.shown = PAGE; st.data = null; st.who = { id: p.id, name: p.name }; st.open = null; st.form = null; st.cancel = null; st.note = null; st.readErr = null; } // always on LOG (D517)

  async function load(refresh) {
    if (!st.who) return;
    if (st.loading) { st.again = true; return; } // asked while a read runs (another player opened, a refund done): read once more after it (audit)
    st.loading = true; st.again = false;
    const who = st.who.id;
    const r = await call("/api/bank", refresh ? { player: who, refresh: true } : { player: who });
    st.loading = false;
    if (st.who && st.who.id === who) {
      if (r.ok || !st.data) { st.data = r; st.at = Date.now(); st.readErr = null; syncForm(); }
      else st.readErr = "Reading again failed: " + r.message + " What is shown is from the last read."; // kept apart from a refund's answer (audit)
    } else st.again = true; // another player was opened meanwhile: read theirs (audit: the page stayed on "Reading")
    if (st.again) { st.again = false; load(false); }
    redraw();
  }
  function redraw() { if (tab === "More" && moreView === "bank") App.refreshMore(); }
  /** After a read: an open refund follows its row: closed when nothing is left, amounts kept within what is left (audit). */
  function syncForm() {
    if (st.open === null || !st.data || !st.data.ok) return;
    const r = (st.data.log || []).find((x) => x.id === st.open), f = st.form;
    if (!r || !refundable(r) || !mayAct()) {
      if (f && f.answer) st.note = { ok: false, text: f.answer };
      st.open = null; st.form = null; return;
    }
    if (!f) return;
    if (f.full) { f.pieces = r.piecesLeft; f.coins = r.coinsLeft; } else { f.pieces = Math.min(f.pieces, r.piecesLeft); f.coins = Math.min(f.coins, r.coinsLeft); }
    f.armed = 0;
  }
  // "read N s ago" kept current without drawing the page again
  setInterval(() => { document.querySelectorAll(".bkage").forEach((x) => { const at = Number(x.dataset.at); if (at) x.textContent = "read " + spanOf(Date.now() - at) + " ago"; }); }, 5000);

  // ---------------------------------------------------------------- the page
  function view(main) {
    const p = st.who;
    if (!p) { main.appendChild(el("div", "note", "Open a player's sheet and press Bank & Market.")); return; }
    if (!st.data && !st.loading) load(false); // started before the header is drawn, so REFRESH says it is reading
    const d = st.data;
    const online = live.players.find((x) => x.id === p.id);
    const head = el("div", "offwho bkhead"); const g = el("div", "grow");
    g.appendChild(el("div", "dname", p.name + " · Bank & Market"));
    const sub = el("div", "sub"); sub.appendChild(el("span", null, online ? "online on " + online.server : "offline"));
    if (d && st.at) { sub.appendChild(el("span", null, " · ")); const age = el("span", "bkage", "read " + spanOf(Date.now() - st.at) + " ago"); age.dataset.at = String(st.at); sub.appendChild(age); if (d.server) sub.appendChild(el("span", null, " from " + d.server)); }
    g.appendChild(sub);
    head.appendChild(Players.face(online || p, 40)); head.appendChild(g);
    const rf = el("button", "btn sm ghost", st.loading ? "Reading…" : "REFRESH"); rf.type = "button"; rf.disabled = st.loading; rf.dataset.keep = "bkrf";
    rf.addEventListener("click", () => { st.note = null; load(true); redraw(); });
    head.appendChild(rf); main.appendChild(head);
    if (!d) { main.appendChild(el("div", "note", "Reading their bank…")); return; }
    if (!d.ok) {
      main.appendChild(el("div", "err", d.message));
      const again = el("button", "btn sm ghost", "Try again"); again.type = "button"; again.addEventListener("click", () => { st.data = null; load(false); redraw(); });
      main.appendChild(again); return;
    }
    if (!mayAct()) main.appendChild(el("div", "gold bklock", "Refunds and cancels are for Admins, Owners and Moderators given the bank power.")); // D516
    if (d.player && d.player.known === false) main.appendChild(el("div", "note", "The bank has never seen " + p.name + ": every figure is 0."));
    main.appendChild(money(d));
    if (st.note) main.appendChild(el("div", st.note.ok ? "okm" : "err", st.note.text));
    if (st.readErr) main.appendChild(el("div", "err", st.readErr));
    const keys = el("div", "bkpages");
    [["log", "LOG", (d.log || []).length], ["lots", "LOTS", (d.lots || []).length], ["orders", "REQUESTS", (d.orders || []).length]].forEach(([k, label, n]) => {
      const b = el("button", "key bkpage" + (st.page === k ? " on" : "")); b.type = "button"; b.dataset.keep = "bkpage:" + k;
      b.appendChild(el("span", null, label + " ")); b.appendChild(el("small", "mono", String(n)));
      b.addEventListener("click", () => { st.page = k; st.open = null; st.form = null; st.cancel = null; redraw(); });
      keys.appendChild(b);
    });
    main.appendChild(keys);
    if (st.page === "log") logView(main, d); else marketView(main, d, st.page === "lots");
  }

  function money(d) {
    const t = el("div", "bktiles");
    const pc = el("div", "tile"); pc.appendChild(el("span", null, "PIECES")); pc.appendChild(el("b", null, fmtN(d.pieces))); t.appendChild(pc);
    const cc = el("div", "tile"); cc.appendChild(el("span", null, "COINS IN THE BANK"));
    const row = el("div", "bkcoins"); let any = false;
    (d.coins || []).forEach((n, i) => { if (n > 0) { any = true; row.appendChild(coin(n, i, true)); } });
    if (!any) row.appendChild(el("span", "mu", "none"));
    cc.appendChild(row); t.appendChild(cc);
    return t;
  }
  function coin(n, tier, named) {
    const c = el("span", "bkcoin");
    const dot = el("i"); dot.style.background = TIER_COLOURS[tierName(tier)] || "#6b7180"; c.appendChild(dot);
    c.appendChild(el("span", null, (named ? "" : n > 0 ? "+" : "−") + fmtN(Math.abs(n)) + (named ? " " + tierName(tier) : "")));
    c.title = coinWord(Math.abs(n), tier);
    return c;
  }

  // ---------------------------------------------------------------- the log (D506, D513 to D515, D520)
  function logView(main, d) {
    if (d.logDown) { main.appendChild(el("div", "err", "Their bank log cannot be read right now (BSP-Core's database). An old copy is never shown; REFRESH asks again.")); return; }
    const chips = el("div", "bkchips");
    CHIPS.forEach(([k, label]) => {
      const c = el("button", "chip" + (st.chip === k ? " on" : ""), label); c.type = "button"; c.dataset.keep = "bkchip:" + k;
      c.addEventListener("click", () => { st.chip = k; st.shown = PAGE; st.open = null; st.form = null; redraw(); });
      chips.appendChild(c);
    });
    main.appendChild(chips);
    const types = CHIPS.find((c) => c[0] === st.chip)[2];
    const rows = (d.log || []).filter((r) => !types || types.includes(r.type));
    const list = el("div", "list bklist");
    rows.slice(0, st.shown).forEach((r) => { list.appendChild(rowEl(r)); if (st.open === r.id && st.form && mayAct() && refundable(r)) list.appendChild(form(r)); });
    if (!rows.length) list.appendChild(el("div", "note bkempty", (d.log || []).length ? "No rows of that kind in what the bank keeps." : "Nothing in their bank log (the bank keeps 90 days)."));
    if (rows.length > st.shown) {
      const more = el("button", "bkmore", "MORE · " + Math.min(st.shown, rows.length) + " of " + rows.length); more.type = "button"; more.dataset.keep = "bkmore";
      more.addEventListener("click", () => { st.shown += PAGE; redraw(); });
      list.appendChild(more);
    }
    main.appendChild(list);
  }

  function typeClass(t) {
    if (t === "PAID" || t === "RECEIVED") return "pay";
    if (t === "MARKET" || t === "TAX") return "mk";
    if (t === "REFUND" || t === "GRANT" || t === "ADMIN") return "st";
    if (t === "CONVERT" || t === "DEPOSIT" || t === "WITHDRAW") return "cv";
    return "";
  }
  function when(ms) {
    const d = new Date(ms), now = new Date();
    const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    if (d.toDateString() === now.toDateString()) return time;
    const y = new Date(now); y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return "yesterday " + time;
    return d.toLocaleDateString([], { day: "numeric", month: "short" }) + " " + time;
  }
  function refundable(r) { return !OWN_COINS.includes(r.type) && (r.piecesLeft > 0 || r.coinsLeft > 0); }
  function rowTitle(r) {
    if (r.type === "REFUND") return "by " + (r.with || "staff");
    if (r.type === "PAID") return "to " + (r.with || "?");
    if (r.type === "RECEIVED") return "from " + (r.with || "?");
    if (r.type === "CONVERT") return r.coins ? Math.abs(r.coins) + " " + tierName(r.tier) + (Math.abs(r.coins) === 1 ? " coin" : " coins") + " into pieces"
      : r.detail || "coins changed into pieces"; // a deposit box's change: pieces only (audit)
    if (OWN_COINS.includes(r.type)) return Math.abs(r.coins) + " " + tierName(r.tier) + (Math.abs(r.coins) === 1 ? " coin " : " coins ") + (r.type === "DEPOSIT" ? "banked" : "taken out");
    return r.with || r.detail || r.type.toLowerCase();
  }
  function rowEl(r) {
    const can = mayAct() && refundable(r);
    const b = el(can ? "button" : "div", "bkrow" + (st.open === r.id ? " sel" : "") + (can ? " tap" : "")); if (can) { b.type = "button"; b.dataset.keep = "bkrow:" + r.id; }
    b.appendChild(el("span", "bkty " + typeClass(r.type), r.type));
    const w = el("div", "bkwhat");
    w.appendChild(el("div", "nm", rowTitle(r)));
    const bits = [];
    if (r.type === "REFUND") bits.push("of row " + r.refundOf);
    else if (r.detail && r.detail !== rowTitle(r) && r.with) bits.push(r.detail);
    else if (r.detail && !r.with && !["CONVERT"].includes(r.type) && !OWN_COINS.includes(r.type) && r.detail !== rowTitle(r)) bits.push(r.detail);
    bits.push(when(r.ms));
    if (r.server) bits.push(r.server);
    if (OWN_COINS.includes(r.type)) bits.push("their own coins · not refunded");
    w.appendChild(el("div", "sub wrap", bits.join(" · ")));
    const done = r.refundedPieces + r.refundedCoins;
    const share = r.pieces ? r.refundedPieces / Math.abs(r.pieces) : r.coins ? r.refundedCoins / Math.abs(r.coins) : 0; // pieces and coins are not added together (audit)
    if (done > 0 && r.type !== "REFUND") { // D514: a row refunded in part, or in full
      const parts = [];
      if (r.refundedPieces > 0) parts.push(pieceWord(r.refundedPieces));
      if (r.refundedCoins > 0) parts.push(r.refundedCoins + (r.refundedCoins === 1 ? " coin" : " coins"));
      w.appendChild(el("div", "sub bkref", parts.join(", ") + " refunded" + (refundable(r) ? "" : " · nothing left")));
      const bar = el("div", "bkbar"); const f = el("i"); f.style.width = Math.min(100, Math.round(100 * share)) + "%"; bar.appendChild(f); w.appendChild(bar);
    }
    b.appendChild(w);
    const a = el("div", "bkamt");
    if (r.pieces) a.appendChild(el("div", r.pieces > 0 ? "tl" : "rd", (r.pieces > 0 ? "+" : "−") + fmtN(Math.abs(r.pieces))));
    if (r.coins) a.appendChild(coin(r.coins, r.tier, false)).classList.add(r.coins > 0 ? "tl" : "rd");
    b.appendChild(a);
    if (can) b.addEventListener("click", () => {
      if (st.open === r.id) { st.open = null; st.form = null; } else { st.open = r.id; st.form = fresh(r); }
      st.note = null; redraw();
    });
    return b;
  }

  // ---------------------------------------------------------------- a refund (D508, D509, D510, D519)
  function fresh(r) { return { full: true, pieces: r.piecesLeft, coins: r.coinsLeft, other: r.payment && !!r.with, reason: "", armed: 0, busy: false, answer: null, without: false }; } // the other player only when the row names them (audit)

  /** The SURE? words, from the numbers in the boxes at this moment (D509). */
  function sure(r, f) {
    const name = st.who.name, out = [];
    if (f.coins > 0) out.push(r.coins < 0 ? "give " + name + " " + coinWord(f.coins, r.tier) + " back into their bank" : "take back " + coinWord(f.coins, r.tier) + " from " + name);
    if (f.pieces > 0) out.push(r.pieces < 0 ? "give " + name + " " + pieceWord(f.pieces) + " back" : "take back " + pieceWord(f.pieces) + " from " + name);
    if (r.payment && r.with && f.pieces > 0) {
      if (f.other) out.push(r.pieces < 0 ? "take " + pieceWord(f.pieces) + " from " + r.with : "give " + pieceWord(f.pieces) + " back to " + r.with);
      else out.push(r.pieces < 0 ? r.with + " keeps theirs" : r.with + " gets nothing back");
    }
    const s = out.join(", ");
    return "Sure? " + s.charAt(0).toUpperCase() + s.slice(1) + " (row " + r.id + ").";
  }

  function form(r) {
    const f = st.form;
    const box = el("div", "bkform");
    const modes = el("div", "bkmodes");
    [[true, "FULL REFUND"], [false, "AN AMOUNT"]].forEach(([full, label]) => {
      const k = el("button", "key" + (f.full === full ? " on" : ""), label); k.type = "button"; k.dataset.keep = "bkmode:" + label;
      k.addEventListener("click", () => { f.full = full; if (full) { f.pieces = r.piecesLeft; f.coins = r.coinsLeft; } f.armed = 0; f.answer = null; redraw(); });
      modes.appendChild(k);
    });
    box.appendChild(modes);
    const field = (label, key, left, hint) => {
      const l = el("label", "bkfld"); l.appendChild(el("span", null, label));
      const row = el("div", "bkin");
      const i = el("input", "bknum"); i.type = "number"; i.inputMode = "numeric"; i.min = "0"; i.max = String(left); i.step = "1"; i.value = String(f[key]); i.disabled = f.full || f.busy; i.dataset.keep = "bkin:" + key;
      i.addEventListener("input", () => { const v = Math.floor(Number(i.value)); f[key] = Number.isFinite(v) ? Math.max(0, Math.min(left, v)) : 0; f.armed = 0; f.answer = null; syncSure(); });
      i.addEventListener("change", () => { i.value = String(f[key]); });
      row.appendChild(i); row.appendChild(el("span", "mu", hint)); l.appendChild(row); box.appendChild(l);
    };
    const conv = r.type === "CONVERT" && r.coins !== 0;
    if (r.coinsLeft > 0) field(conv ? "Give the coins back" : r.coins < 0 ? "Coins to give back" : "Coins to take back", "coins", r.coinsLeft, "of " + r.coinsLeft + " " + tierName(r.tier) + " left");
    if (r.piecesLeft > 0) field(conv ? "Take back the pieces it made" : r.pieces < 0 ? "Pieces to give back" : "Pieces to take back", "pieces", r.piecesLeft, "of " + fmtN(r.piecesLeft) + " left");
    if (r.payment && r.with) {
      const sw = el("label", "tick bksw"); const c = el("input"); c.type = "checkbox"; c.checked = f.other; c.disabled = f.busy; c.dataset.keep = "bkother";
      c.addEventListener("change", () => { f.other = c.checked; f.armed = 0; f.answer = null; syncSure(); });
      sw.appendChild(c); sw.appendChild(el("span", null, (r.pieces < 0 ? "Also take it back from " : "Also give it back to ") + r.with)); box.appendChild(sw);
    }
    const why = el("input", "reason bkwhy"); why.type = "text"; why.maxLength = 160; why.placeholder = "Why (staff only: the Action Log)"; why.value = f.reason; why.disabled = f.busy; why.dataset.keep = "bkwhy";
    why.addEventListener("input", () => { f.reason = why.value; f.armed = 0; syncSure(); });
    box.appendChild(why);
    const line = el("div", "bksure"); box.appendChild(line);
    if (f.answer) box.appendChild(el("div", "err", f.answer));
    const keys = el("div", "bkmodes");
    const go = el("button", "key"); go.type = "button"; go.dataset.keep = "bkgo";
    if (f.without) { // D508: refused for the other player; offered without them
      const w = el("button", "key bkgoal", "REFUND WITHOUT " + r.with.toUpperCase()); w.type = "button";
      w.addEventListener("click", () => { f.other = false; f.without = false; f.answer = null; f.armed = Date.now(); redraw(); });
      keys.appendChild(w);
    } else keys.appendChild(go);
    const close = el("button", "key", "CLOSE"); close.type = "button";
    close.addEventListener("click", () => { st.open = null; st.form = null; redraw(); });
    keys.appendChild(close); box.appendChild(keys);

    function ready() { return (f.pieces > 0 || f.coins > 0) && f.reason.trim().length > 0; }
    function syncSure() {
      const left = sending && !f.busy ? 0 : 3000 - (Date.now() - f.armed);
      line.textContent = ready() ? sure(r, f) : f.reason.trim() ? "Pick an amount." : "Say why first: it goes in the Action Log.";
      line.className = "bksure" + (ready() && left > 0 ? " armed" : "");
      go.disabled = f.busy || sending || !ready();
      go.textContent = f.busy ? "Refunding…" : sending ? "Waiting for another answer…" : left > 0 && ready() ? "SURE? REFUND" : "REFUND";
      go.className = "key" + (left > 0 && ready() && !f.busy ? " bkarmed" : " bkgoal");
      if (left > 0) setTimeout(() => { if (go.isConnected && Date.now() - f.armed >= 3000) syncSure(); }, left + 30);
    }
    go.addEventListener("click", async () => {
      if (f.busy || sending || !ready()) return;
      if (Date.now() - f.armed > 3000) { f.armed = Date.now(); syncSure(); return; } // two taps within 3 s (D509)
      f.armed = 0; f.busy = true; sending = true; syncSure();
      const who = st.who.id;
      const r2 = await call("/api/bank/refund", { player: who, row: r.id, type: r.type, with: r.with || "", tier: r.tier, pieces: f.pieces, coins: f.coins, other: !!(r.payment && r.with && f.other), reason: f.reason.trim(), server: st.data.server || "" }, true);
      f.busy = false; sending = false;
      if (!st.who || st.who.id !== who) return; // another player is on screen now: their page is not told (audit)
      if (r2.ok) { st.open = null; st.form = null; st.note = { ok: true, text: r2.message }; }
      else if (st.form !== f) st.note = { ok: false, text: r2.message }; // its form was closed meanwhile: the answer at the top (audit)
      else { f.answer = r2.message; f.without = !!r2.withoutOther; }
      redraw(); // the answer at once (audit: the key said "Refunding…" until the re-read came back)
      load(false); // D518: read again after any answer, refusals and no-answers too, so what is left on the row is current
    });
    syncSure();
    return box;
  }

  // ---------------------------------------------------------------- lots and requests (D507, D509, D510)
  function marketView(main, d, lots) {
    const list = el("div", "list bklist"), now = Date.now();
    const items = lots ? d.lots || [] : d.orders || [];
    items.forEach((x) => {
      const row = el("div", "bklot");
      const slots = el("div", "mks");
      (lots ? x.items || [] : [x.item]).forEach((it) => slots.appendChild(stackSlot(it, false, null)));
      row.appendChild(slots);
      const g = el("div", "grow");
      const what = lots ? (x.items || []).map((it) => Items.name(it) + (it.n > 1 ? " × " + it.n : "")).join(", ") : (x.wanted > 1 ? x.wanted + " " : "") + Items.name(x.item);
      g.appendChild(el("div", "nm", what || "?"));
      const togo = lots ? 0 : Math.max(0, x.wanted - x.filled);
      g.appendChild(el("div", "sub", lots ? fmtN(x.price) + " pieces · listed on " + x.server + " · " + (x.ends > now ? "ends in " + spanOf(x.ends - now) : "ended, closing soon")
        : x.filled + " of " + x.wanted + " · " + fmtN(x.each) + " each · " + fmtN(togo * x.each) + " held · " + (x.ends > now ? "ends in " + spanOf(x.ends - now) : "ended, closing soon")));
      row.appendChild(g);
      if (mayAct() && (!st.cancel || st.cancel.id !== x.id)) {
        const c = el("button", "key danger bkcancel", "CANCEL"); c.type = "button"; c.dataset.keep = "bkcancel:" + x.id;
        c.addEventListener("click", () => { st.cancel = { id: x.id, lot: lots, what, reason: "", armed: 0, busy: false, answer: null, held: lots ? 0 : togo * x.each }; st.note = null; redraw(); });
        row.appendChild(c);
      }
      list.appendChild(row);
      const who = st.who.id, mine = (fn) => (...a) => { if (st.who && st.who.id === who) fn(...a); }; // an answer for another player's page is dropped (audit)
      if (st.cancel && st.cancel.id === x.id) list.appendChild(cancelBox(st.cancel, { owner: st.who.name, server: d.server || "",
        back: () => { st.cancel = null; redraw(); }, refused: mine((msg) => { if (!st.cancel) st.note = { ok: false, text: msg }; redraw(); load(false); }),
        done: mine((msg) => { st.cancel = null; st.note = { ok: true, text: msg }; redraw(); load(false); }) }));
    });
    if (!items.length) list.appendChild(el("div", "note bkempty", lots ? st.who.name + " has no lots on the Market Board." : st.who.name + " has no requests on the Wanted Board."));
    if (d.cut) list.appendChild(el("div", "note bkempty", "Too many to show at once: only the newest are here."));
    main.appendChild(list);
    if (!items.length) return;
    main.appendChild(el("div", "note", "Items show as their picture, count and name only."));
  }

  /**
   * A cancel's reason, SURE? and answer (D507, D509, D510), for this page and the Market page. {@code c}: {id, lot, what,
   * reason, armed, busy, answer, held}; {@code o}: {owner (the seller's or buyer's name), server, back, done(message),
   * refused() (after a refusal: redraw and read again)}.
   */
  function cancelBox(c, o) {
    const lots = c.lot, box = el("div", "bkform");
    const why = el("input", "reason bkwhy"); why.type = "text"; why.maxLength = 160; why.placeholder = "Why (staff only: the Action Log)"; why.value = c.reason; why.disabled = c.busy; why.dataset.keep = "bkcwhy";
    box.appendChild(why);
    const line = el("div", "bksure"); box.appendChild(line);
    if (c.answer) box.appendChild(el("div", "err", c.answer));
    const keys = el("div", "bkmodes");
    const go = el("button", "key"); go.type = "button"; go.dataset.keep = "bkcgo";
    const back = el("button", "key", "BACK"); back.type = "button"; back.addEventListener("click", () => o.back());
    keys.appendChild(go); keys.appendChild(back); box.appendChild(keys);
    const label = lots ? "CANCEL THE LOT" : "CANCEL THE REQUEST";
    function sync() {
      const left = sending && !c.busy ? 0 : 3000 - (Date.now() - c.armed), ok = c.reason.trim().length > 0;
      line.textContent = !ok ? "Say why first: it goes in the Action Log." : lots
        ? "Sure? Take this lot off the Market Board. " + c.what + " go to " + o.owner + "'s Collect box. The listing tax stays paid."
        : "Sure? Take the " + c.what + " request off the Wanted Board. What it still holds" + (c.held > 0 ? " (" + pieceWord(c.held) + ")" : "") + " goes back to " + o.owner + "'s bank.";
      line.className = "bksure" + (ok && left > 0 ? " armed" : "");
      go.disabled = c.busy || sending || !ok;
      go.textContent = c.busy ? "Cancelling…" : sending ? "Waiting for another answer…" : ok && left > 0 ? "SURE? " + label : label;
      go.className = "key" + (ok && left > 0 && !c.busy ? " bkarmed" : " danger");
      if (left > 0) setTimeout(() => { if (go.isConnected && Date.now() - c.armed >= 3000) sync(); }, left + 30);
    }
    why.addEventListener("input", () => { c.reason = why.value; c.armed = 0; sync(); });
    go.addEventListener("click", async () => {
      if (c.busy || sending || !c.reason.trim()) return;
      if (Date.now() - c.armed > 3000) { c.armed = Date.now(); sync(); return; }
      c.armed = 0; c.busy = true; sending = true; sync();
      const r = await call("/api/bank/cancel", Object.assign(lots ? { lot: c.id } : { order: c.id }, { name: o.owner, what: c.what.slice(0, 120), reason: c.reason.trim(), server: o.server || "" }), true);
      c.busy = false; sending = false;
      if (r.ok) { o.done(r.message); return; }
      c.answer = r.message; o.refused(r.message); // read again after a refusal or no answer too (audit)
    });
    sync();
    return box;
  }

  /** The Bank & Market key for a player's sheet or Find a player page (D517). */
  function key(p) {
    const b = el("button", "key", "Bank & Market"); b.type = "button"; b.dataset.keep = "k:bank";
    b.addEventListener("click", () => openBank(p));
    return b;
  }

  return { view, open, key, cancelBox, mayAct, who: () => st.who, reset: () => { st.open = null; st.form = null; st.cancel = null; st.note = null; } };
})();
