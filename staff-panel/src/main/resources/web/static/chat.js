"use strict";
// The network's chat (D261 to D263, D304 to D313): every server's public chat, live, the newest at the bottom with a divider
// for each day; server chips to filter; lines naming you or asking for staff marked red (and counted on the tab's badge);
// older lines when scrolled to the top, back to 30 days; search; speaking as yourself or as [Server], to one server or the
// network, with each server's answer. On a phone it is the Chat tab; on a computer the column on the right (D292, D294).
// New lines are added under the ones on screen (a batch at once), so a selection, the scroll and the speak box stay as
// they are. Every text from the network is set with textContent.
const ChatView = (() => {
  const CALL = /\b(staff|admin|mod|help)\b/i, MAX = 256, KEEP = 1500, SLACK = 100;
  let lines = [], uids = new Set(), more = true, loading = false, started = false, startError = "";
  let filter = "", unread = { calls: 0, any: false }, past = null, pastNew = 0;
  let voice = "staff", to = "network", draft = "", sending = [], picker = false;
  let search = null, host = null, flash = null;

  // ---------------------------------------------------------------- lines in
  function add(l) {
    if (uids.has(l.uid)) return false;
    uids.add(l.uid); lines.push(l);
    if (lines.length > 1 && lines[lines.length - 2].at > l.at) lines.sort((a, b) => a.at - b.at);
    return true;
  }
  // Only the newest 1,500 lines are kept, and only while the stream shows its newest lines: lines loaded by scrolling
  // back are never dropped under the reader (audit).
  function trim() {
    if (lines.length <= KEEP + SLACK) return false;
    lines.splice(0, lines.length - KEEP).forEach((x) => uids.delete(x.uid)); more = true;
    return true;
  }
  function isCall(l) {
    if (l.kind !== "player" || !me) return false;
    const name = me.mcName.replace(/[^A-Za-z0-9_]/g, "");
    return CALL.test(l.text) || (name && new RegExp("(^|[^A-Za-z0-9_])@?" + name + "([^A-Za-z0-9_]|$)", "i").test(l.text));
  }
  async function start() {
    if (started) return; started = true; startError = "";
    try { const r = await api("/api/chat"); r.lines.forEach(add); more = r.more; } catch (e) { started = false; startError = failText(e); }
    draw(true);
  }
  // a batch of lines from the live feed: drawn once
  function incoming(batch) {
    if (!Array.isArray(batch)) batch = [batch];
    if (past) { pastNew += batch.length; drawPill(); return; }
    const fresh = batch.filter(add);
    if (!fresh.length) return;
    if (!shown()) {
      fresh.forEach((l) => { if (isCall(l)) unread.calls++; else unread.any = true; });
      if (typeof nav === "function") nav();
    }
    if (!host) { trim(); return; }
    if (search) return; // drawn when the search closes; the search form is left as it is
    appendLines(fresh);
  }
  async function older() {
    if (loading || !more || !lines.length || past) return;
    loading = true;
    try {
      const r = await api("/api/chat?before=" + lines[0].at); // at or before: lines sharing that millisecond are not skipped (audit)
      const box = host && host.querySelector(".chatlines"), h0 = box ? box.scrollHeight : 0;
      r.lines.forEach(add); more = r.more;
      draw(false);
      const b2 = host && host.querySelector(".chatlines"); if (b2 && box) b2.scrollTop += b2.scrollHeight - h0;
    } catch (e) { /* the next scroll to the top asks again */ } finally { loading = false; }
  }

  // ---------------------------------------------------------------- where it is drawn
  function shown() { return !!host && !document.hidden; }
  function badge() { return shown() ? null : unread.calls > 0 ? String(unread.calls) : unread.any ? "dot" : null; }
  function mount(el) {
    host = el; unread = { calls: 0, any: false };
    if (typeof nav === "function") nav();
    trim(); start(); draw(true);
  }
  function unmount(el) { if (!el || host === el) host = null; }

  // ---------------------------------------------------------------- drawing
  const COLOURS = ["#19d3b0", "#7fb2ff", "#d9a066", "#b58cff", "#9aa3b5", "#7fe08a", "#ffd23a", "#ff9ec7"];
  // each live server its own colour (by its place in the list); a server not live now by its name
  function colour(server) {
    const i = (live.servers || []).findIndex((s) => s.server === server);
    if (i >= 0) return COLOURS[i % COLOURS.length];
    let h = 0; for (const c of server) h = (h * 31 + c.charCodeAt(0)) >>> 0; return COLOURS[h % COLOURS.length];
  }
  const time = (at) => new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  function dayName(at) {
    const d = new Date(at), today = new Date(); const t0 = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
    if (at >= t0) return "Today"; if (at >= t0 - 86400000) return "Yesterday";
    return d.toLocaleDateString([], { weekday: "long", day: "numeric", month: "short" });
  }
  const dayKey = (at) => new Date(at).toDateString();
  const visible = (l) => !filter || (l.kind === "player" ? l.server === filter : l.server === "network" || l.server === filter);
  function visibleLines() { return lines.filter(visible); }
  function serverTag(server) {
    const s = el("span", "sv"); const dot = el("i"); dot.style.background = colour(server); s.appendChild(dot); s.appendChild(document.createTextNode(server)); return s;
  }
  function lineEl(l, pending) {
    const row = el("div", "cl" + (isCall(l) ? " hit" : "") + (pending ? " pend" : "") + (flash === l.uid ? " flash" : ""));
    row.dataset.uid = l.uid; row.dataset.at = String(l.at);
    row.appendChild(el("span", "t", time(l.at))); row.appendChild(document.createTextNode(" "));
    if (l.kind === "staff") {
      row.appendChild(el("b", "vtx", "[Staff] " + l.name)); row.appendChild(document.createTextNode(" "));
      row.appendChild(el("span", "tox", l.server === "network" ? "to the network" : "to " + l.server));
    } else if (l.kind === "server") {
      row.appendChild(el("b", "gtx", "[Server]"));
      row.appendChild(el("span", "tox", " by " + l.name + (l.server === "network" ? ", to the network" : ", to " + l.server)));
    } else {
      row.appendChild(serverTag(l.server)); row.appendChild(document.createTextNode(" "));
      const who = el("button", "who", l.name); who.type = "button"; who.title = "Open " + l.name;
      who.addEventListener("click", () => openPlayer(l));
      row.appendChild(who);
      if (l.player) row.appendChild(Tags.chips(l.player, 2)); // D331
    }
    row.appendChild(document.createTextNode(" " + l.text));
    return row;
  }
  // a tapped name opens their sheet when online, else their Find a player page (D304, D311)
  function openPlayer(l) {
    const p = (live.players || []).find((x) => x.id === l.player || x.name === l.name);
    if (p) { if (typeof openPlayerSheet === "function") openPlayerSheet(p.id); return; }
    if (l.player && typeof openOffline === "function") { openOffline({ id: l.player, name: l.name }); return; }
    toast(l.name + " is not online now.");
  }
  function toast(text) {
    const t = el("div", "toast", text); document.body.appendChild(t); setTimeout(() => t.remove(), 2600);
  }
  function headLine() {
    return el("div", "day dim chead", past ? "" : more ? (loading ? "Loading older lines…" : "Scroll up for older lines") : "The start of the last 30 days");
  }

  function draw(toBottom) {
    if (!host) return;
    const old = host.querySelector(".chatlines");
    const atBottom = !old || old.scrollHeight - old.scrollTop - old.clientHeight < 40;
    const keepTop = old ? old.scrollTop : 0;
    const h = hold();
    host.textContent = "";
    const box = el("div", "chat");
    box.appendChild(search ? searchBar() : topBar());
    if (search) { box.appendChild(results()); host.appendChild(box); give(h); return; }
    const list = el("div", "chatlines");
    if (past) list.appendChild(el("div", "day chead", "Around " + new Date(past).toLocaleString([], { dateStyle: "medium", timeStyle: "short" })));
    else list.appendChild(headLine());
    if (startError) {
      const e = el("div", "err", startError); const again = el("button", "btn sm ghost", "Try again"); again.type = "button";
      again.addEventListener("click", () => { start(); }); list.appendChild(e); list.appendChild(again);
    }
    let lastDay = "", lastAt = 0;
    const mine = new Map(sending.filter((x) => x.line).map((x) => [x.line.uid, x]));
    for (const l of visibleLines()) {
      const k = dayKey(l.at); if (k !== lastDay) { lastDay = k; list.appendChild(el("div", "day", dayName(l.at))); }
      const row = lineEl(l, false); list.appendChild(row); lastAt = l.at;
      const s = mine.get(l.uid); if (s) outcome(list, row, s);
    }
    list.dataset.lastDay = lastDay; list.dataset.lastAt = String(lastAt);
    sending.filter((x) => !x.line).forEach((s) => {
      const row = lineEl({ uid: s.id, at: s.at, kind: s.voice, name: me.mcName, server: s.to, text: s.text }, true);
      list.appendChild(row); outcome(list, row, s); // still on its way, or shown nowhere
    });
    list.addEventListener("scroll", () => {
      if (list.scrollTop < 40) older();
      else if (!past && list.scrollHeight - list.scrollTop - list.clientHeight < 40 && trim()) dropRows(list); // back at the newest lines
    });
    box.appendChild(list);
    box.appendChild(pillEl());
    box.appendChild(speakBar());
    if (picker) box.appendChild(pickerSheet());
    host.appendChild(box);
    if (toBottom || atBottom) list.scrollTop = list.scrollHeight; else list.scrollTop = keepTop;
    const flashEl = list.querySelector(".flash"); if (flashEl && past) flashEl.scrollIntoView({ block: "center" });
    give(h);
  }
  // new lines under the ones on screen; anything out of order (or a line of ours still on its way) draws the whole stream
  function appendLines(fresh) {
    const list = host.querySelector(".chatlines");
    if (!list || list.classList.contains("results")) return;
    const show = fresh.filter(visible);
    if (!show.length) return;
    const lastAt = Number(list.dataset.lastAt || 0);
    if (sending.some((s) => !s.line) || show.some((l) => l.at < lastAt) || startError) { draw(false); return; }
    const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 40;
    let lastDay = list.dataset.lastDay || "";
    show.forEach((l) => {
      const k = dayKey(l.at); if (k !== lastDay) { lastDay = k; list.appendChild(el("div", "day", dayName(l.at))); }
      list.appendChild(lineEl(l, false));
    });
    list.dataset.lastDay = lastDay; list.dataset.lastAt = String(show[show.length - 1].at);
    if (atBottom) { if (trim()) dropRows(list); list.scrollTop = list.scrollHeight; }
  }
  // the rows of lines no longer kept, from the top, and the day dividers they leave empty
  function dropRows(list) {
    const head = list.querySelector(".chead"); if (head && !past) head.textContent = more ? "Scroll up for older lines" : "The start of the last 30 days";
    const kept = new Set([...uids].map(String)); // a row's uid is read back as text
    let n = head ? head.nextSibling : list.firstChild;
    while (n) {
      const next = n.nextSibling;
      if (n.dataset && n.dataset.uid) { if (kept.has(n.dataset.uid)) break; n.remove(); }
      else if (n.classList && (n.classList.contains("day") || n.classList.contains("fail"))) n.remove();
      else break;
      n = next;
    }
    if (n && n.dataset && n.dataset.uid) list.insertBefore(el("div", "day", dayName(Number(n.dataset.at))), n); // its day's divider
    list.scrollTop = list.scrollHeight;
  }
  function pillEl() {
    const pill = el("button", "pill" + (past || pastNew ? "" : " hidden"), past ? (pastNew ? pastNew + " new lines · back to now ↓" : "Back to now ↓") : ""); pill.type = "button";
    pill.dataset.keep = "chatpill";
    pill.addEventListener("click", backToNow);
    return pill;
  }
  function drawPill() { const p = host && host.querySelector(".pill"); if (p) p.replaceWith(pillEl()); }

  // after SEND (D313): "✓ 7 servers" on the line, and a red line with RETRY for each server that did not show it
  function outcome(list, row, s) {
    if (!s.done) { row.appendChild(el("span", "okm2 wait", "sending…")); return; }
    const okN = s.results.filter((r) => r.ok).length, n = s.results.length;
    if (okN) row.appendChild(el("span", "okm2", "✓ " + (okN === n ? n + (n === 1 ? " server" : " servers") : okN + " of " + n + " servers")));
    s.results.filter((r) => !r.ok).forEach((r) => {
      const f = el("div", "fail"); f.appendChild(el("span", null, r.server + ": " + r.message));
      const again = el("button", null, r.retrying ? "SENDING…" : "RETRY"); again.type = "button"; again.disabled = !!r.retrying; again.dataset.keep = "retry:" + s.id + ":" + r.server;
      again.addEventListener("click", () => retry(s, r, again));
      f.appendChild(again); list.appendChild(f);
    });
  }

  function topBar() {
    const bar = el("div", "chatbar");
    const chips = el("div", "chips"); chips.dataset.scroll = "chatchips";
    const all = el("button", "chip" + (filter ? "" : " on"), "All servers"); all.type = "button"; all.dataset.keep = "chip:";
    all.addEventListener("click", () => { filter = ""; draw(true); }); chips.appendChild(all);
    (live.servers || []).forEach((s) => {
      const c = el("button", "chip" + (filter === s.server ? " on" : "")); c.type = "button"; c.dataset.keep = "chip:" + s.server;
      const dot = el("span", "dot"); dot.style.background = colour(s.server); c.appendChild(dot); c.appendChild(document.createTextNode(s.server));
      c.addEventListener("click", () => { filter = filter === s.server ? "" : s.server; draw(true); }); chips.appendChild(c);
    });
    bar.appendChild(chips);
    const find = el("button", "ico", ""); find.type = "button"; find.title = "Search the chat"; find.dataset.keep = "chatfind";
    find.innerHTML = '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>';
    find.addEventListener("click", () => { search = { q: "", player: "", server: "", days: "7", results: null, busy: false }; draw(); });
    bar.appendChild(find);
    return bar;
  }
  // the live servers changed (a count, one more or one fewer): the chips and the TO list alone, never the lines
  function serversChanged() {
    if (!host || search) return;
    const h = hold();
    const bar = host.querySelector(".chatbar"); if (bar) bar.replaceWith(topBar());
    const sheet = host.querySelector(".csheetwrap"); if (sheet && picker) sheet.replaceWith(pickerSheet());
    give(h);
  }

  // ---------------------------------------------------------------- speaking (D306, D307, D313)
  function speakBar() {
    const bar = el("div", "chatspeak");
    const row = el("div", "speakrow");
    const as = el("button", "chip", ""); as.type = "button"; as.dataset.keep = "chatas";
    as.appendChild(document.createTextNode("As "));
    as.appendChild(el("b", voice === "server" ? "gtx" : "vtx", voice === "server" ? "[Server]" : "[Staff] " + me.mcName));
    as.appendChild(document.createTextNode(" ▾"));
    const target = el("button", "chip", (to === "network" ? "To the network" : "To " + to) + " ▾"); target.type = "button"; target.dataset.keep = "chatto";
    [as, target].forEach((b) => b.addEventListener("click", () => { picker = true; draw(); }));
    row.appendChild(as); row.appendChild(target);
    const count = el("span", "count" + (draft.length >= 200 ? "" : " hidden"), draft.length + " / " + MAX); row.appendChild(count);
    bar.appendChild(row);
    const form = el("form", "speakform");
    const input = el("input", "chatin"); input.type = "text"; input.maxLength = MAX; input.placeholder = "Say something…"; input.value = draft; input.dataset.keep = "chatin";
    input.addEventListener("input", () => { draft = input.value; count.textContent = draft.length + " / " + MAX; count.classList.toggle("hidden", draft.length < 200); });
    const send = el("button", "btn sm", "Send"); send.type = "submit";
    form.appendChild(input); form.appendChild(send);
    form.addEventListener("submit", (ev) => { ev.preventDefault(); say(); });
    bar.appendChild(form);
    return bar;
  }
  function pickerSheet() {
    const wrap = el("div", "csheetwrap");
    const dim = el("div", "csheetdim"); dim.addEventListener("click", () => { picker = false; draw(); }); wrap.appendChild(dim);
    const sh = el("div", "csheet");
    sh.appendChild(el("div", "sect", "SPEAK AS"));
    const opt = (on, label, cls, sub, locked, pick, key) => {
      const o = el("button", "opt" + (on ? " on" : "") + (locked ? " lock" : "")); o.type = "button"; o.dataset.keep = "chatopt:" + key;
      o.appendChild(el("span", "rb")); const g = el("div", "grow"); g.appendChild(el("b", cls, label)); g.appendChild(el("div", "sub", sub)); o.appendChild(g);
      o.addEventListener("click", pick); return o;
    };
    const mod = me.rank === "MODERATOR";
    sh.appendChild(opt(voice === "staff", "[Staff] " + me.mcName, "vtx", "Your own name with the staff tag", false, () => { voice = "staff"; draw(); }, "as:staff"));
    sh.appendChild(opt(voice === "server", "[Server]", "gtx", mod ? "The server's voice · Admin" : "The server's voice", mod, () => {
      if (mod) { toast("Speaking as [Server] is for Admins and Owners."); return; }
      voice = "server"; draw();
    }, "as:server"));
    sh.appendChild(el("div", "sect", "TO"));
    const total = (live.servers || []).reduce((a, s) => a + s.count, 0);
    sh.appendChild(opt(to === "network", "The network", "", "Every server, " + total + (total === 1 ? " player" : " players"), false, () => { to = "network"; draw(); }, "to:network"));
    (live.servers || []).forEach((s) => sh.appendChild(opt(to === s.server, s.server, "", s.count + (s.count === 1 ? " player" : " players"), false, () => { to = s.server; draw(); }, "to:" + s.server)));
    const done = el("button", "btn sm", "Done"); done.type = "button"; done.dataset.keep = "chatdone"; done.addEventListener("click", () => { picker = false; draw(); });
    sh.appendChild(done); wrap.appendChild(sh);
    return wrap;
  }
  async function say() {
    const text = draft.trim();
    if (!text) return;
    if (text.includes("§")) { toast("Plain text only: no § codes."); return; }
    if (to !== "network" && !(live.servers || []).some((s) => s.server === to)) { toast(to + " is not sending to the panel right now."); return; }
    const s = { id: "p" + Date.now(), at: Date.now(), voice, to, text, done: false, results: [], line: null };
    sending.push(s); draft = ""; draw(true);
    const r = await call("/api/chat/say", { voice, to, text }, true);
    s.done = true;
    s.results = r.results || [{ server: to === "network" ? "The panel" : to, ok: false, message: r.message || "Not sent." }];
    if (r.line) { s.line = r.line; add(r.line); }
    if (sending.length > 50) sending.shift();
    draw(true);
  }
  // one RETRY at a time for each server: a second tap while it is on its way does nothing (audit)
  async function retry(s, failed, key) {
    if (failed.retrying) return;
    failed.retrying = true; if (key) { key.disabled = true; key.textContent = "SENDING…"; }
    const r = await call("/api/chat/say", { voice: s.voice, to: failed.server, text: s.text, retry: true, kept: !!s.line }, true);
    failed.retrying = false;
    const got = (r.results || [])[0] || { server: failed.server, ok: false, message: r.message || "Not sent." };
    if (r.line && !s.line) { s.line = r.line; add(r.line); }
    s.results = s.results.map((x) => x === failed ? got : x);
    draw();
  }

  // ---------------------------------------------------------------- search (D312)
  function searchBar() {
    const bar = el("form", "chatsearch");
    const top = el("div", "speakrow");
    const q = el("input", "search"); q.type = "text"; q.placeholder = "Words…"; q.value = search.q; q.maxLength = 100; q.dataset.keep = "chatq";
    q.addEventListener("input", () => { search.q = q.value; });
    const close = el("button", "ico", "✕"); close.type = "button"; close.title = "Close the search"; close.dataset.keep = "chatclose";
    close.addEventListener("click", () => { search = null; draw(true); });
    top.appendChild(q); top.appendChild(close); bar.appendChild(top);
    const row = el("div", "speakrow");
    const p = el("input", "search small"); p.type = "text"; p.placeholder = "Player"; p.value = search.player; p.maxLength = 16; p.dataset.keep = "chatplayer";
    p.addEventListener("input", () => { search.player = p.value.trim(); });
    const sv = el("select", "small"); sv.dataset.keep = "chatsrv";
    [["", "Any server"], ...(live.servers || []).map((s) => [s.server, s.server]), ["network", "From the panel"]].forEach(([v, t]) => {
      const o = el("option", null, t); o.value = v; if (v === search.server) o.selected = true; sv.appendChild(o);
    });
    sv.addEventListener("change", () => { search.server = sv.value; });
    const days = el("select", "small"); days.dataset.keep = "chatdays";
    [["1", "Today"], ["7", "7 days"], ["30", "30 days"]].forEach(([v, t]) => {
      const o = el("option", null, t); o.value = v; if (v === search.days) o.selected = true; days.appendChild(o);
    });
    days.addEventListener("change", () => { search.days = days.value; });
    const go = el("button", "btn sm", "Search"); go.type = "submit"; go.disabled = search.busy; go.dataset.keep = "chatgo";
    [p, sv, days, go].forEach((x) => row.appendChild(x)); bar.appendChild(row);
    bar.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      if (search.busy) return;
      if (!search.q.trim() && !search.player && !search.server) { toast("Type some words, a player or pick a server."); return; }
      const asked = search;
      asked.busy = true; draw();
      const r = await call("/api/chat/search", { q: asked.q.trim(), player: asked.player, server: asked.server, days: asked.days });
      asked.busy = false; asked.results = r.ok ? r.lines : []; asked.error = r.ok ? "" : r.message;
      if (search === asked) draw();
    });
    return bar;
  }
  function marked(text, words) {
    const span = el("span"); const w = words.trim();
    if (!w) { span.textContent = text; return span; }
    const low = text.toLowerCase(), lw = w.toLowerCase(); let i = 0, j;
    while ((j = low.indexOf(lw, i)) >= 0) {
      span.appendChild(document.createTextNode(text.slice(i, j))); span.appendChild(el("mark", null, text.slice(j, j + w.length))); i = j + w.length;
    }
    span.appendChild(document.createTextNode(text.slice(i))); return span;
  }
  function results() {
    const box = el("div", "chatlines results");
    if (search.busy) { box.appendChild(el("div", "note", "Searching…")); return box; }
    if (search.error) { box.appendChild(el("div", "err", search.error)); return box; }
    if (!search.results) { box.appendChild(el("div", "note", "Search the last 30 days by words, a player, a server or the day.")); return box; }
    box.appendChild(el("div", "note", search.results.length + (search.results.length === 1 ? " line" : " lines") + ", newest first" + (search.results.length >= 100 ? " (the first 100)" : "")));
    search.results.forEach((l) => {
      const r = el("button", "res"); r.type = "button"; r.dataset.keep = "res:" + l.uid;
      r.appendChild(el("div", "w", dayName(l.at) + " " + time(l.at) + " · " + (l.kind === "player" ? l.server : l.server === "network" ? "the network" : l.server)));
      const t = el("div"); t.appendChild(el("b", l.kind === "staff" ? "vtx" : l.kind === "server" ? "gtx" : "", l.kind === "server" ? "[Server]" : l.kind === "staff" ? "[Staff] " + l.name : l.name));
      if (l.player) t.appendChild(Tags.chips(l.player, 2));
      t.appendChild(document.createTextNode(" ")); t.appendChild(marked(l.text, search.q)); r.appendChild(t);
      r.addEventListener("click", () => jump(l));
      box.appendChild(r);
    });
    return box;
  }
  async function jump(l) {
    const r = await call("/api/chat?around=" + l.at);
    if (!r.ok) { toast(r.message); return; }
    search = null; past = l.at; pastNew = 0; flash = l.uid; filter = "";
    lines = []; uids = new Set(); r.lines.forEach(add); more = false;
    draw();
  }
  async function backToNow() {
    const r = await call("/api/chat");
    if (!r.ok) { toast(r.message); return; }
    past = null; pastNew = 0; flash = null; lines = []; uids = new Set(); more = true;
    r.lines.forEach(add); more = r.more; draw(true);
  }

  // After the live feed came back: the newest lines again. When more was said meanwhile than one page holds, the stream
  // starts again from the newest page (older lines by scrolling up), so no gap is left hidden in it (audit).
  async function resync() {
    if (!started || past) return;
    const r = await call("/api/chat");
    if (!r.ok || !Array.isArray(r.lines)) return;
    const newest = lines[lines.length - 1];
    const joined = !newest || !r.more || !r.lines.length || r.lines.some((l) => uids.has(l.uid)) || Math.min(...r.lines.map((l) => l.at)) <= newest.at;
    if (!joined) { lines = []; uids = new Set(); }
    const fresh = r.lines.filter(add);
    if (!joined) more = true;
    if (!shown()) { fresh.forEach((l) => { if (isCall(l)) unread.calls++; else unread.any = true; }); if (fresh.length && typeof nav === "function") nav(); }
    if (!search) draw(!joined);
  }

  document.addEventListener("visibilitychange", () => { if (!document.hidden && host) { unread = { calls: 0, any: false }; if (typeof nav === "function") nav(); } });
  return { mount, unmount, incoming, badge, start, resync, serversChanged, redraw: () => draw(false) };
})();
