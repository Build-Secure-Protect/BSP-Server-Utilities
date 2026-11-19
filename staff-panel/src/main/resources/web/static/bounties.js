"use strict";
// More › Bounties (SU-77; D524 to D542, look B of set AD): the Bounty Board as wanted posters (two a row on a phone, four on
// a computer), each opening its own screen with every detail and, for Admins, Owners and the bounty power, BLANK NOTES,
// +1 HOUR, +1 DAY, +7 DAYS, DAYS… and the two REMOVE keys behind one "Why" box; HISTORY with coloured ending chips; + ADD,
// a bounty paid by the server. Read on opening, on REFRESH, after every change and every 30 s while nothing is being done
// (D534). A player's sheet gets a line opening the board filtered to them (D528). Network text is set with textContent only.

const Bounties = (() => {
  const H = 3600000, D = 24 * H;
  const CHIPS = [["all", "All"], ["soon", "Ending within a day"], ["staff", "Staff bounties"], ["bonus", "With a bonus"]];
  const HCHIPS = [["all", "All", null], ["claimed", "Claimed", ["CLAIMED"]], ["expired", "Expired", ["EXPIRED"]], ["cancelled", "Cancelled", ["CANCELLED"]],
    ["removed", "Removed", ["REMOVED", "REMOVED_NO_REFUND"]]]; // D533: TARGET_STAFF and newer endings under All
  const ENDING_CLASS = { CLAIMED: "claimed", EXPIRED: "grey", CANCELLED: "grey", REMOVED: "removed", REMOVED_NO_REFUND: "removed", TARGET_STAFF: "staff" };
  const TIER_COLOURS = { copper: "#c87533", gold: "#f2c94c", diamond: "#6fe3e1", netherite: "#4a4048", illyrium: "#b58cff" };
  const st = { data: null, at: 0, loading: false, again: false, hist: null, histLoading: false, page: "open", chip: "all", q: "", who: null, sel: null, act: null,
    hchip: "all", hopen: null, add: null, note: null };
  let sending = false;

  const mayAct = () => typeof me !== "undefined" && me && me.bountyPower === true;
  const tierName = (i) => { const t = st.data && st.data.tiers ? st.data.tiers[i] : null; return t || "coin"; };
  const price = (b, n) => b.tier < 0 ? fmtN(n) + (n === 1 ? " piece" : " pieces") : n + " " + tierName(b.tier) + (n === 1 ? " coin" : " coins");
  function left(ms) {
    const m = Math.max(0, Math.round(ms / 60000));
    if (m < 1) return "under a minute";
    if (m < 60) return m + " min";
    const h = Math.floor(m / 60);
    if (h < 24) return h + " h" + (m % 60 ? " " + (m % 60) + " min" : "");
    return h % 24 ? Math.floor(h / 24) + " d " + (h % 24) + " h" : h / 24 + (h === 24 ? " day" : " days");
  }
  function coinTag(tier) { const c = el("span", "bkcoin"); const d = el("i"); d.style.background = TIER_COLOURS[tierName(tier)] || "#6b7180"; c.appendChild(d); c.appendChild(el("span", null, tierName(tier))); return c; }
  function reward(b, cls) { // D542: coins as the count with the tier's dot and name
    const r = el("span", cls || "byrw", b.tier < 0 ? fmtN(b.amount) : String(b.amount));
    if (b.tier >= 0) { r.appendChild(document.createTextNode(" ")); r.appendChild(coinTag(b.tier)); }
    return r;
  }
  function bonusText(b, now) { // D537: counting down, then over
    if (!b.bonus) return "no bonus";
    const sign = "+" + (b.tier < 0 ? fmtN(b.bonus) : b.bonus + " " + tierName(b.tier) + (b.bonus === 1 ? " coin" : " coins"));
    return b.bonusEnds > now ? sign + " if killed within " + left(b.bonusEnds - now) : sign + " · bonus window over";
  }

  // ---------------------------------------------------------------- reading
  async function load() {
    if (st.loading) { st.again = true; return; }
    st.loading = true; st.again = false;
    const r = await call("/api/bounties", {});
    st.loading = false;
    if (r.ok || !st.data) { st.data = r; st.at = Date.now(); if (r.ok) st.readErr = null; } else st.readErr = "Reading again failed: " + r.message + " What is shown is from the last read.";
    if (st.sel && r.ok && !(r.open || []).some((b) => b.id === st.sel)) { st.sel = null; st.act = null; if (!st.note) st.note = { ok: false, text: "That bounty has ended." }; }
    if (st.again) { st.again = false; load(); }
    redraw(); // the sheet's line too
  }
  async function loadHistory() {
    if (st.histLoading) return;
    st.histLoading = true;
    const r = await call("/api/bounties/history", {});
    st.histLoading = false;
    if (r.ok || !st.hist || !st.hist.ok) st.hist = r; else st.readErr = "Reading the history again failed: " + r.message; // the last copy stays (audit)
    redraw();
  }
  function redraw() {
    if (tab === "More" && (moreView === "bounties" || moreView === "find")) { App.refreshMore(); return; } // Find a player shows the line too (audit)
    if (tab === "Players") { const a = document.activeElement; whenFree(a && a.closest ? a.closest("#pdetail, .offsheet, .mcard") : null, () => App.refresh()); }
  }
  // D534: every 30 s while the page is open and nothing is being done
  setInterval(() => {
    if (tab !== "More" || moreView !== "bounties" || sending || document.hidden) return;
    if (st.page === "open" && !(st.act && st.act.reason)) load();
    else if (st.page === "history") loadHistory();
  }, 30000);

  // ---------------------------------------------------------------- the page
  function view(main) {
    if (!st.data && !st.loading) load();
    const d = st.data;
    const head = el("div", "offwho bkhead"); const g = el("div", "grow");
    g.appendChild(el("div", "dname", "Bounties"));
    const sub = el("div", "sub");
    sub.appendChild(el("span", null, d && d.ok ? (d.open || []).length + " open" : "the Bounty Board"));
    if (d && st.at) { sub.appendChild(el("span", null, " · ")); const age = el("span", "bkage", "read " + spanOf(Date.now() - st.at) + " ago"); age.dataset.at = String(st.at); sub.appendChild(age); if (d.server) sub.appendChild(el("span", null, " from " + d.server)); }
    g.appendChild(sub); head.appendChild(g);
    const rf = el("button", "btn sm ghost", st.loading ? "Reading…" : "REFRESH"); rf.type = "button"; rf.disabled = st.loading; rf.dataset.keep = "byrf";
    rf.addEventListener("click", () => { st.note = null; load(); if (st.page === "history") loadHistory(); redraw(); });
    head.appendChild(rf); main.appendChild(head);
    if (!d) { main.appendChild(el("div", "note", "Reading the board…")); return; }
    if (!d.ok) {
      main.appendChild(el("div", "err", d.message));
      const again = el("button", "btn sm ghost", "Try again"); again.type = "button"; again.addEventListener("click", () => { st.data = null; load(); redraw(); });
      main.appendChild(again); return;
    }
    if (st.note) main.appendChild(el("div", st.note.ok ? "okm" : "err", st.note.text));
    if (st.readErr) main.appendChild(el("div", "err", st.readErr));
    if (st.sel) { const b = (d.open || []).find((x) => x.id === st.sel); if (b) { bountyView(main, b); return; } st.sel = null; }
    const keys = el("div", "bkpages");
    [["open", "OPEN", String((d.open || []).length)], ["history", "HISTORY", ""], ["add", "+ ADD", ""]].forEach(([k, label, n]) => {
      if (k === "add" && !mayAct()) return;
      const b = el("button", "key bkpage" + (st.page === k ? " on" : "")); b.type = "button"; b.dataset.keep = "bypage:" + k;
      b.appendChild(el("span", null, label + (n ? " " : ""))); if (n) b.appendChild(el("small", "mono", n));
      b.addEventListener("click", () => { st.page = k; st.note = null; if (k === "history" && !st.hist) loadHistory(); if (k === "add" && !st.add) st.add = freshAdd(); redraw(); });
      keys.appendChild(b);
    });
    main.appendChild(keys);
    if (st.page === "history") historyView(main);
    else if (st.page === "add" && mayAct()) addView(main);
    else boardView(main, d);
  }

  // ---------------------------------------------------------------- the board (D535, D536, D539)
  function matches(b) {
    const now = Date.now();
    if (st.chip === "soon" && b.ends - now > D) return false;
    if (st.chip === "staff" && !b.byServer) return false;
    if (st.chip === "bonus" && !(b.bonus && b.bonusEnds > now)) return false;
    if (st.who && b.target !== st.who.id && b.placer !== st.who.id && !(b.hunters || []).some((h) => h.id === st.who.id)) return false;
    const w = st.q.trim().toLowerCase();
    if (!w) return true;
    return [b.targetName, b.placerName].concat((b.hunters || []).map((h) => h.name)).some((n) => (n || "").toLowerCase().includes(w));
  }
  function boardView(main, d) {
    const chips = el("div", "bkchips"); chips.dataset.scroll = "bychips";
    CHIPS.forEach(([k, label]) => {
      const c = el("button", "chip" + (st.chip === k ? " on" : ""), label); c.type = "button"; c.dataset.keep = "bychip:" + k;
      c.addEventListener("click", () => { st.chip = k; redraw(); });
      chips.appendChild(c);
    });
    main.appendChild(chips);
    if (st.who) {
      const c = el("button", "chip mkchip"); c.type = "button";
      c.appendChild(el("span", null, st.who.name + " · target, placer or hunter")); c.appendChild(el("b", "tl", "✕"));
      c.addEventListener("click", () => { st.who = null; redraw(); });
      main.appendChild(c);
    }
    const q = el("input", "search byq"); q.type = "search"; q.placeholder = "Target, placer or hunter"; q.value = st.q; q.maxLength = 17; q.dataset.keep = "byq";
    main.appendChild(q);
    const grid = el("div", "bygrid");
    const draw = () => {
      grid.textContent = "";
      const now = Date.now(), list = (d.open || []).filter(matches);
      list.forEach((b) => grid.appendChild(poster(b, now)));
      if (!list.length) grid.appendChild(el("div", "note", (d.open || []).length ? "No bounty matches." : "The Bounty Board is empty."));
    };
    q.addEventListener("input", () => { st.q = q.value; draw(); });
    draw();
    main.appendChild(grid);
  }
  function poster(b, now) {
    const p = el("button", "byposter"); p.type = "button"; p.dataset.keep = "bypost:" + b.id;
    p.appendChild(el("span", "bywanted", "WANTED"));
    p.appendChild(Players.face({ id: b.target, name: b.targetName }, 56));
    p.appendChild(el("b", "nm", b.targetName));
    p.appendChild(reward(b));
    p.appendChild(el("span", "sub", bonusText(b, now)));
    const hn = (b.hunters || []).length;
    p.appendChild(el("span", "sub", (b.byServer ? "by the Server · " : "") + (hn ? hn + (hn === 1 ? " hunter" : " hunters") : "no hunters") + " · " + (b.ends > now ? left(b.ends - now) : "ending")));
    p.addEventListener("click", () => { st.sel = b.id; st.act = freshAct(); st.note = null; redraw(); });
    return p;
  }

  // ---------------------------------------------------------------- a bounty's own screen (D530, D532, D540, D541)
  function freshAct() { return { reason: "", armed: null, at: 0, days: "", busy: false, answer: null, ok: false }; }
  function bountyView(main, b) {
    const now = Date.now(), a = st.act || (st.act = freshAct());
    const back = el("button", "btn sm ghost back", "‹ The board"); back.type = "button"; back.addEventListener("click", () => { st.sel = null; st.act = null; redraw(); });
    main.appendChild(back);
    if (!mayAct()) main.appendChild(el("div", "gold bklock", "Adding, removing and extending bounties is for Admins, Owners and Moderators given the bounty power."));
    const top = el("div", "byposter byhead");
    top.appendChild(Players.face({ id: b.target, name: b.targetName }, 56));
    const tg = el("div", "grow"); tg.appendChild(el("span", "bywanted", "WANTED")); tg.appendChild(el("div", "nm", b.targetName));
    const rw = el("div"); rw.appendChild(reward(b)); if (b.tier < 0) rw.appendChild(el("span", "byrwu", " pieces")); tg.appendChild(rw);
    tg.appendChild(el("div", "sub", bonusText(b, now))); top.appendChild(tg); main.appendChild(top);
    const kv = el("div", "kvl");
    const ends = new Date(b.ends);
    [["Ends", b.ends > now ? "in " + left(b.ends - now) + " (" + ends.toLocaleString([], { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) + ")" : "ending now"],
      ["Placed by", (b.byServer ? "the Server (staff)" : b.placerName) + " · " + spanOf(now - b.placed) + " ago" + (b.server ? " on " + b.server : "")],
      ["Fee", b.fee ? fmtN(b.fee) + " pieces (to the lottery pot)" : "none"], ["Bounty", b.id]].forEach(([k, v]) => { kv.appendChild(el("span", null, k)); kv.appendChild(el("span", null, v)); });
    main.appendChild(kv);
    const hs = b.hunters || [];
    main.appendChild(el("div", "sect", "HUNTERS · " + hs.length));
    if (!hs.length) main.appendChild(el("div", "note", "Nobody has accepted it yet."));
    hs.forEach((h) => { const r = el("div", "byhunt"); r.appendChild(Players.face({ id: h.id, name: h.name }, 18)); r.appendChild(el("span", null, h.name)); r.appendChild(el("span", "mu", " · accepted " + spanOf(now - h.accepted) + " ago")); main.appendChild(r); });
    main.appendChild(el("div", "sect", "NOTES · HUNTERS AND STAFF ONLY"));
    main.appendChild(el("div", "bynotes" + (b.notes ? "" : " mu"), b.notes || "No notes."));
    if (!mayAct()) return;
    const why = el("input", "reason bkwhy"); why.type = "text"; why.maxLength = 160; why.placeholder = "Why (staff only: the Action Log)"; why.value = a.reason; why.disabled = a.busy; why.dataset.keep = "bywhy";
    main.appendChild(why);
    const quick = el("div", "bkmodes"), big = el("div", "bkmodes"), line = el("div", "bksure"), days = el("div", "bkmodes");
    const keys = [];
    const mk = (label, cls, act, host) => { const k = el("button", "key " + (cls || ""), label); k.type = "button"; k.dataset.keep = "byk:" + label; k.addEventListener("click", act); host.appendChild(k); keys.push([k, label]); return k; };
    mk("BLANK NOTES", "", () => send("blank", {}, "BLANK NOTES"), quick);
    [["+1 HOUR", H], ["+1 DAY", D], ["+7 DAYS", 7 * D]].forEach(([l, ms]) => mk(l, "", () => send("extend", { addMs: ms }, l), quick));
    mk("DAYS…", "", () => { a.armed = a.armed === "days" ? null : "days"; a.at = 0; redraw(); }, quick);
    if (a.armed === "days" || a.armed === "daysgo") {
      const n = el("input", "bknum"); n.type = "number"; n.inputMode = "numeric"; n.min = "1"; n.max = "30"; n.step = "1"; n.placeholder = "days"; n.value = a.days; n.dataset.keep = "bydays";
      n.addEventListener("input", () => { a.days = n.value; a.at = 0; a.armed = "days"; sync(); });
      days.appendChild(n); days.appendChild(el("span", "mu", "days (stops at 30 days from now)"));
      mk("EXTEND", "", () => arm("daysgo", () => send("extend", { addMs: Math.floor(Number(a.days)) * D }, "EXTEND")), days);
    }
    const removeRefund = mk("REMOVE + REFUND", "danger", () => arm("refund", () => send("remove", { refund: true }, "REMOVE + REFUND")), big);
    const removeNone = mk("REMOVE, NO REFUND", "danger", () => arm("none", () => send("remove", { refund: false }, "REMOVE, NO REFUND")), big);
    main.appendChild(quick); if (days.childNodes.length) main.appendChild(days); main.appendChild(big); main.appendChild(line);
    if (a.answer) main.appendChild(el("div", a.ok ? "okm" : "err", a.answer));
    function arm(kind, go) {
      if (!a.reason.trim() || a.busy || sending) return;
      if (a.armed === kind && Date.now() - a.at <= 3000) { a.armed = null; a.at = 0; go(); return; } // two taps within 3 s (D530, D532)
      a.armed = kind; a.at = Date.now(); sync();
    }
    function sync() {
      const ok = a.reason.trim().length > 0, live = Date.now() - a.at <= 3000;
      keys.forEach(([k, label]) => { k.disabled = !ok || a.busy || sending; });
      const nDays = Math.floor(Number(a.days));
      const ext = keys.find((x) => x[1] === "EXTEND");
      if (ext) { ext[0].disabled = ext[0].disabled || !(nDays >= 1 && nDays <= 30); ext[0].textContent = a.armed === "daysgo" && live ? "SURE? EXTEND BY " + nDays + (nDays === 1 ? " DAY" : " DAYS") : "EXTEND"; ext[0].className = "key" + (a.armed === "daysgo" && live ? " bkarmed" : ""); }
      removeRefund.textContent = a.armed === "refund" && live ? "SURE? REMOVE + REFUND" : "REMOVE + REFUND"; removeRefund.className = "key " + (a.armed === "refund" && live ? "bkarmed" : "danger");
      removeNone.textContent = a.armed === "none" && live ? "SURE? NO REFUND" : "REMOVE, NO REFUND"; removeNone.className = "key " + (a.armed === "none" && live ? "bkarmed" : "danger");
      line.className = "bksure" + (live && a.armed && a.armed !== "days" ? " armed" : "");
      line.textContent = !ok ? "Say why first: it goes in the Action Log. The keys wait for it." : a.busy ? "Waiting for the answer…" : sending ? "Waiting for another answer…"
        : live && a.armed === "refund" ? sureRemove(b, true) : live && a.armed === "none" ? sureRemove(b, false)
          : live && a.armed === "daysgo" ? "Sure? Add " + nDays + (nDays === 1 ? " day" : " days") + " to this bounty (it stops at 30 days from now). " + told(b, false)
            : "+1 HOUR, +1 DAY, +7 DAYS and BLANK NOTES act on one tap; REMOVE asks SURE? first.";
      if (live) setTimeout(() => { if (line.isConnected && Date.now() - a.at > 3000) sync(); }, 3000 - (Date.now() - a.at) + 30);
    }
    why.addEventListener("input", () => { a.reason = why.value; a.armed = a.armed === "days" || a.armed === "daysgo" ? "days" : null; a.at = 0; sync(); });
    async function send(kind, extra, label) {
      if (!a.reason.trim() || a.busy || sending) return;
      a.busy = true; sending = true; a.answer = null; sync();
      const r = await call("/api/bounties/" + kind, Object.assign({ bounty: b.id, targetName: b.targetName, reason: a.reason.trim(), server: st.data.server || "" }, extra), true);
      a.busy = false; sending = false;
      if (r.ok) st.hist = null; // HISTORY is read again after a change (audit)
      if (st.act !== a) { st.note = { ok: !!r.ok, text: b.targetName + ": " + r.message }; redraw(); load(); return; } // another bounty is on screen: the answer at the top (audit)
      if (r.ok && kind === "remove") { st.sel = null; st.act = null; st.note = { ok: true, text: r.message }; redraw(); load(); return; } // D541: back to the board
      a.ok = !!r.ok; a.answer = r.message; a.armed = null; a.at = 0;
      redraw(); load(); // D541: stay, read again, the answer under the keys
    }
    sync();
  }
  /** "Kestrel_88, the 2 hunters and Vexa are told." with only who is there (audit). */
  function told(b, target) {
    const who = [];
    if (!b.byServer) who.push(b.placerName);
    const n = (b.hunters || []).length; if (n) who.push("the " + n + (n === 1 ? " hunter" : " hunters"));
    if (target) who.push(b.targetName);
    if (!who.length) return "Nobody else is told.";
    const list = who.length === 1 ? who[0] : who.slice(0, -1).join(", ") + " and " + who[who.length - 1];
    return list + (who.length === 1 && !/^the \d+ hunters$/.test(who[0]) ? " is told." : " are told.");
  }
  function sureRemove(b, refund) {
    const tell = told(b, true);
    if (!refund) return "Sure? Take this bounty off the board. Nothing goes back" + (b.byServer ? "" : " to " + b.placerName) + ". " + tell;
    if (b.byServer) return "Sure? Take this bounty off the board. It was placed by the server, so nothing goes back to anyone. " + tell;
    return "Sure? Take this bounty off the board and give " + b.placerName + " " + price(b, b.amount) + (b.bonus ? " and the " + price(b, b.bonus) + " bonus" : "") + " back"
      + (b.tier < 0 ? " to their bank." : " in their Collect box.") + (b.fee ? " The " + fmtN(b.fee) + " fee stays in the pot. " : " ") + tell;
  }

  // ---------------------------------------------------------------- history (D533, D538)
  function historyView(main) {
    const h = st.hist;
    if (!h) { if (!st.histLoading) loadHistory(); main.appendChild(el("div", "note", "Reading the history…")); return; }
    if (!h.ok) { main.appendChild(el("div", "err", h.message)); return; }
    const chips = el("div", "bkchips"); chips.dataset.scroll = "byhchips";
    HCHIPS.forEach(([k, label]) => { const c = el("button", "chip" + (st.hchip === k ? " on" : ""), label); c.type = "button"; c.dataset.keep = "byh:" + k; c.addEventListener("click", () => { st.hchip = k; redraw(); }); chips.appendChild(c); });
    main.appendChild(chips);
    const types = HCHIPS.find((c) => c[0] === st.hchip)[2], now = Date.now();
    const list = el("div", "list bklist");
    const rows = (h.history || []).filter((c) => !types || types.includes(c.ending));
    rows.forEach((c, i) => {
      const b = c.bounty, key = b.id + ":" + c.closed;
      const row = el("button", "bklot tap"); row.type = "button"; row.dataset.keep = "byhr:" + key;
      row.appendChild(Players.face({ id: b.target, name: b.targetName }, 30));
      const g = el("div", "grow"); const nm = el("div", "nm", b.targetName + " "); nm.appendChild(el("span", "bychip " + (ENDING_CLASS[c.ending] || "grey"), c.ending)); g.appendChild(nm);
      const hn = (b.hunters || []).length;
      const why = c.ending === "CLAIMED" ? "by " + (c.hunterName || "?") + (c.bonusPaid ? " · bonus paid" : "") : c.ending === "REMOVED" ? "by " + (c.by || "staff") + (b.byServer ? " · placed by the server" : " · refunded")
        : c.ending === "REMOVED_NO_REFUND" ? "by " + (c.by || "staff") + " · not refunded" : c.ending === "CANCELLED" ? "by the placer" : c.ending === "TARGET_STAFF" ? "the target is staff: everything refunded"
          : c.ending === "EXPIRED" ? (hn ? hn + (hn === 1 ? " hunter" : " hunters") + ", no kill" : "no hunter") : (c.by || "");
      g.appendChild(el("div", "sub", why + " · " + spanOf(now - c.closed) + " ago"));
      row.appendChild(g); row.appendChild(reward(b, "byrw small"));
      row.addEventListener("click", () => { st.hopen = st.hopen === key ? null : key; redraw(); });
      list.appendChild(row);
      if (st.hopen === key) { // D538: the whole bounty as it was, notes included
        const box = el("div", "bkform"); const kv = el("div", "kvl");
        [["Placed by", (b.byServer ? "the Server" : b.placerName) + " · " + new Date(b.placed).toLocaleString([], { dateStyle: "medium", timeStyle: "short" })],
          ["Reward", price(b, b.amount) + (b.bonus ? " + " + price(b, b.bonus) + " bonus" + (c.bonusPaid ? " (paid)" : "") : "")], ["Fee", b.fee ? fmtN(b.fee) + " pieces" : "none"],
          ["Hunters", (b.hunters || []).map((x) => x.name).join(", ") || "none"], ["Closed", new Date(c.closed).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) + (b.server ? " · " + b.server : "")],
          ["Bounty", b.id]].forEach(([k, v]) => { kv.appendChild(el("span", null, k)); kv.appendChild(el("span", null, v)); });
        box.appendChild(kv); box.appendChild(el("div", "bynotes" + (b.notes ? "" : " mu"), b.notes || "No notes."));
        list.appendChild(box);
      }
    });
    if (!rows.length) list.appendChild(el("div", "note bkempty", (h.history || []).length ? "No closed bounty of that kind." : "No bounty has closed yet."));
    main.appendChild(list);
  }

  // ---------------------------------------------------------------- + ADD (D529, D531)
  function freshAdd() { return { q: "", found: null, timer: 0, picked: null, coins: false, tier: 0, amount: "", time: "3", unit: "d", bonusOn: false, bonus: "", within: "1", wunit: "d", notes: "", reason: "", armed: 0, busy: false, answer: null }; }
  function addView(main) {
    const f = st.add || (st.add = freshAdd());
    const tiers = (st.data && st.data.tiers) || [];
    const q = el("input", "search byq"); q.type = "search"; q.placeholder = "The player's name"; q.value = f.q; q.maxLength = 17; q.dataset.keep = "byaddq";
    q.addEventListener("input", () => {
      f.q = q.value.trim(); clearTimeout(f.timer);
      f.timer = setTimeout(async () => { const s = f.q; if (!s) { f.found = null; redraw(); return; } const r = await call("/api/bounties/players", { search: s }); if (f.q === s && st.add === f) { f.found = r; redraw(); } }, 300);
    });
    main.appendChild(q);
    if (f.picked) {
      const p = el("div", "bklot"); p.appendChild(Players.face({ id: f.picked.id, name: f.picked.name }, 30));
      const g = el("div", "grow"); g.appendChild(el("div", "nm", f.picked.name)); g.appendChild(el("div", "sub", f.picked.online ? "online" : "offline")); p.appendChild(g);
      const x = el("button", "key", "CHANGE"); x.type = "button"; x.addEventListener("click", () => { f.picked = null; f.armed = 0; redraw(); }); p.appendChild(x);
      const l = el("div", "list bklist"); l.appendChild(p); main.appendChild(l);
    } else if (f.found) {
      const l = el("div", "list bklist");
      if (!f.found.ok) l.appendChild(el("div", "err", f.found.message));
      (f.found.players || []).slice(0, 12).forEach((p) => {
        const refused = p.refusal === "staff" || p.refusal === "protected";
        const r = el(refused ? "div" : "button", "bklot" + (refused ? " bydim" : " tap")); if (!refused) { r.type = "button"; r.dataset.keep = "bypick:" + p.id; }
        r.appendChild(Players.face({ id: p.id, name: p.name }, 30));
        const g = el("div", "grow"); g.appendChild(el("div", "nm", p.name));
        g.appendChild(el("div", "sub", p.refusal === "staff" ? "staff: cannot have a bounty" : p.refusal === "protected" ? "still under new-player protection" : p.online ? "online" : "offline"));
        r.appendChild(g);
        if (!refused) r.addEventListener("click", () => { f.picked = p; f.armed = 0; redraw(); });
        l.appendChild(r);
      });
      if (f.found.ok && !(f.found.players || []).length) l.appendChild(el("div", "note bkempty", "Nobody the bank knows has that in their name."));
      main.appendChild(l);
    }
    const unit = el("div", "bkmodes");
    [[false, "PIECES"], [true, "COINS"]].forEach(([c, label]) => { const k = el("button", "key" + (f.coins === c ? " on" : ""), label); k.type = "button"; k.addEventListener("click", () => { f.coins = c; f.armed = 0; redraw(); }); unit.appendChild(k); });
    main.appendChild(unit);
    if (f.coins) {
      const tk = el("div", "bkmodes");
      tiers.forEach((t, i) => { const k = el("button", "key" + (f.tier === i ? " on" : "")); k.type = "button"; k.appendChild(coinTag(i)); k.addEventListener("click", () => { f.tier = i; f.armed = 0; redraw(); }); tk.appendChild(k); });
      main.appendChild(tk);
    }
    const num = (label, key, hint, keep) => {
      const l = el("label", "bkfld"); l.appendChild(el("span", null, label)); const row = el("div", "bkin");
      const i = el("input", "bknum"); i.type = "number"; i.inputMode = "numeric"; i.min = "1"; i.step = "1"; i.value = f[key]; i.dataset.keep = keep;
      i.addEventListener("input", () => { f[key] = i.value; f.armed = 0; sync(); });
      row.appendChild(i); if (hint) row.appendChild(hint); l.appendChild(row); main.appendChild(l); return row;
    };
    const unitSel = (key, keep) => { const s = el("select", "byunit"); [["h", "hours"], ["d", "days"]].forEach(([v, t]) => { const o = el("option", null, t); o.value = v; o.selected = f[key] === v; s.appendChild(o); }); s.dataset.keep = keep; s.addEventListener("change", () => { f[key] = s.value; f.armed = 0; sync(); }); return s; };
    num("Reward", "amount", el("span", "mu", f.coins ? tierName(f.tier) + " coins (at least 1)" : "pieces (at least 1,000)"), "byamt");
    num("For", "time", unitSel("unit", "byunit"), "bytime");
    const bsw = el("label", "tick bksw"); const bc = el("input"); bc.type = "checkbox"; bc.checked = f.bonusOn; bc.dataset.keep = "bybon";
    bc.addEventListener("change", () => { f.bonusOn = bc.checked; f.armed = 0; redraw(); }); bsw.appendChild(bc); bsw.appendChild(el("span", null, "A bonus for a quick kill")); main.appendChild(bsw);
    if (f.bonusOn) { num("Bonus", "bonus", el("span", "mu", f.coins ? tierName(f.tier) + " coins" : "pieces"), "bybonus"); num("If killed within", "within", unitSel("wunit", "bywunit"), "bywithin"); }
    const notes = el("input", "reason bkwhy"); notes.type = "text"; notes.maxLength = 512; notes.placeholder = "Notes for hunters (optional; the server keeps up to its limit, 256 by default)"; notes.value = f.notes; notes.dataset.keep = "bynotes";
    notes.addEventListener("input", () => { f.notes = notes.value; f.armed = 0; sync(); }); main.appendChild(notes);
    const why = el("input", "reason bkwhy"); why.type = "text"; why.maxLength = 160; why.placeholder = "Why (staff only: the Action Log)"; why.value = f.reason; why.dataset.keep = "byaddwhy";
    why.addEventListener("input", () => { f.reason = why.value; f.armed = 0; sync(); }); main.appendChild(why);
    const line = el("div", "bksure"); main.appendChild(line);
    if (f.answer) main.appendChild(el("div", "err", f.answer));
    const go = el("button", "key"); go.type = "button"; go.dataset.keep = "byaddgo"; const keys = el("div", "bkmodes"); keys.appendChild(go); main.appendChild(keys);
    const ms = (v, u) => Math.floor(Number(v)) * (u === "h" ? H : D);
    function problem() {
      const amount = Math.floor(Number(f.amount)), dur = ms(f.time, f.unit);
      if (!f.picked) return "Pick a player first.";
      if (!(amount >= (f.coins ? 1 : 1000))) return f.coins ? "The reward is at least 1 coin." : "The reward is at least 1,000 pieces.";
      if (!(dur >= H && dur <= 30 * D)) return "A bounty runs from 1 hour to 30 days.";
      if (f.bonusOn) { const bn = Math.floor(Number(f.bonus)), w = ms(f.within, f.wunit); if (!(bn >= 1)) return "Type the bonus, or switch it off."; if (!(w >= H && w < dur)) return "The bonus window is shorter than the bounty and at least an hour."; }
      if (!f.reason.trim()) return "Say why: it goes in the Action Log.";
      return null;
    }
    function sync() {
      const why2 = problem(), live = Date.now() - f.armed <= 3000;
      const p = { tier: f.coins ? f.tier : -1 }, amount = Math.floor(Number(f.amount)), dur = ms(f.time, f.unit);
      line.className = "bksure" + (!why2 && live ? " armed" : "");
      line.textContent = why2 || ("Sure? Put " + price(p, amount) + " on " + f.picked.name + " for " + left(dur) + (f.bonusOn ? ", with " + price(p, Math.floor(Number(f.bonus))) + " more if killed within " + left(ms(f.within, f.wunit)) : "")
        + ", paid by the server" + (f.notes.trim() ? ", with the notes “" + f.notes.trim().slice(0, 60) + (f.notes.trim().length > 60 ? "…" : "") + "”" : "") + ". " + f.picked.name + " is told.");
      go.disabled = !!why2 || f.busy || sending;
      go.textContent = f.busy ? "Placing…" : sending ? "Waiting for another answer…" : !why2 && live ? "SURE? PLACE IT" : "PLACE IT";
      go.className = "key " + (!why2 && live && !f.busy ? "bkarmed" : "bkgoal");
      if (live) setTimeout(() => { if (go.isConnected && Date.now() - f.armed > 3000) sync(); }, 3000 - (Date.now() - f.armed) + 30);
    }
    go.addEventListener("click", async () => {
      if (problem() || f.busy || sending) return;
      if (Date.now() - f.armed > 3000) { f.armed = Date.now(); sync(); return; }
      f.armed = 0; f.busy = true; sending = true; sync();
      const tier = f.coins ? f.tier : -1, dur = ms(f.time, f.unit);
      const r = await call("/api/bounties/add", { target: f.picked.id, name: f.picked.name, tier, amount: Math.floor(Number(f.amount)), bonus: f.bonusOn ? Math.floor(Number(f.bonus)) : 0,
        bonusWithinMs: f.bonusOn ? ms(f.within, f.wunit) : 0, durationMs: dur, notes: f.notes.trim(), reason: f.reason.trim(), server: st.data.server || "" }, true);
      f.busy = false; sending = false;
      if (r.ok) { st.add = null; st.hist = null; if (st.page === "add") st.page = "open"; st.note = { ok: true, text: r.message }; redraw(); load(); return; }
      f.answer = r.message; redraw();
    });
    sync();
  }

  // ---------------------------------------------------------------- a player's sheet (D528)
  function line(p) {
    if ((!st.data || Date.now() - st.at > 60000) && !st.loading && (!st.triedAt || Date.now() - st.triedAt > 30000)) { st.triedAt = Date.now(); load(); }
    const d = st.data;
    if (!d || !d.ok) return null;
    const on = (d.open || []).filter((b) => b.target === p.id).length, placed = (d.open || []).filter((b) => b.placer === p.id).length,
      hunting = (d.open || []).filter((b) => (b.hunters || []).some((h) => h.id === p.id)).length;
    if (!on && !placed && !hunting) return null;
    const b = el("button", "mkline"); b.type = "button";
    b.appendChild(el("span", "rd", "✦"));
    const bits = []; if (on) bits.push(on + " on them"); if (placed) bits.push(placed + " placed"); if (hunting) bits.push("hunting " + hunting);
    b.appendChild(el("span", "grow", "Bounties: " + bits.join(", ")));
    b.appendChild(el("span", "tl", "Open ›"));
    b.addEventListener("click", () => openBounties(p));
    return b;
  }

  return { view, line, mayAct, open: (p) => { // every opening starts clean: no old chip, search, history row or add form (audit)
      st.page = "open"; st.sel = null; st.act = null; st.who = p ? { id: p.id, name: p.name } : null; st.note = null; st.readErr = null; st.data = null; st.hist = null;
      st.chip = "all"; st.q = ""; st.hchip = "all"; st.hopen = null; st.add = null; },
    reset: () => { st.sel = null; st.act = null; st.note = null; st.hopen = null; } };
})();
