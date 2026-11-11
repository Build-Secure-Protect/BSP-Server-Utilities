"use strict";
// Network bans (SU-44; D255, D350 to D359, D367 to D370, D375, D376). BAN in a player's sheet or card, for Admins and
// Owners (Moderators see it dimmed with "Admin"; staff are banned by the Owner alone, so an Admin sees "Owner" on a
// staff member): the screen the player will get, the length (1 hour, 1 day, 7 days, 30 days, custom days and hours,
// permanent), the reason they read, a note only staff read, earlier bans in gold. BAN → SURE? → LAST CHECK with their
// name typed. More › Bans: ACTIVE and PAST with a search, + BAN for any name, UNBAN (SURE?) and EDIT.
const Bans = (() => {
  const LENS = [["1h", "1 hour"], ["1d", "1 day"], ["7d", "7 days"], ["30d", "30 days"], ["custom", "Custom"], ["perm", "Permanent"]];
  const MS = { "1h": 3600000, "1d": 86400000, "7d": 7 * 86400000, "30d": 30 * 86400000, perm: 0 };
  let openFor = null, form = null, armed = 0, dialog = null, answer = null, busy = false;
  const history = new Map(); // player id -> list (or "loading")
  const page = { which: "active", q: "", list: null, adding: null, addName: "", edit: null, armed: {}, busy: {}, msg: null, finding: false };
  const isAdmin = () => typeof me !== "undefined" && me && me.rank !== "MODERATOR";
  const isOwner = () => typeof me !== "undefined" && me && me.rank === "OWNER";
  const words = (ms) => { const m = Math.max(1, Math.round(ms / 60000)), h = Math.floor(m / 60), d = Math.floor(h / 24);
    return d ? d + (d === 1 ? " day" : " days") + (h % 24 ? " " + (h % 24) + (h % 24 === 1 ? " hour" : " hours") : "") : h ? h + (h === 1 ? " hour" : " hours") : m + (m === 1 ? " minute" : " minutes"); };
  const when = (at) => new Date(at).toLocaleString([], { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
  const utc = (at) => { const d = new Date(at); return d.getUTCDate() + " " + d.toLocaleString("en", { month: "short", timeZone: "UTC" }) + " " + d.getUTCFullYear() + " "
    + String(d.getUTCHours()).padStart(2, "0") + ":" + String(d.getUTCMinutes()).padStart(2, "0"); };
  function lengthMs(f) {
    if (f.length === "custom") { const ms = (Number(f.days) || 0) * 86400000 + (Number(f.hours) || 0) * 3600000; return ms >= 3600000 ? ms : -2; }
    if (f.length === "keep") return -1;
    return f.length in MS ? MS[f.length] : -2;
  }
  const lenLabel = (f) => f.length === "custom" ? words(lengthMs(f)) : f.length === "perm" ? "good" : (LENS.find((l) => l[0] === f.length) || ["", "?"])[1];

  const levels = new Map(); // the server's word on a player: 0 not staff, 1 staff, 2 an Owner (D355)
  async function loadHistory(id, redraw) {
    if (history.get(id) === "loading") return;
    history.set(id, "loading");
    const r = await call("/api/ban/history", { player: id });
    history.set(id, r.ok ? r.bans : []); levels.set(id, r.ok ? r.staff || 0 : 0);
    redraw();
  }

  // ---------------------------------------------------------------- the BAN key and the form in the sheet
  function key(p) {
    const staff = !!p.role;
    const why = !isAdmin() ? "Admin" : staff && !isOwner() ? "Owner" : "";
    const b = el("button", "key danger" + (why ? " locked-item" : "") + (openFor === p.id ? " on" : ""), "Ban"); b.type = "button"; b.dataset.keep = "k:ban";
    if (why) b.appendChild(el("small", "keynote", why));
    b.addEventListener("click", () => {
      if (why) { alert(why === "Admin" ? "Bans are for Admins and Owners." : p.name + " is staff: only the Owner bans staff."); return; }
      openFor = openFor === p.id ? null : p.id; form = null; answer = null; App.refresh();
    });
    return b;
  }
  function box(p) {
    if (openFor !== p.id) return null;
    return formEl({ id: p.id, name: p.name }, () => App.refresh(), null);
  }

  // the screen the player will get (D354, D369)
  function preview(f, name) {
    const d = el("div", "disc");
    d.appendChild(el("span", "gy", "Failed to connect to the server"));
    d.appendChild(el("span", "rd2", "You are banned from the Build Secure Protect network."));
    d.appendChild(el("span", "prevreason", (f.reason || "").trim() || "(the reason)"));
    const ms = lengthMs(f);
    d.appendChild(el("span", "gy", f.length === "perm" ? "This ban is permanent." : ms > 0 ? "Ends " + utc(Date.now() + ms) + " UTC (in " + words(ms) + ")" : f.length === "keep" ? "Ends as before" : "…"));
    return d;
  }

  // the form: a new ban for a player (sheet or More › Bans), or an edit of a ban (More › Bans)
  function formEl(target, redraw, editing) {
    if (!form || form.for !== (editing ? "e" + editing.id : target.id)) {
      form = { for: editing ? "e" + editing.id : target.id, length: editing ? "keep" : "7d", days: "", hours: "", reason: editing ? editing.reason : "", note: editing ? editing.note : "" };
    }
    const f = form;
    const w = el("div", "banbox");
    if (!editing) w.appendChild(preview(f, target.name));
    w.appendChild(el("div", "lbl", editing ? "Change the end (from now)" : "How long"));
    const lens = el("div", "lens");
    (editing ? [["keep", "Keep the end"]].concat(LENS) : LENS).forEach(([k, t]) => {
      const b = el("button", k === f.length ? "on" : "", t); b.type = "button"; b.dataset.keep = "len:" + k; b.addEventListener("click", () => { f.length = k; armed = 0; redraw(); }); lens.appendChild(b);
    });
    w.appendChild(lens);
    if (f.length === "custom") {
      const two = el("div", "two");
      [["days", "Days"], ["hours", "Hours"]].forEach(([k, t]) => { const l = el("label", "fld", t); const i = el("input", "bannum ban" + k); i.type = "text"; i.inputMode = "numeric"; i.value = f[k]; i.dataset.keep = "ban" + k;
        i.addEventListener("input", () => { f[k] = i.value.replace(/[^0-9]/g, ""); }); l.appendChild(i); two.appendChild(l); });
      w.appendChild(two);
    }
    const rl = el("div", "lbl"); rl.appendChild(el("span", null, "Reason (they see it)")); const cnt = el("span", "mono", f.reason.length + "/160"); rl.appendChild(cnt); w.appendChild(rl);
    const r = el("textarea", "ta banreason"); r.rows = 2; r.maxLength = 160; r.value = f.reason; r.dataset.keep = "banreason:" + f.for;
    r.addEventListener("input", () => {
      f.reason = r.value; cnt.textContent = r.value.length + "/160";
      const pv = w.querySelector(".prevreason"); if (pv) pv.textContent = r.value.trim() || "(the reason)"; // the preview follows as they type
    });
    w.appendChild(r);
    w.appendChild(el("div", "lbl", "Note for staff (optional, never shown to them)"));
    const n = el("textarea", "ta note bannote"); n.rows = 2; n.maxLength = 500; n.value = f.note; n.dataset.keep = "bannote:" + f.for; n.addEventListener("input", () => { f.note = n.value; }); w.appendChild(n);
    if (!editing) {
      const h = history.get(target.id);
      if (h === undefined) loadHistory(target.id, redraw);
      if (Array.isArray(h) && h.length) {
        w.appendChild(el("div", "hist", "Banned " + h.length + (h.length === 1 ? " time" : " times") + " before: " + h.slice(0, 3).map((x) =>
          (x.until ? words(x.until - x.at) : "permanent") + " (" + new Date(x.at).toLocaleDateString([], { month: "short" }) + ", " + x.reason + ")").join(", ") + "."));
      }
    }
    if (answer && answer.for === f.for) w.appendChild(el("div", answer.ok ? "okm" : "err", answer.message));
    const level = levels.get(target.id) || 0;
    const barred = !editing && (level === 2 ? target.name + " is an Owner of the panel: an Owner cannot be banned from here."
      : level === 1 && !isOwner() ? target.name + " is staff: only the Owner bans staff." : "");
    if (barred) w.appendChild(el("div", "err", barred)); // shown before the two checks, as the server would refuse it (audit)
    // read when BAN is pressed, not when the form was drawn: typing changes the form without drawing it again (audit)
    const ready = () => f.reason.trim() && lengthMs(f) >= -1 && (editing || lengthMs(f) >= 0);
    const left = 3000 - (Date.now() - armed), plain = editing ? "Save changes" : "Ban " + target.name, plainCls = "btn sm " + (editing ? "" : "red");
    const sureText = () => (f.length === "perm" ? "Sure? Ban " + target.name + " for good" : "Sure? Ban " + target.name + " for " + lenLabel(f));
    const go = el("button", !editing && left > 0 ? "btn sm sure" : plainCls, !editing && left > 0 ? sureText() : plain);
    go.type = "button"; go.disabled = busy || !!barred; go.dataset.keep = "bango:" + f.for;
    if (!editing) unarm(go, left, plain, plainCls);
    go.addEventListener("click", async () => {
      if (busy) return;
      if (!ready()) { alert(lengthMs(f) === -2 ? "Choose how long (at least an hour of your own)." : "Write the reason they will see."); return; }
      if (editing) { await send("/api/ban/edit", { ban: editing.id, length: f.length, days: f.days, hours: f.hours, reason: f.reason, note: f.note }, f.for, redraw); return; }
      if (Date.now() - armed > 3000) { armed = Date.now(); go.textContent = sureText(); go.className = "btn sm sure"; unarm(go, 3000, plain, plainCls); return; } // the first check: SURE? (D368), focus kept on it
      armed = 0; dialog = { target, f, typed: "", redraw }; drawDialog();
    });
    w.appendChild(go);
    return w;
  }

  async function send(path, body, key, redraw) {
    if (busy) return;
    busy = true; redraw(); // its key off while it runs
    const r = await call(path, body, true);
    busy = false; answer = { for: key, ok: r.ok, message: r.message };
    if (r.ok) { history.clear(); page.list = null; if (path.endsWith("/edit")) { page.edit = null; page.msg = answer; } }
    redraw();
  }

  // the second check (D368): the player's name typed out before the red BAN wakes up
  function drawDialog() {
    let w = $("bandlg"); if (w) w.remove();
    if (!dialog) return;
    const d = dialog;
    const dim = el("div", "pdim top"); dim.id = "bandlg";
    const box2 = el("div", "dlg");
    box2.appendChild(el("h3", null, "LAST CHECK"));
    box2.appendChild(el("div", null, "Ban " + d.target.name + " from the whole network " + (d.f.length === "perm" ? "for good" : "for " + lenLabel(d.f)) + "? If online, they leave the network now."));
    box2.appendChild(el("div", "lbl", "Type their name to go on"));
    const i = el("input", "banconfirm"); i.type = "text"; i.autocapitalize = "off"; i.autocomplete = "off"; i.spellcheck = false; i.value = d.typed;
    const go = el("button", "btn sm red", "Ban " + d.target.name); go.type = "button"; go.disabled = d.typed.toLowerCase() !== d.target.name.toLowerCase();
    i.addEventListener("input", () => { d.typed = i.value.trim(); go.disabled = d.typed.toLowerCase() !== d.target.name.toLowerCase(); });
    go.addEventListener("click", async () => {
      dialog = null; drawDialog();
      await send("/api/ban", { player: d.target.id, length: d.f.length, days: d.f.days, hours: d.f.hours, reason: d.f.reason, note: d.f.note, confirm: d.typed }, d.f.for, d.redraw);
    });
    const cancel = el("button", "btn sm ghost", "Cancel"); cancel.type = "button"; cancel.addEventListener("click", () => { dialog = null; drawDialog(); });
    box2.appendChild(i); box2.appendChild(go); box2.appendChild(cancel);
    dim.appendChild(box2); document.body.appendChild(dim);
    i.focus();
  }

  // ---------------------------------------------------------------- More › Bans
  async function load() {
    page.list = "loading";
    const r = await call("/api/bans", { which: page.which, q: page.q });
    page.list = r.ok ? r : { bans: [], now: Date.now(), error: r.message };
    App.refreshMore();
  }
  function view(main) {
    main.appendChild(el("h2", null, "Bans"));
    const seg = el("div", "seg");
    [["active", "Active"], ["past", "Past"]].forEach(([k, t]) => { const b = el("button", page.which === k ? "on" : "", t); b.type = "button"; b.dataset.keep = "bseg:" + k; b.addEventListener("click", () => { page.which = k; page.list = null; App.refreshMore(); }); seg.appendChild(b); });
    main.appendChild(seg);
    const bar = el("div", "banbar");
    const s = el("input", "search banq"); s.type = "search"; s.placeholder = "Find a ban by name"; s.value = page.q; s.dataset.keep = "banq";
    s.addEventListener("change", () => { page.q = s.value.trim(); page.list = null; App.refreshMore(); });
    bar.appendChild(s);
    const add = el("button", "btn sm red" + (isAdmin() ? "" : " locked-item"), "+ Ban"); add.type = "button"; add.dataset.keep = "banadd";
    add.addEventListener("click", () => { if (!isAdmin()) { alert("Bans are for Admins and Owners."); return; } page.adding = page.adding ? null : { id: null, name: "" }; App.refreshMore(); });
    bar.appendChild(add); main.appendChild(bar);
    if (page.adding) main.appendChild(addBox());
    if (page.msg) main.appendChild(el("div", page.msg.ok ? "okm" : "err", page.msg.message));
    if (page.list === null) load();
    if (!page.list || page.list === "loading") { main.appendChild(el("div", "note", "Reading the bans…")); return; }
    const list = page.list.bans;
    if (page.list.error) {
      main.appendChild(el("div", "err", page.list.error));
      const again = el("button", "btn sm ghost back", "Try again"); again.type = "button"; again.addEventListener("click", () => { page.list = null; App.refreshMore(); }); main.appendChild(again);
      return;
    }
    if (!list.length) main.appendChild(el("div", "note", page.which === "active" ? "Nobody is banned." : "No past bans."));
    list.forEach((b) => main.appendChild(card(b, page.list.now)));
  }
  function addBox() {
    const w = el("div", "banadd");
    if (!page.adding.id) {
      const f = el("form", "gsearch"); const i = el("input", "search"); i.type = "text"; i.placeholder = "Their Minecraft name"; i.value = page.addName; i.maxLength = 16; i.dataset.keep = "banname";
      i.autocapitalize = "off"; i.spellcheck = false;
      const go = el("button", "btn sm", "Next"); go.type = "submit"; go.disabled = page.finding;
      f.appendChild(i); f.appendChild(go);
      f.addEventListener("submit", async (e) => {
        e.preventDefault(); if (page.finding) return;
        page.addName = i.value.trim();
        page.finding = true; go.disabled = true;
        const r = await call("/api/players/find", { q: page.addName });
        page.finding = false; go.disabled = false;
        if (!r.ok && r.failed) { alert(r.message); return; }
        const hit = r.ok ? r.players.find((x) => x.name.toLowerCase() === page.addName.toLowerCase()) : null;
        if (!hit) { alert("Nobody called " + page.addName + " has played on the network."); return; }
        page.adding = { id: hit.id, name: hit.name }; form = null; App.refreshMore();
      });
      w.appendChild(f); return w;
    }
    w.appendChild(el("div", "nm", "Ban " + page.adding.name));
    w.appendChild(formEl(page.adding, () => App.refreshMore(), null));
    return w;
  }
  function card(b, now) {
    const c = el("div", "bcard");
    const t = el("div", "bt"); t.appendChild(el("div", "nm grow", b.name)); t.appendChild(Tags.chips(b.player, 2));
    const left = b.until ? b.until - now : 0;
    t.appendChild(el("span", "ends" + (b.until ? "" : " perm"), b.active ? (b.until ? "ends in " + words(left) : "permanent") : b.liftedAt ? "lifted" : "ended"));
    c.appendChild(t);
    c.appendChild(el("div", null, b.reason));
    if (b.note) c.appendChild(el("div", "sub wrap", "Staff note: " + b.note));
    c.appendChild(el("div", "sub", "by " + b.by + " · " + when(b.at) + (b.editedAt ? " · edited " + when(b.editedAt) : "")
      + (b.liftedAt ? " · lifted " + when(b.liftedAt) + " by " + b.liftedBy : !b.active && b.until ? " · ended " + when(b.until) : "")));
    if (b.active) {
      const keys = el("div", "kk");
      const left = 3000 - (Date.now() - (page.armed[b.id] || 0)), working = !!page.busy[b.id];
      const ownerOnly = (b.staff || 0) >= 1 && !isOwner(); // a staff member's ban: changed and lifted by the Owner alone (D355, D427)
      const may = isAdmin() && !ownerOnly, whyNot = ownerOnly ? b.name + " is staff: only the Owner lifts or changes a ban on staff." : "Bans are for Admins and Owners.";
      const un = el("button", may ? "u" : "locked-item", working ? "Unbanning…" : left > 0 ? "Sure? Unban " + b.name : ownerOnly ? "Unban (Owner)" : "Unban"); un.type = "button"; un.dataset.keep = "unban:" + b.id;
      un.disabled = working; // a redraw while it runs keeps the key off (audit)
      if (!working) unarm(un, left, "Unban");
      un.addEventListener("click", async () => {
        if (!may) { alert(whyNot); return; }
        if (page.busy[b.id]) return;
        if (Date.now() - (page.armed[b.id] || 0) > 3000) { page.armed[b.id] = Date.now(); un.textContent = "Sure? Unban " + b.name; unarm(un, 3000, "Unban"); return; } // SURE? (D356), focus kept on it
        page.armed[b.id] = 0; page.busy[b.id] = true; un.disabled = true; un.textContent = "Unbanning…";
        const r = await call("/api/ban/lift", { ban: b.id }, true);
        page.busy[b.id] = false;
        page.msg = r; page.list = null; history.clear(); App.refreshMore();
      });
      const ed = el("button", may ? "" : "locked-item", page.edit === b.id ? "Close" : ownerOnly ? "Edit (Owner)" : "Edit"); ed.type = "button"; ed.dataset.keep = "banedit:" + b.id;
      ed.addEventListener("click", () => { if (!may) { alert(whyNot); return; } page.edit = page.edit === b.id ? null : b.id; form = null; App.refreshMore(); });
      keys.appendChild(un); keys.appendChild(ed); c.appendChild(keys);
      if (page.edit === b.id) c.appendChild(formEl({ id: b.player, name: b.name }, () => App.refreshMore(), b));
    }
    return c;
  }
  function close() { openFor = null; form = null; answer = null; }
  /** The answer to the newest ban of this player from their sheet: kept for the card once they have left (audit). */
  function answerFor(id) { return answer && answer.for === id ? answer : null; }
  return { key, box, view, close, answerFor, reset: () => { page.list = null; page.adding = null; page.msg = null; page.edit = null; } };
})();
