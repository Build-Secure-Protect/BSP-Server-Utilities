"use strict";
// The TOTEMS tab (SU-99; D613 to D629, look A of set AJ refined): stealing now (counting down each second), the players
// flagged in the window, BSP-Core's board with points gained or lost, and every steal with where, when, from whom and how
// fast; a player opens under their row with their points line, their newest steals, NOTES and PLAYER ›. Read on opening,
// on every "totems" event from the live feed (a board read, a steal starting or ending) and when the window changes. Text
// from the network is set with textContent only; the chart is drawn with SVG elements, never markup.

const Totems = (() => {
  const DAYS = [1, 7, 30, 90], DAYS_KEY = "bsp.totemsDays", BOARD_TOP = 20, STEPS = 50, LIT_MS = 10000;
  const ROMAN = ["?", "I", "II", "III", "IV", "V"];
  const CHIPS = [["all", "All"], ["flagged", "Flagged"], ["done", "Completed"], ["failed", "Failed"]];
  const st = { data: null, loading: false, again: false, days: loadDays(), chip: "all", q: "", shown: STEPS, boardAll: false, open: null, players: new Map(),
    notes: false, lit: new Map(), who: null, skew: 0 };
  let host = null, ticker = 0, soon = 0;

  function loadDays() { try { const d = Number(localStorage.getItem(DAYS_KEY)); return DAYS.includes(d) ? d : 7; } catch (e) { return 7; } }
  function saveDays() { try { localStorage.setItem(DAYS_KEY, String(st.days)); } catch (e) { /* kept for this page only */ } }
  // the panel's time, as its last answer gave it: countdowns and ages never follow a wrong clock on this device (audit)
  const nowS = () => Date.now() + st.skew;

  // ---------------------------------------------------------------- words (R9)
  const tier = (t) => ROMAN[t] || "?";
  function dimName(d) {
    d = String(d || "");
    if (d === "minecraft:overworld") return "Overworld";
    if (d === "minecraft:the_nether") return "Nether";
    if (d === "minecraft:the_end") return "End";
    const w = d.replace(/^.*:/, "").replace(/_/g, " ");
    return w.charAt(0).toUpperCase() + w.slice(1);
  }
  const clock = (ms) => { const s = Math.max(0, Math.round(ms / 1000)); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); };
  function span(ms) { // "1 min 41 s", "4 h 10 min", "2 d"
    const s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + " s";
    if (s < 3600) return Math.floor(s / 60) + " min" + (s % 60 ? " " + (s % 60) + " s" : "");
    if (s < 86400) { const m = Math.floor(s / 60); return Math.floor(m / 60) + " h" + (m % 60 ? " " + (m % 60) + " min" : ""); }
    return Math.floor(s / 86400) + " d";
  }
  function ago(ms) { // the flagged cards' "last": 11 m, 3 h, 1 d
    const m = Math.max(0, Math.round(ms / 60000));
    return m < 60 ? m + " m" : m < 1440 ? Math.floor(m / 60) + " h" : Math.floor(m / 1440) + " d";
  }
  function when(at) { // R5: "21:31" today, "Yest. 18:04", "18 Nov 22:10", in the viewer's own zone
    const d = new Date(at), now = new Date(nowS()), hm = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
    const day = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const diff = Math.round((day(now) - day(d)) / 86400000);
    if (diff <= 0) return hm;
    if (diff === 1) return "Yest. " + hm;
    return d.toLocaleDateString("en-GB", { day: "numeric", month: "short" }) + " " + hm;
  }
  function where(s) { // the coordinates never broken across lines
    const w = el("span", "mono", s.server + " · " + dimName(s.dim) + " · ");
    w.appendChild(el("span", "ttxyz", s.x + " " + s.y + " " + s.z));
    return w;
  }
  function whatTag(s) { // the tier, or FORGE for a Totem Forge's pour
    return s.source === "forge" ? el("span", "tt-forge", "FORGE") : el("span", "tt-tier", tier(s.tier));
  }

  // ---------------------------------------------------------------- reading
  async function load() {
    if (st.loading) { st.again = true; return; }
    st.loading = true; st.again = false;
    const days = st.days, r = await call("/api/totems", { days });
    st.loading = false;
    // a failed read for a window not shown yet replaces the old window's page, so its error shows instead of "Reading…" (audit)
    if (days === st.days && (r.ok || !st.data || st.data.days !== days)) { st.data = r.ok ? r : Object.assign({}, r, { days }); st.readErr = null; if (r.ok && r.now) st.skew = r.now - Date.now(); }
    else if (days === st.days) st.readErr = "Reading again failed: " + r.message + " What is shown is from the last read.";
    if (st.again) { st.again = false; load(); return; }
    if (st.open) loadPlayer(st.open.id);
    later();
  }
  async function loadPlayer(id) {
    const days = st.days, r = await call("/api/totems/player", { id, days });
    if (days !== st.days) return;
    st.players.set(id, r);
    later();
  }
  /** A "totems" event from the live feed: read again (once, shortly, however many come together); an ended steal is lit 10 s (R5). */
  function event(e) {
    if (e && e.event === "end" && e.steal && e.steal.uid) st.lit.set(e.steal.uid, nowS() + LIT_MS); // the panel's clock throughout (audit)
    if (!host) { st.data = null; return; } // read when the tab opens
    clearTimeout(soon); soon = setTimeout(load, 600);
  }
  function resync() { if (host) load(); else st.data = null; } // after a gap in the live feed

  // ---------------------------------------------------------------- mounting
  function mount(main) {
    host = main; main.classList.add("ttmain");
    toastReady();
    draw();
    load();
    clearInterval(ticker); ticker = setInterval(tick, 1000);
  }
  function unmount(main) {
    if (host && host === main) {
      main.classList.remove("ttmain"); host = null; listBox = null; clearInterval(ticker); ticker = 0; clearTimeout(laterTimer); st.notes = false;
      if (toastBox) { clearTimeout(toastTimer); toastBox.classList.add("hidden"); } // never left over the next tab (audit)
    }
  }
  function redraw() { if (host) later(); }
  // an answer's redraw waits for a press under way (app.js's pressing) and for typing in the tab, so neither is lost (audit)
  let laterTimer = 0;
  function later() {
    clearTimeout(laterTimer);
    if (!host) return;
    if (typeof pressing !== "undefined" && pressing && Date.now() - pressing < 8000) { laterTimer = setTimeout(later, 300); return; }
    if (typingIn(host)) { whenFree(host, later); return; }
    draw();
  }

  // each second: the countdowns, the board's age and the lit steals going out (no redraw of the rest)
  function tick() {
    if (!host) return;
    const now = nowS();
    host.querySelectorAll("[data-ends]").forEach((x) => { const left = Number(x.dataset.ends) - now; x.textContent = left > 0 ? clock(left) + " left" : "finishing…"; });
    host.querySelectorAll("[data-bar]").forEach((x) => {
      const [since, total] = x.dataset.bar.split(",").map(Number);
      x.style.width = Math.max(0, Math.min(100, 100 * (now - since) / total)) + "%";
    });
    const age = host.querySelector("[data-age]"); if (age) age.textContent = ageLine(now);
    host.querySelectorAll(".tt-lit[data-lit]").forEach((x) => { if (Number(x.dataset.lit) < now) x.classList.remove("tt-lit"); });
    st.lit.forEach((until, uid) => { if (until < now) st.lit.delete(uid); });
  }
  function ageLine(now) {
    const d = st.data;
    if (!d || !d.ok || !d.boardAt) return "the board is read every 30 seconds";
    return "board read " + span(now - d.boardAt) + " ago from " + d.boardServer;
  }

  // ---------------------------------------------------------------- drawing
  function draw() {
    if (!host) return;
    const h = hold(), now = nowS();
    host.textContent = "";
    const page = el("div", "ttpage"); host.appendChild(page);
    const top = el("div", "tttop");
    const tg = el("div", "grow"); tg.appendChild(el("div", "nm", "Totems"));
    const age = el("div", "sub", ageLine(now)); age.dataset.age = "1"; tg.appendChild(age); top.appendChild(tg);
    const seg = el("div", "seg ttdays");
    DAYS.forEach((d) => {
      const b = el("button", d === st.days ? "on" : "", d === 1 ? "24 H" : d + " DAYS"); b.type = "button"; b.dataset.keep = "ttdays:" + d;
      b.addEventListener("click", () => { if (st.days === d) return; st.days = d; saveDays(); st.players.clear(); st.shown = STEPS; load(); draw(); });
      seg.appendChild(b);
    });
    top.appendChild(seg); page.appendChild(top);
    const d = st.data;
    if (!d || (d.ok && d.days !== st.days)) { // never the last window's numbers under this window's words (audit)
      page.appendChild(el("div", "note", "Reading the board and the steals…")); give(h); return;
    }
    if (!d.ok) {
      page.appendChild(el("div", "err", d.message || "The TOTEMS tab could not be read."));
      const again = el("button", "btn sm ghost", "Try again"); again.type = "button"; again.addEventListener("click", () => load()); page.appendChild(again);
      give(h); return;
    }
    // R8: problems in plain words, the rest still shown
    if (st.readErr) page.appendChild(el("div", "gold", st.readErr));
    if (d.problem) page.appendChild(el("div", "gold", d.problem + (d.boardAt ? " Showing the board read " + span(now - d.boardAt) + " ago." : "")));
    if (d.pointsProblem) page.appendChild(el("div", "gold", d.pointsProblem));
    if (d.stealsProblem) page.appendChild(el("div", "err", d.stealsProblem));

    stealingNow(page, d, now);
    flagged(page, d, now);
    board(page, d);
    steals(page, d, now);
    give(h);
  }

  function sectLine(page, text) { page.appendChild(el("div", "sect", text)); }

  // R2: stealing now, counting down each second
  function stealingNow(page, d, now) {
    const list = d.stealing || [];
    if (!list.length) return; // R1: only while something runs
    const box = el("div", "ttnow");
    box.appendChild(el("span", "ttlive", "Stealing now · " + list.length));
    list.forEach((s) => {
      const r = el("div", "ttnowrow");
      const who = el("span", "grow"); who.appendChild(el("b", null, s.thiefName || "?")); who.appendChild(el("span", "mu", " from "));
      who.appendChild(document.createTextNode(s.owner ? (s.ownerName || "someone") + (s.tier > 0 ? "'s " : "") : "an unclaimed" + (s.tier > 0 ? " " : " totem")));
      if (s.tier > 0) who.appendChild(whatTag(s)); // the tier, or FORGE, when the start said (R2)
      r.appendChild(who);
      const left = el("span", "mono mu ttleft");
      const bar = el("span", "ttbar"); const fill = el("i"); bar.appendChild(fill);
      if (s.since > 0 && s.seconds > 0) {
        const ends = s.since + s.seconds * 1000;
        left.dataset.ends = String(ends); left.textContent = ends > now ? clock(ends - now) + " left" : "finishing…";
        fill.dataset.bar = s.since + "," + s.seconds * 1000; fill.style.width = Math.max(0, Math.min(100, 100 * (now - s.since) / (s.seconds * 1000))) + "%";
      } else { // read from the board: how much is left, not when it ends
        left.textContent = Math.round((s.left || 0) * 100) + " % left";
        fill.style.width = Math.round(100 - (s.left || 0) * 100) + "%";
      }
      r.appendChild(left);
      const w = el("div", "ttwhere"); w.appendChild(where(s)); w.appendChild(mapKey(s)); r.appendChild(w);
      r.appendChild(bar);
      box.appendChild(r);
    });
    page.appendChild(box);
  }

  // R3: the players flagged in the window
  function flagged(page, d, now) {
    const who = (d.thieves || []).filter((t) => t.fast > 0 || t.burst);
    sectLine(page, "FLAGGED IN " + windowWords() + " · " + who.length);
    if (!who.length) { const l = el("div", "list"); l.appendChild(el("div", "ttempty", "No FAST steals or bursts in " + windowWords().toLowerCase() + ".")); page.appendChild(l); return; }
    const grid = el("div", "ttflags");
    who.forEach((t) => {
      const fastest = (d.steals || []).filter((s) => s.thief === t.id && s.success && s.tookMs > 0).sort((a, b) => a.tookMs - b.tookMs)[0];
      const card = el("button", "ttsus" + (t.fast > 0 ? "" : " burstonly")); card.type = "button"; card.dataset.keep = "ttflag:" + t.id;
      card.appendChild(Players.face({ id: t.id, name: t.name }, 30));
      const g = el("div", "grow"); g.appendChild(el("div", "nm", t.name || "?"));
      g.appendChild(el("div", "sub", t.steals + (t.steals === 1 ? " steal" : " steals") + " · " + t.failed + " failed · " + t.points + " points taken"));
      card.appendChild(g);
      const fl = el("span", "ttflagtags");
      if (t.fast > 0) fl.appendChild(el("span", "ttflag ttfast", "FAST" + (t.fast > 1 ? " ×" + t.fast : "")));
      if (t.burst) fl.appendChild(el("span", "ttflag ttburst", "BURST"));
      card.appendChild(fl);
      const stats = el("div", "ttstats");
      stat(stats, "fastest", t.fastestMs ? clock(t.fastestMs) : "?", t.fast > 0 ? "rd" : "");
      stat(stats, "of", fastest && fastest.seconds ? clock(fastest.seconds * 1000) : "?", "");
      stat(stats, "min gap", t.shortestGapMs ? clock(t.shortestGapMs) : "·", t.burst ? "gd" : "");
      stat(stats, "last", ago(now - t.lastAt), "");
      card.appendChild(stats);
      card.addEventListener("click", () => toggle(t.id, t.name, "flag"));
      grid.appendChild(card);
      if (isOpen(t.id, "flag")) grid.appendChild(opened(t.id, t.name, d, now));
    });
    page.appendChild(grid);
  }
  function stat(box, label, value, cls) { const s = el("div", "ttst"); s.appendChild(document.createTextNode(label)); s.appendChild(el("b", cls, value)); box.appendChild(s); }
  const windowWords = () => st.days === 1 ? "24 HOURS" : st.days + " DAYS";

  // R4: the board, the top 20 and ALL ›
  function board(page, d) {
    const lines = d.board || [];
    sectLine(page, "BOARD · " + (d.boardLines || lines.length) + " PLAYERS · GAINED IN " + windowWords());
    const list = el("div", "list ttboard");
    const head = el("div", "ttbrd tthead");
    ["#", "Player", "Totems", "Steals", "Gained", "Points"].forEach((x, i) => head.appendChild(el("span", i === 2 || i === 3 ? "ttwide" : "", x)));
    list.appendChild(head);
    if (!lines.length) list.appendChild(el("div", "ttempty", d.boardAt ? "Nobody holds a totem." : "The board has not been read yet."));
    const thieves = new Map((d.thieves || []).map((t) => [t.id, t]));
    (st.boardAll ? lines : lines.slice(0, BOARD_TOP)).forEach((l) => {
      const row = el("button", "ttbrd" + (isOpen(l.id, "board") ? " tt-open" : "")); row.type = "button"; row.dataset.keep = "ttbrd:" + l.id;
      row.appendChild(el("span", "ttrk", String(l.rank)));
      const nm = el("span", "nm"); nm.appendChild(document.createTextNode((l.name || "?") + " ")); nm.appendChild(el("span", "tt-tier", tier(l.tier)));
      const t = thieves.get(String(l.id).toLowerCase());
      if (l.flags > 0) {
        const bd = el("span", "ttbadge" + (t && t.fast > 0 ? "" : " gold"), String(l.flags));
        bd.title = t ? [t.fast > 0 ? t.fast + " FAST" : "", t.burst ? "BURST" : ""].filter(Boolean).join(", ") : "flagged"; // not by colour alone
        bd.setAttribute("aria-label", bd.title); nm.appendChild(bd);
      }
      row.appendChild(nm);
      row.appendChild(el("span", "ttwide mono", String(l.totems)));
      row.appendChild(el("span", "ttwide mono", String(l.steals || 0)));
      const g = l.gained - l.lost;
      row.appendChild(el("span", "ttgain" + (g < 0 ? " lo" : ""), l.gained || l.lost ? (g > 0 ? "+" : g < 0 ? "−" : "±") + Math.abs(g) : ""));
      row.appendChild(el("span", "ttpts", String(l.points)));
      row.addEventListener("click", () => toggle(l.id, l.name, "board"));
      list.appendChild(row);
      if (isOpen(l.id, "board")) list.appendChild(opened(l.id, l.name, d, nowS()));
    });
    if (lines.length > BOARD_TOP) {
      const all = el("button", "ttmore", st.boardAll ? "THE TOP " + BOARD_TOP : "ALL " + lines.length + " ›"); all.type = "button"; all.dataset.keep = "ttboardall";
      all.addEventListener("click", () => { st.boardAll = !st.boardAll; draw(); });
      list.appendChild(all);
    }
    page.appendChild(list);
  }

  // R5: the steals, 50 at a time, chips with counts, a name search
  function matches(s, chip) {
    if (chip === "flagged") return s.fast || s.burst;
    if (chip === "done") return s.success;
    if (chip === "failed") return !s.success;
    return true;
  }
  function steals(page, d, now) {
    const all = d.steals || [];
    sectLine(page, "STEALS · " + all.length);
    const chips = el("div", "ttchips");
    CHIPS.forEach(([k, label]) => {
      const n = all.filter((s) => matches(s, k)).length;
      const c = el("button", "chip ttchip" + (st.chip === k ? " on" : "")); c.type = "button"; c.dataset.keep = "ttchip:" + k;
      c.appendChild(document.createTextNode(label + (k === "all" ? "" : " "))); if (k !== "all") c.appendChild(el("b", null, String(n)));
      c.addEventListener("click", () => { st.chip = k; st.shown = STEPS; draw(); });
      chips.appendChild(c);
    });
    page.appendChild(chips);
    const q = el("input", "search ttq"); q.type = "search"; q.placeholder = "Player"; q.value = st.q; q.maxLength = 17; q.dataset.keep = "ttq";
    q.addEventListener("input", () => { st.q = q.value.trim(); st.who = null; st.shown = STEPS; drawList(); }); // the box itself is never drawn again while typed in (audit)
    page.appendChild(q);
    if (d.unverified > 0) page.appendChild(el("div", "gold", d.unverified + (d.unverified === 1 ? " steal could not be verified and is" : " steals could not be verified and are")
        + " not shown (their rows are not signed by a game server).")); // D628
    const list = el("div", "list ttsteals");
    page.appendChild(list);
    listBox = list;
    fillList(list, d, now);
    if (d.stealsCut) page.appendChild(el("div", "note", "Only the newest 2,000 steals are read; pick a shorter window to see the rest of them."));
  }
  let listBox = null;
  function drawList() { if (listBox && listBox.isConnected && st.data) { listBox.textContent = ""; fillList(listBox, st.data, nowS()); } }
  function fillList(list, d, now) {
    const all = d.steals || [];
    const ql = st.q.toLowerCase();
    const shown = all.filter((s) => matches(s, st.chip) && (st.who ? String(s.thief).toLowerCase() === st.who.id
        : !ql || String(s.thiefName).toLowerCase().includes(ql) || String(s.ownerName).toLowerCase().includes(ql)));
    if (st.who) {
      const only = el("button", "chip ttchip on ttonly", "Only " + st.who.name + "'s steals ✕"); only.type = "button"; only.dataset.keep = "ttonly";
      only.addEventListener("click", () => { st.who = null; draw(); });
      list.appendChild(only);
    }
    if (!shown.length) list.appendChild(el("div", "ttempty", all.length ? "No steals match." : "No steals in " + windowWords().toLowerCase() + "."));
    shown.slice(0, st.shown).forEach((s) => {
      list.appendChild(stealRow(s, now, true));
      if (isOpen(s.thief, "steal:" + s.uid)) list.appendChild(opened(s.thief, s.thiefName, d, now));
    });
    if (shown.length > st.shown) {
      const more = el("button", "ttmore", "MORE · " + (shown.length - st.shown) + " left"); more.type = "button"; more.dataset.keep = "ttmoresteals";
      more.addEventListener("click", () => { st.shown += STEPS; draw(); });
      list.appendChild(more);
    }
  }

  function stealRow(s, now, withName) {
    const lit = st.lit.get(s.uid), litNow = lit && lit > now;
    const r = el("div", "ttev" + (s.success ? "" : " failed") + (litNow ? " tt-lit" : ""));
    if (litNow) r.dataset.lit = String(lit);
    r.appendChild(el("span", "ttt", when(s.ended)));
    const g = el("div", "grow");
    const line = el("div", "ttline");
    if (withName) {
      const n = el("button", "ttname", s.thiefName || "?"); n.type = "button"; n.dataset.keep = "ttname:" + s.uid;
      n.addEventListener("click", (ev) => { ev.stopPropagation(); toggle(s.thief, s.thiefName, "steal:" + s.uid); });
      line.appendChild(n); line.appendChild(el("span", "mu", s.success ? " took " : " tried "));
    }
    line.appendChild(document.createTextNode(s.owner ? (s.ownerName || "someone") + "'s " : "an unclaimed "));
    line.appendChild(whatTag(s));
    if (s.fast) line.appendChild(el("span", "ttflag ttfast", "FAST"));
    if (s.burst) line.appendChild(el("span", "ttflag ttburst", "BURST"));
    if (!s.success) line.appendChild(el("span", "ttflag ttfail", "FAILED"));
    g.appendChild(line);
    const w = el("div", "ttwhere"); w.appendChild(where(s)); if (s.success) w.appendChild(mapKey(s)); g.appendChild(w);
    if (!s.success) g.appendChild(el("div", "sub", s.why || "Failed"));
    else if (!s.started) g.appendChild(el("div", "sub", "start not seen (the server restarted)"));
    else if (s.gapMs > 0) g.appendChild(el("div", "sub", span(s.gapMs) + " after their last"));
    r.appendChild(g);
    const took = el("span", "tttook" + (s.fast ? " rd" : ""), s.started ? clock(s.tookMs) : "?");
    took.appendChild(el("small", null, "of " + (s.seconds ? clock(s.seconds * 1000) : "?")));
    r.appendChild(took);
    return r;
  }

  // R7: MAP › opens the Map tab on that server and world at the block, or says the map is not shown
  function mapKey(s) {
    const b = el("button", "ttmap", "MAP ›"); b.type = "button"; b.dataset.keep = "ttmap:" + (s.uid || s.server + "|" + s.dim + "|" + s.x + "|" + s.y + "|" + s.z);
    b.addEventListener("click", async (ev) => {
      ev.stopPropagation();
      b.disabled = true;
      const ok = await MapView.canShow(s.server, s.dim); // only reads the list of maps: nothing on the Map tab changes yet
      b.disabled = false;
      if (!host || tab !== "Totems") return; // staff moved on while the maps were read: never pulled to the Map tab, nothing changed (audit)
      if (ok && MapView.showSpot(s.server, s.dim, s.x, s.y, s.z)) { tab = "Map"; render(); return; }
      toast(ok === null ? "The list of maps could not be read. Try MAP › again." // where staff are looking, not at the top of the page
        : "No map of " + s.server + "'s " + dimName(s.dim) + " is shown on the panel.");
    });
    return b;
  }

  // a line held at the foot of the screen for 6 seconds
  let toastBox = null, toastTimer = 0;
  function toastReady() { // made before it is needed, so a screen reader announces its first words (audit)
    if (!toastBox) { toastBox = el("div", "gold tttoast hidden"); toastBox.setAttribute("role", "status"); document.body.appendChild(toastBox); }
  }
  function toast(text) {
    toastReady();
    toastBox.textContent = text; toastBox.classList.remove("hidden");
    clearTimeout(toastTimer); toastTimer = setTimeout(() => toastBox.classList.add("hidden"), 6000);
  }

  // ---------------------------------------------------------------- a player opened (R6)
  const isOpen = (id, at) => !!st.open && st.open.id === String(id).toLowerCase() && st.open.at === at;
  function toggle(id, name, at) {
    id = String(id).toLowerCase();
    if (st.open && st.open.id === id && st.open.at === at) { st.open = null; st.notes = false; draw(); return; }
    st.open = { id, name, at }; st.notes = false;
    if (!st.players.has(id)) loadPlayer(id);
    draw();
  }
  function opened(id, name, d, now) {
    id = String(id).toLowerCase();
    const box = el("div", "ttopen");
    const p = st.players.get(id), t = (d.thieves || []).find((x) => x.id === id), line = (d.board || []).find((l) => String(l.id).toLowerCase() === id);
    const online = typeof live !== "undefined" && live ? live.players.find((x) => x.id === id) : null;
    const bits = [];
    if (line) bits.push(line.totems + (line.totems === 1 ? " totem" : " totems") + " · best " + tier(line.tier));
    bits.push(online ? "online on " + online.server : "offline");
    if (t) bits.push(t.steals + " steals, " + t.failed + " failed, " + t.points + " points taken");
    box.appendChild(el("div", "sub wrap", bits.join(" · ")));
    if (!p) { box.appendChild(el("div", "note", "Reading their points and steals…")); return box; }
    if (!p.ok) { box.appendChild(el("div", "err", p.message || "Their points could not be read.")); return box; }
    if (p.pointsProblem) box.appendChild(el("div", "gold", p.pointsProblem));
    box.appendChild(chart(p, now));
    const mine = p.steals || [];
    if (mine.some((s) => s.fast)) box.appendChild(el("div", "gold", "A FAST steal may be an operator pressing finish on BSP-Core's admin screen, which is not in the Action Log. Ask the operators before acting.")); // D627
    if (!mine.length) box.appendChild(el("div", "note", "No steals of theirs in " + windowWords().toLowerCase() + "."));
    mine.slice(0, 5).forEach((s) => box.appendChild(stealRow(s, now, false)));
    const keys = el("div", "ttkeys");
    const allSteals = el("button", "btn sm ghost", "ALL " + mine.length + " STEALS"); allSteals.type = "button";
    allSteals.addEventListener("click", () => { // their steals as thief, by id: never a name merely containing theirs (audit)
      st.who = { id, name: p.name || name || "?" }; st.q = ""; st.chip = "all"; st.shown = STEPS; draw();
      const q = host && host.querySelector(".ttq"); if (q) q.scrollIntoView({ block: "start" });
    });
    const notes = el("button", "btn sm ghost", st.notes ? "HIDE NOTES" : "NOTES"); notes.type = "button"; notes.dataset.keep = "ttnotes";
    notes.addEventListener("click", () => { st.notes = !st.notes; draw(); });
    const player = el("button", "btn sm ghost", "PLAYER ›"); player.type = "button";
    player.addEventListener("click", () => { if (online) openPlayerSheet(id); else openOffline({ id, name: p.name || name }); });
    keys.appendChild(allSteals); keys.appendChild(notes); keys.appendChild(player);
    box.appendChild(keys);
    if (st.notes) box.appendChild(Notes.section({ id, name: p.name || name }));
    return box;
  }

  // the points line over the window: steps, a red dot for FAST, gold for a burst, hollow for a failed steal
  const SVG = "http://www.w3.org/2000/svg";
  function sv(tag, attrs) { const e = document.createElementNS(SVG, tag); Object.entries(attrs).forEach(([k, v]) => e.setAttribute(k, String(v))); return e; }
  function chart(p, now) {
    const wrap = el("div", "ttchart");
    const W = 600, H = 170, L = 30, R = 594, T = 12, B = 140, from = now - st.days * 86400000;
    const pts = (p.points || []).filter((x) => x.at >= from);
    // where the line starts: the value before the window's first change (0 before a first sighting)
    let v0 = pts.length ? (pts[0].first ? null : pts[0].before) : (p.now ? p.now.points : typeof p.start === "number" ? p.start : 0);
    const steps = []; // [at, value]
    if (v0 !== null) steps.push([from, v0]);
    pts.forEach((x) => steps.push([x.at, x.after]));
    const end = steps.length ? steps[steps.length - 1][1] : 0;
    steps.push([now, p.now ? p.now.points : end]);
    if (!pts.length && !p.now && typeof p.start !== "number") wrap.appendChild(el("div", "note", "Not on the board read: where their points stand is not known."));
    const max = Math.max(1, ...steps.map((s) => s[1]));
    const X = (at) => L + (R - L) * Math.max(0, Math.min(1, (at - from) / (now - from)));
    const Y = (v) => B - (B - T) * (v / max);
    const svg = sv("svg", { viewBox: "0 0 " + W + " " + H, role: "img" });
    svg.setAttribute("aria-label", (p.name || "Their") + " points over " + windowWords().toLowerCase());
    [0, 0.5, 1].forEach((f) => {
      const v = Math.round(max * f), y = Y(v);
      svg.appendChild(sv("line", { x1: L, y1: y, x2: R, y2: y, stroke: "#2a2f3a", "stroke-width": 1 }));
      const tx = sv("text", { x: 2, y: y + 4, fill: "#6b7180", "font-size": 11, "font-family": "ui-monospace, Menlo, monospace" }); tx.textContent = String(v); svg.appendChild(tx);
    });
    [[from, "start"], [(from + now) / 2, "middle"], [now, "end"]].forEach(([at, k]) => {
      const tx = sv("text", { x: k === "end" ? R : X(at), y: H - 6, fill: "#6b7180", "font-size": 11, "text-anchor": k === "start" ? "start" : k === "end" ? "end" : "middle" });
      tx.textContent = st.days === 1 ? new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hourCycle: "h23" }) : new Date(at).toLocaleDateString("en-GB", { day: "numeric", month: "short" });
      svg.appendChild(tx);
    });
    let path = "";
    steps.forEach(([at, v], i) => { const x = X(at), y = Y(v); path += i === 0 ? "M" + x + " " + y : " H" + x + " V" + y; });
    if (path) svg.appendChild(sv("path", { d: path, fill: "none", stroke: "#19d3b0", "stroke-width": 2.2, "stroke-linejoin": "round" }));
    const valueAt = (at) => { let v = steps.length ? steps[0][1] : 0; steps.forEach(([a, x]) => { if (a <= at) v = x; }); return v; };
    (p.steals || []).filter((s) => s.ended >= from).forEach((s) => {
      const x = X(s.ended), y = Y(valueAt(s.ended));
      if (!s.success) svg.appendChild(sv("circle", { cx: x, cy: y, r: 4, fill: "none", stroke: "#9aa3b5", "stroke-width": 1.6 }));
      else if (s.fast) svg.appendChild(sv("circle", { cx: x, cy: y, r: 5, fill: "#ff6b5c" }));
      else if (s.burst) svg.appendChild(sv("polygon", { points: [x, y - 5.5, x + 5.5, y, x, y + 5.5, x - 5.5, y].join(" "), fill: "#ffd23a" })); // a diamond: not by colour alone
    });
    wrap.appendChild(svg);
    const leg = el("div", "ttlegend");
    [["points", "ttk ttkline"], ["fast", "ttk ttkfast"], ["burst", "ttk ttkburst"], ["failed", "ttk ttkfail"]].forEach(([t, c]) => { const s = el("span"); s.appendChild(el("i", c)); s.appendChild(document.createTextNode(t)); leg.appendChild(s); });
    wrap.appendChild(leg);
    if (p.pointsCut) wrap.appendChild(el("div", "note", "Only the newest 20,000 changes are drawn."));
    return wrap;
  }

  return { mount, unmount, redraw, event, resync };
})();
