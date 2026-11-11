"use strict";
// The Staff tab (D265, D314 to D319, D327 to D329): the live staff room and messages to one staff member or all staff.
// On a phone ROOM and MESSAGES under one switch; on a computer side by side. Panel only: nothing reaches the game.
// Every text from staff is set with textContent.
const StaffView = (() => {
  const MAX = 1000, DELETE_MS = 5 * 60000;
  let lines = [], ids = new Set(), more = true, loading = false, started = false;
  let box = { messages: [], unread: 0, people: [] }, half = "room", host = null;
  let roomDraft = "", newMsg = null, replying = null, replyDraft = "", menuFor = null, readersOf = null;
  let unread = { calls: 0, any: false }, busy = false;
  // one request at a time from this tab: a second press of Send, Got it or Delete while the first is on its way does
  // nothing, and its key is off meanwhile (audit)
  async function once(key, path, body) {
    if (busy) return null;
    busy = true; if (key) key.disabled = true;
    const r = await call(path, body, true);
    busy = false; if (key) key.disabled = false;
    return r;
  }

  // ---------------------------------------------------------------- data
  async function start() {
    if (started) return; started = true;
    try {
      const r = await api("/api/room"); r.lines.forEach(add); more = r.more;
      await loadMessages();
    } catch (e) { started = false; }
    draw(true);
  }
  function add(l) {
    if (ids.has(l.id)) { lines = lines.map((x) => x.id === l.id ? l : x); return false; } // a deletion replaces the line
    ids.add(l.id); lines.push(l); lines.sort((a, b) => a.id - b.id); return true;
  }
  async function loadMessages() { const r = await call("/api/messages"); if (r.ok) box = r; if (typeof nav === "function") nav(); }
  const namesMe = (l) => !!me && l.staffId !== me.id && new RegExp("(^|[^A-Za-z0-9_])@?" + me.mcName.replace(/[^A-Za-z0-9_]/g, "") + "([^A-Za-z0-9_]|$)", "i").test(l.text);
  function room(l) {
    const fresh = add(l);
    if (!host) { if (fresh && !l.deleted && l.staffId !== me.id) { if (namesMe(l)) unread.calls++; else unread.any = true; if (typeof nav === "function") nav(); } return; }
    draw();
  }
  async function messagesChanged() { await loadMessages(); draw(); }
  async function older() {
    if (loading || !more || !lines.length) return;
    loading = true;
    try {
      const list = host && host.querySelector(".roomlines"), h0 = list ? list.scrollHeight : 0;
      const r = await api("/api/room?before=" + lines[0].id); r.lines.forEach(add); more = r.more; draw();
      const l2 = host && host.querySelector(".roomlines"); if (l2 && list) l2.scrollTop += l2.scrollHeight - h0;
    } catch (e) { /* the next scroll to the top asks again */ } finally { loading = false; }
  }

  // ---------------------------------------------------------------- where
  function badge() {
    const n = box.unread + (host ? 0 : unread.calls);
    return n > 0 ? String(n) : !host && unread.any ? "dot" : null;
  }
  function mount(el) { host = el; unread = { calls: 0, any: false }; start(); draw(true); if (typeof nav === "function") nav(); }
  function unmount(el) { if (!el || host === el) host = null; }

  // ---------------------------------------------------------------- drawing
  const time = (at) => new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  function when(at) {
    const d = new Date(at), t0 = new Date(new Date().toDateString()).getTime();
    if (at >= t0) return time(at);
    if (at >= t0 - 86400000) return "yesterday " + time(at);
    return d.toLocaleDateString([], { weekday: "short" }) + " " + time(at);
  }
  function dayName(at) {
    const t0 = new Date(new Date().toDateString()).getTime();
    if (at >= t0) return "Today"; if (at >= t0 - 86400000) return "Yesterday";
    return new Date(at).toLocaleDateString([], { weekday: "long", day: "numeric", month: "short" });
  }
  function face(name, size) {
    const p = (live.players || []).find((x) => x.name === name);
    if (p) { const i = el("img", "head"); i.src = "/api/head/" + p.id; i.alt = ""; i.width = size; i.height = size; i.onerror = () => i.replaceWith(plain(name, size)); return i; }
    return plain(name, size);
  }
  function plain(name, size) { const d = el("span", "face", (name || "?")[0].toUpperCase()); d.style.width = d.style.height = size + "px"; return d; }

  function draw(toBottom) {
    if (!host) return;
    const old = host.querySelector(".roomlines");
    const atBottom = !old || old.scrollHeight - old.scrollTop - old.clientHeight < 40, keep = old ? old.scrollTop : 0;
    const h = hold();
    host.textContent = "";
    const wrap = el("div", "stafftab");
    const seg = el("div", "seg staffseg");
    [["room", "Room"], ["msg", "Messages"]].forEach(([k, t]) => {
      const b = el("button", half === k ? "on" : "", t); b.type = "button"; b.dataset.keep = "half:" + k;
      if (k === "msg" && box.unread) b.appendChild(el("span", "n", String(box.unread)));
      b.addEventListener("click", () => { half = k; draw(true); }); seg.appendChild(b);
    });
    wrap.appendChild(seg);
    const cols = el("div", "staffcols " + half);
    cols.appendChild(roomHalf()); cols.appendChild(messageHalf());
    wrap.appendChild(cols);
    if (newMsg) wrap.appendChild(newSheet());
    host.appendChild(wrap);
    const list = host.querySelector(".roomlines");
    if (list) list.scrollTop = toBottom || atBottom ? list.scrollHeight : keep;
    give(h);
  }

  function roomHalf() {
    const half = el("div", "roomhalf");
    half.appendChild(el("div", "sect", "ROOM"));
    const list = el("div", "roomlines");
    list.appendChild(el("div", "day dim", more ? (loading ? "Loading older lines…" : "Scroll up for older lines") : "The start of the last 30 days"));
    let day = "";
    lines.forEach((l) => {
      const k = new Date(l.at).toDateString(); if (k !== day) { day = k; list.appendChild(el("div", "day", dayName(l.at))); }
      list.appendChild(roomLine(l));
    });
    list.addEventListener("scroll", () => { if (list.scrollTop < 40) older(); });
    half.appendChild(list);
    const f = el("form", "speakform");
    const i = el("input", "chatin"); i.type = "text"; i.maxLength = MAX; i.placeholder = "Say something to the staff…"; i.value = roomDraft; i.dataset.keep = "room";
    i.addEventListener("input", () => { roomDraft = i.value; });
    const s = el("button", "btn sm", "Send"); s.type = "submit"; s.disabled = busy;
    f.appendChild(i); f.appendChild(s);
    f.addEventListener("submit", async (ev) => {
      ev.preventDefault(); const text = roomDraft.trim(); if (!text) return;
      const r = await once(s, "/api/room/say", { text });
      if (!r) return;
      if (!r.ok) { note(r.message); return; }
      roomDraft = ""; add(r.line); draw(true);
    });
    half.appendChild(f);
    return half;
  }
  function deletable(staffId, at) { return me.rank === "OWNER" || (staffId === me.id && Date.now() - at < DELETE_MS); }
  function roomLine(l) {
    const row = el("div", "rl" + (menuFor === l.id ? " picked" : ""));
    row.appendChild(face(l.name, 24));
    const g = el("div", "grow");
    const top = el("div"); top.appendChild(el("b", l.staffId === me.id ? "vtx" : "", l.name)); top.appendChild(document.createTextNode(" "));
    top.appendChild(el("span", "rk " + l.rank, l.rankLabel)); top.appendChild(el("span", "t", " " + time(l.at)));
    g.appendChild(top);
    if (l.deleted) g.appendChild(el("div", "gone", "deleted"));
    else {
      const t = el("div", "rtext");
      const at = new RegExp("(@" + me.mcName.replace(/[^A-Za-z0-9_]/g, "") + ")", "i");
      l.text.split(at).forEach((part) => t.appendChild(at.test(part) && part.startsWith("@") ? el("b", "vtx", part) : document.createTextNode(part)));
      g.appendChild(t);
    }
    row.appendChild(g);
    if (!l.deleted && deletable(l.staffId, l.at)) {
      // a long press (or a right click) on a line you may delete (D327); from the keyboard its ⋯ key
      const dots = el("button", "rdots", "⋯"); dots.type = "button"; dots.title = "Delete"; dots.dataset.keep = "rdots:" + l.id;
      dots.addEventListener("click", () => { menuFor = l.id; draw(); const d = host && host.querySelector('[data-keep="rldel"]'); if (d) d.focus(); });
      row.appendChild(dots);
      let timer = 0;
      row.addEventListener("pointerdown", () => { timer = setTimeout(() => { menuFor = l.id; draw(); }, 500); });
      ["pointerup", "pointerleave", "pointercancel"].forEach((e) => row.addEventListener(e, () => clearTimeout(timer)));
      row.addEventListener("contextmenu", (ev) => { ev.preventDefault(); menuFor = l.id; draw(); });
    }
    if (menuFor === l.id) {
      const m = el("div", "linemenu");
      const left = l.staffId === me.id && me.rank !== "OWNER" ? " · " + Math.max(1, Math.ceil((DELETE_MS - (Date.now() - l.at)) / 60000)) + " min left" : "";
      const d = el("button", "danger", "Delete" + left); d.type = "button"; d.dataset.keep = "rldel"; d.disabled = busy;
      d.addEventListener("click", async () => {
        const r = await once(d, "/api/room/delete", { id: l.id });
        if (!r) return;
        menuFor = null; if (!r.ok) note(r.message); else add(r.line); draw();
      });
      const c = el("button", null, "Cancel"); c.type = "button"; c.dataset.keep = "rlcancel"; c.addEventListener("click", () => { menuFor = null; draw(); });
      m.appendChild(d); m.appendChild(c); row.appendChild(m);
    }
    return row;
  }

  function messageHalf() {
    const half = el("div", "msghalf");
    const head = el("div", "sect"); head.appendChild(el("span", null, "MESSAGES" + (box.unread ? " · " + box.unread + " UNREAD" : "")));
    half.appendChild(head);
    const nb = el("button", "btn sm", "+ New message"); nb.type = "button"; nb.dataset.keep = "newmsg";
    nb.addEventListener("click", () => { newMsg = { to: "all", text: "" }; draw(); });
    half.appendChild(nb);
    const list = el("div", "msglist");
    if (!box.messages.length) list.appendChild(el("div", "note", "No messages in the last 30 days."));
    box.messages.forEach((m) => list.appendChild(card(m)));
    half.appendChild(list);
    return half;
  }
  function card(m) {
    const c = el("div", "msg" + (!m.read && !m.deleted ? " new" : ""));
    const hd = el("div", "mhd"); hd.appendChild(face(m.from, 20));
    hd.appendChild(el("b", m.mine ? "vtx" : "", m.from)); hd.appendChild(el("span", "rk " + m.fromRank, m.fromRankLabel));
    hd.appendChild(el("span", "to", m.to === "all" ? "to all staff" : m.toId === me.id ? "to you" : "to " + m.to));
    hd.appendChild(el("span", "t", when(m.at)));
    c.appendChild(hd);
    c.appendChild(m.deleted ? el("div", "gone", "deleted") : el("div", "mtext", m.text));
    if (m.deleted) return c;
    const foot = el("div", "mfoot");
    if (m.mine) {
      if (m.to === "all") {
        const r = el("button", "linkish", "Read by " + m.readers.length + " of " + m.of + (m.readers.length ? " ›" : "")); r.type = "button"; r.dataset.keep = "readers:" + m.id;
        r.addEventListener("click", () => { readersOf = readersOf === m.id ? null : m.id; draw(); });
        foot.appendChild(r);
      } else foot.appendChild(el("span", null, m.theyReadAt ? "Read " + when(m.theyReadAt) : "Not read yet"));
    } else if (m.read) foot.appendChild(el("span", null, "You read it " + when(m.readAt)));
    if (deletable(m.fromId, m.at)) {
      let armed = 0; const d = el("button", "linkish danger", "Delete"); d.type = "button"; d.dataset.keep = "mdel:" + m.id;
      d.addEventListener("click", async () => {
        if (busy) return;
        if (Date.now() - armed > 3000) { armed = Date.now(); d.textContent = "Sure? Delete"; unarm(d, 3000, "Delete"); return; }
        const r = await once(d, "/api/messages/delete", { id: m.id });
        if (!r) return;
        if (!r.ok) note(r.message); await loadMessages(); draw();
      });
      foot.appendChild(d);
    }
    if (foot.children.length) c.appendChild(foot);
    if (readersOf === m.id && m.readers.length) c.appendChild(el("div", "sub", "Read by " + m.readers.join(", ")));
    if (!m.mine && !m.read) {
      if (replying === m.id) {
        const f = el("form", "speakform");
        const i = el("input", "chatin"); i.type = "text"; i.maxLength = MAX; i.placeholder = "Reply to " + m.from + "…"; i.value = replyDraft; i.dataset.keep = "reply";
        i.addEventListener("input", () => { replyDraft = i.value; });
        const s = el("button", "btn sm", "Send"); s.type = "submit"; s.disabled = busy; f.appendChild(i); f.appendChild(s);
        f.addEventListener("submit", async (ev) => {
          ev.preventDefault(); if (!replyDraft.trim()) return;
          const r = await once(s, "/api/messages/send", { to: String(m.fromId), text: replyDraft, replyTo: m.id });
          if (!r) return;
          if (!r.ok) { note(r.message); return; }
          replying = null; replyDraft = ""; await loadMessages(); draw();
        });
        c.appendChild(f); c.appendChild(el("div", "sub", "Replying to " + m.from + " · marked read once sent"));
      } else {
        const keys = el("div", "row2");
        const rp = el("button", "btn sm ghost", "Reply"); rp.type = "button"; rp.dataset.keep = "reply:" + m.id;
        rp.addEventListener("click", () => { replying = m.id; replyDraft = ""; draw(); const i = host && host.querySelector('[data-keep="reply"]'); if (i) i.focus(); });
        const ok = el("button", "btn sm", "Got it"); ok.type = "button"; ok.dataset.keep = "gotit:" + m.id; ok.disabled = busy;
        ok.addEventListener("click", async () => {
          const r = await once(ok, "/api/messages/read", { id: m.id });
          if (!r) return;
          if (!r.ok) note(r.message); await loadMessages(); draw();
        });
        keys.appendChild(rp); keys.appendChild(ok); c.appendChild(keys);
      }
    }
    return c;
  }
  function newSheet() {
    const wrap = el("div", "csheetwrap");
    const dim = el("div", "csheetdim"); dim.addEventListener("click", () => { newMsg = null; draw(); }); wrap.appendChild(dim);
    const sh = el("form", "csheet");
    sh.appendChild(el("div", "sect", "NEW MESSAGE · TO"));
    const to = el("select"); to.dataset.keep = "to";
    [["all", "All staff"], ...box.people.map((p) => [String(p.id), p.name + " (" + p.rankLabel + ")"])].forEach(([v, t]) => {
      const o = el("option", null, t); o.value = v; if (v === newMsg.to) o.selected = true; to.appendChild(o);
    });
    to.addEventListener("change", () => { newMsg.to = to.value; });
    sh.appendChild(to);
    const ta = el("textarea", "ta"); ta.maxLength = MAX; ta.rows = 5; ta.value = newMsg.text; ta.placeholder = "Your message…"; ta.dataset.keep = "msg";
    const count = el("div", "count", newMsg.text.length + " / " + MAX);
    ta.addEventListener("input", () => { newMsg.text = ta.value; count.textContent = ta.value.length + " / " + MAX; });
    sh.appendChild(ta); sh.appendChild(count);
    sh.appendChild(el("div", "sub", "They see it now if they are on the panel, otherwise when they next open it."));
    const row = el("div", "row2");
    const cancel = el("button", "btn sm ghost", "Cancel"); cancel.type = "button"; cancel.addEventListener("click", () => { newMsg = null; draw(); });
    const send = el("button", "btn sm", "Send"); send.type = "submit"; send.disabled = busy;
    row.appendChild(cancel); row.appendChild(send); sh.appendChild(row);
    sh.addEventListener("submit", async (ev) => {
      ev.preventDefault(); if (!newMsg.text.trim()) return;
      const r = await once(send, "/api/messages/send", { to: newMsg.to, text: newMsg.text });
      if (!r) return;
      if (!r.ok) { note(r.message); return; }
      newMsg = null; half = "msg"; await loadMessages(); draw();
    });
    wrap.appendChild(sh); return wrap;
  }
  function note(text) { const t = el("div", "toast", text); document.body.appendChild(t); setTimeout(() => t.remove(), 2600); }

  // After the live feed came back: the room's newest lines and the messages again. When more was said meanwhile than one
  // page holds, the room starts again from the newest page (older lines by scrolling up), so no gap is hidden (audit).
  async function resync() {
    if (!started) return;
    const r = await call("/api/room");
    if (!r.ok || !Array.isArray(r.lines)) return;
    const joined = !lines.length || !r.more || !r.lines.length || r.lines.some((l) => ids.has(l.id)) || Math.min(...r.lines.map((l) => l.id)) <= lines[lines.length - 1].id;
    if (!joined) { lines = []; ids = new Set(); more = true; }
    r.lines.forEach(add);
    await loadMessages();
    draw(!joined);
  }
  return { mount, unmount, room, messagesChanged, badge, start, resync };
})();
