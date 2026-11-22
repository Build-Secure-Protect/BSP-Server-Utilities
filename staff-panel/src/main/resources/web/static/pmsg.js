"use strict";
// Private messages to players (SU-111; D640, D651, D652 R6 and R7; tools/preview/talk_refined.html): the MESSAGES section
// in a player's card or sheet and on an offline player's page (look A of set AP: staff on the right in violet with their
// names, the player on the left, earlier conversations folded behind "Ended by", SEND, END CONVERSATION with a second tap
// within 3 s), the MESSAGE key that scrolls to it (a red dot while a reply waits), More › Player messages (open
// conversations, waiting replies first) and the red count on More. Every word from staff or players is set with textContent.
const PlayerMsg = (() => {
  const cache = new Map(), drafts = new Map(), shown = new Set(), lit = new Map(), refusals = new Map(), gens = new Map(), busy = new Set();
  const RETRY_MS = 30000, LIT_MS = 10000, MAX = 256, COUNT_FROM = 200, MAX_CACHED = 50, SCROLL_MS = 5000;
  // the list: one read at a time (a second asker waits for the same answer, audit: a page freeze), read again when a line
  // came during the read; the count: the newest of the list's and the panel's count events, never an older one over it
  let list = null, listAt = 0, listPromise = null, listDirty = false, waiting = 0, countAt = 0, armed = { id: null, at: 0 }, scrollTo = null;

  const pad = (n) => String(n).padStart(2, "0");
  function time(at) {
    const d = new Date(at), today = new Date();
    const hm = pad(d.getHours()) + ":" + pad(d.getMinutes());
    return d.toDateString() === today.toDateString() ? hm : d.toLocaleDateString([], { day: "numeric", month: "short" }) + " " + hm;
  }
  function span(ms) {
    const m = Math.max(1, Math.round(ms / 60000));
    return m < 60 ? m + " min" : m < 48 * 60 ? Math.round(m / 60) + " h" : Math.round(m / 1440) + " d";
  }

  // ---------------------------------------------------------------- reading
  // a player's conversations; a newer read always wins over an older one still on its way (audit)
  async function fetchFor(p, quiet) {
    const g = (gens.get(p.id) || 0) + 1; gens.set(p.id, g);
    if (!cache.has(p.id) || cache.get(p.id).error) cache.set(p.id, "loading");
    const r = await call("/api/pmsg", { id: p.id, name: p.name });
    if (gens.get(p.id) !== g) return;
    if (r.ok) r.fetchedAt = Date.now();
    cache.set(p.id, r.ok ? r : { error: r.message || "The messages could not be read.", at: Date.now() });
    while (cache.size > MAX_CACHED) cache.delete(cache.keys().next().value); // the oldest read first
    redraw(quiet);
  }
  // drawn again; while staff type in a messages box, after they leave it, unless they left it for that box's own keys
  // (audit: a SEND click was lost when the form was drawn again under it)
  function redraw(quiet) {
    if (typeof App === "undefined") return;
    const a = document.activeElement;
    const box = a && a.closest ? a.closest("#pdetail, .offsheet, .pmsgpage") : null;
    if (!quiet || !typingIn(box)) { App.refresh(); return; }
    a.addEventListener("blur", (ev) => {
      const to = ev.relatedTarget;
      if (to && to.closest && to.closest(".pmsg")) return; // its own SEND or END: they redraw when done
      setTimeout(() => App.refresh(), 0);
    }, { once: true });
  }
  function loadList() {
    if (listPromise) { listDirty = true; return listPromise; }
    const asked = Date.now();
    listPromise = (async () => {
      const r = await call("/api/pmsg/list", {});
      list = r; listAt = Date.now();
      if (r.ok) { r.fetchedAt = listAt; if (countAt <= asked) { waiting = r.waiting; countAt = asked; } }
      if (typeof nav === "function") nav();
    })().finally(() => {
      listPromise = null;
      if (listDirty) { listDirty = false; loadList().then(() => redraw(true)); }
    });
    return listPromise;
  }
  function waitingFor(id) {
    return !!(list && list.ok && list.open.some((o) => o.player === id && o.waitingOnStaff));
  }

  // A new line from the game or the panel: only that player's section is read again (when it is open or kept), and the
  // list when it is shown. The waiting count comes as its own event (count), worked out once by the panel after a burst.
  function event(e) {
    if (!e) return;
    if (e.event === "count") {
      if (typeof e.waiting === "number" && (e.at || 0) >= countAt) { waiting = e.waiting; countAt = e.at || Date.now(); if (typeof nav === "function") nav(); }
      if (list) loadList().then(() => redraw(true)); // the list's rows follow the count
      return;
    }
    if (e.event !== "line" || !e.line) return;
    const now = Date.now();
    lit.set(e.line.uid, now);
    for (const [uid, at] of lit) if (now - at > LIT_MS) lit.delete(uid);
    setTimeout(() => redraw(true), LIT_MS + 50);
    if (cache.has(e.line.player)) { const was = cache.get(e.line.player); fetchFor({ id: e.line.player, name: was && was.name || e.line.name }, true); }
  }
  // after a gap in the live feed: everything read again when next shown, the count at once
  function resync() { cache.clear(); countAt = 0; loadList().then(() => redraw(true)); }

  // ---------------------------------------------------------------- the MESSAGE key and the section
  function key(p) {
    const k = el("button", "key", "Message"); k.type = "button"; k.dataset.keep = "k:pmsg";
    if (waitingFor(p.id)) { k.classList.add("pmsgwait"); k.appendChild(el("span", "pdot")); }
    k.addEventListener("click", () => { const s = document.getElementById("pmsg-" + p.id); if (s) s.scrollIntoView({ behavior: "smooth", block: "start" }); });
    return k;
  }

  function bubble(l, name) {
    const staff = l.kind === "staff";
    const b = el("div", "pb " + (staff ? "st" : "pl") + (lit.has(l.uid) && Date.now() - lit.get(l.uid) < LIT_MS ? " new" : ""));
    const w = el("span", "w"); w.appendChild(el("b", null, staff ? l.staff : l.name || name)); w.appendChild(document.createTextNode(" · " + time(l.at))); // the signed name (audit)
    b.appendChild(w); b.appendChild(el("div", "mtext", l.text));
    return b;
  }
  function divider(text, cls) { return el("div", "pdiv" + (cls ? " " + cls : ""), text); }
  function endedWords(t) {
    return t.endedBy ? "Ended by " + t.endedBy + " · " + time(t.endedAt) : "Ended after 90 days with no line · " + time(t.endedAt);
  }

  function section(p) {
    const wrap = el("div", "pmsg"); wrap.id = "pmsg-" + p.id;
    let d = cache.get(p.id);
    if (d === undefined || (d && d.error && Date.now() - d.at > RETRY_MS)) { fetchFor(p); d = cache.get(p.id); }
    const head = el("div", "sect"); head.appendChild(el("span", null, "MESSAGES"));
    const right = el("span", "pmsgstate");
    if (d && d.ok && d.open) {
      right.appendChild(el("span", "state open", "open"));
      if (d.open.waiting === "staff") { const u = el("span", "unans"); u.appendChild(el("i")); u.appendChild(document.createTextNode("waiting " + span(d.open.waitMs + (Date.now() - d.fetchedAt)))); right.appendChild(u); }
    }
    head.appendChild(right); wrap.appendChild(head);
    if (d && d.error) {
      wrap.appendChild(el("div", "err", d.error));
      const again = el("button", "btn sm ghost", "Try again"); again.type = "button"; again.dataset.keep = "pmsgagain:" + p.id;
      again.addEventListener("click", () => { cache.delete(p.id); fetchFor(p); });
      wrap.appendChild(again);
    } else if (!d || d === "loading") {
      wrap.appendChild(el("div", "note", "Reading the messages…"));
    } else {
      const thread = el("div", "msgs");
      const talks = d.talks.slice().reverse(); // oldest first
      const name = d.name || p.name;
      talks.forEach((t) => {
        if (t.open) { t.lines.forEach((l) => { if (l.kind !== "end") thread.appendChild(bubble(l, name)); }); return; }
        const open = shown.has(p.id + ":" + t.id);
        if (open) t.lines.forEach((l) => { if (l.kind !== "end") thread.appendChild(bubble(l, name)); });
        const dv = el("button", "pdiv fold", endedWords(t) + (open ? " · ▾ hide" : " · ▸ show")); dv.type = "button"; dv.dataset.keep = "pfold:" + t.id;
        dv.addEventListener("click", () => { const k = p.id + ":" + t.id; if (shown.has(k)) shown.delete(k); else shown.add(k); App.refresh(); });
        thread.appendChild(dv);
      });
      if (!d.talks.length) thread.appendChild(el("div", "empty", "Start a conversation: they get a popup at once, or when they next log on. A MESSAGE STAFF key shows in their inventory until a member of staff ends it."));
      if (d.unverified) thread.appendChild(el("div", "gold", d.unverified + (d.unverified === 1 ? " line could not be verified and is" : " lines could not be verified and are") + " not shown."));
      wrap.appendChild(thread);
      wrap.appendChild(compose(p, d));
      if (d.open) wrap.appendChild(foot(p, d));
    }
    if (scrollTo && scrollTo.id === p.id && Date.now() - scrollTo.at < SCROLL_MS) { scrollTo = null; setTimeout(() => { if (wrap.isConnected) wrap.scrollIntoView({ block: "start" }); }, 30); }
    else if (scrollTo && Date.now() - scrollTo.at >= SCROLL_MS) scrollTo = null; // never later, somewhere else (audit)
    return wrap;
  }

  function compose(p, d) {
    const box = el("div", "pcompose");
    const f = el("form", "speakform");
    const i = el("input", "pin"); i.type = "text"; i.maxLength = MAX; i.value = drafts.get(p.id) || ""; i.dataset.keep = "pmsgin:" + p.id;
    i.placeholder = "Write to " + (d.name || p.name);
    const count = el("span", "pcount"); const n = i.value.length; count.textContent = n >= COUNT_FROM ? n + "/" + MAX : "";
    i.addEventListener("input", () => { drafts.set(p.id, i.value); const m = i.value.length; count.textContent = m >= COUNT_FROM ? m + "/" + MAX : ""; refusals.delete(p.id); });
    const s = el("button", "btn sm psend", "Send"); s.type = "submit"; s.disabled = busy.has(p.id) || !d.canWrite; s.dataset.keep = "psend:" + p.id;
    f.appendChild(i); f.appendChild(count); f.appendChild(s);
    f.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const text = (drafts.get(p.id) || "").trim();
      if (!text || busy.has(p.id)) return;
      busy.add(p.id); s.disabled = true; // one line per press for this player, though Enter or the key is pressed twice
      const r = await call("/api/pmsg/send", { id: p.id, name: d.name || p.name, text }, true);
      busy.delete(p.id); s.disabled = false;
      if (!r.ok) { refusals.set(p.id, r.message); App.refresh(); return; } // the words stay in the box (R6); a late answer says so
      drafts.delete(p.id); refusals.delete(p.id); cache.delete(p.id); fetchFor({ id: p.id, name: d.name || p.name });
    });
    box.appendChild(f);
    if (refusals.has(p.id)) box.appendChild(el("div", "err", refusals.get(p.id)));
    else if (!d.canWrite) box.appendChild(el("div", "note", "No game server is answering: messages can be read, not sent, until one is."));
    else box.appendChild(el("div", "note", d.where ? "Online on " + d.where + ": they get a popup at once." : "Offline: they get a popup when they next log on."));
    return box;
  }

  function foot(p, d) {
    const f = el("div", "pfoot");
    f.appendChild(el("span", "mu", "Open since " + time(d.open.since) + ", started by " + d.open.by + (d.open.seen ? "" : " · not seen yet")));
    const left = 3000 - (Date.now() - (armed.id === p.id ? armed.at : 0));
    const plain = "End conversation";
    const end = el("button", "pend" + (left > 0 ? " armed" : ""), left > 0 ? "Sure? End" : plain); end.type = "button"; end.dataset.keep = "pend:" + p.id;
    unarm(end, left, plain, "pend");
    end.addEventListener("click", async () => {
      if (busy.has(p.id)) return;
      if (armed.id !== p.id || Date.now() - armed.at > 3000) { armed = { id: p.id, at: Date.now() }; end.textContent = "Sure? End"; end.className = "pend armed"; unarm(end, 3000, plain, "pend"); return; }
      busy.add(p.id); end.disabled = true; armed = { id: null, at: 0 };
      const r = await call("/api/pmsg/end", { id: p.id, name: d.name || p.name }, true);
      busy.delete(p.id); end.disabled = false;
      if (!r.ok) { refusals.set(p.id, r.message); App.refresh(); return; }
      cache.delete(p.id); fetchFor({ id: p.id, name: d.name || p.name });
    });
    f.appendChild(end);
    return f;
  }

  // ---------------------------------------------------------------- More › Player messages (R7)
  function view(main) {
    const page = el("div", "pmsgpage"); main.appendChild(page);
    page.appendChild(el("h2", null, "Player messages"));
    if (!list) {
      page.appendChild(el("div", "note", "Reading the open conversations…"));
      if (!listPromise) loadList().then(() => App.refreshMore()); // one read, one redraw when it is back (audit: never a loop)
      return;
    }
    if (!list.ok) {
      page.appendChild(el("div", "err", list.message));
      const again = el("button", "btn sm ghost", "Try again"); again.type = "button"; again.addEventListener("click", () => { list = null; App.refreshMore(); });
      page.appendChild(again); return;
    }
    const box = el("div", "list pmsglist");
    list.open.forEach((o) => {
      const r = el("button", "item menuitem prow" + (o.waitingOnStaff ? " hot" : "")); r.type = "button"; r.dataset.keep = "prow:" + o.player;
      const pl = live.players.find((x) => x.id === o.player);
      r.appendChild(Players.face(pl || { name: o.name }, 28));
      const g = el("div", "grow"); const n = el("div", "nm"); n.appendChild(el("b", null, o.name));
      n.appendChild(el("span", "sub", " " + (o.where ? "online · " + o.where : o.notSeen ? "offline · not seen it yet" : "offline")));
      g.appendChild(n);
      if (o.last) g.appendChild(el("div", "last", (o.last.kind === "staff" ? o.last.staff + ": " : "") + o.last.text));
      r.appendChild(g);
      const since = o.sinceMs + (Date.now() - (list.fetchedAt || Date.now()));
      if (o.waitingOnStaff) { const u = el("span", "unans"); u.appendChild(el("i")); u.appendChild(document.createTextNode(span(since))); r.appendChild(u); }
      else r.appendChild(el("span", "mu", "sent " + span(since)));
      r.addEventListener("click", () => {
        scrollTo = { id: o.player, at: Date.now() };
        if (pl) openPlayerSheet(o.player); else openOffline({ id: o.player, name: o.name });
      });
      box.appendChild(r);
    });
    if (!list.open.length) box.appendChild(el("div", "note", "No open conversations with players."));
    page.appendChild(box);
    page.appendChild(el("div", "note", "Open conversations only. Ended ones stay on each player's card for 90 days."));
  }

  // the red count on More: conversations waiting on staff
  function badge() { return waiting > 0 ? String(waiting) : null; }
  function start() {
    loadList();
    // "waiting 4 min" counts up: shown sections and the list drawn again each minute, never while staff type (R6)
    setInterval(() => { if (document.querySelector(".pmsg, .pmsglist")) redraw(true); }, 60000);
  }
  return { section, key, view, badge, start, event, resync, refreshList: () => { list = null; } };
})();
