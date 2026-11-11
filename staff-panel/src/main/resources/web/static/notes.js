"use strict";
// Tags and player notes (D266, D267, D320 to D326, D330, D331). Tags: a list admins keep with colours, shown beside names
// everywhere (two on a row, then "+1"; all in the sheet); any staff member puts one on or takes it off, or types a new one,
// which joins the list grey until an admin colours it. Notes: a section of the player's sheet, seen by all staff; the author
// or an Admin edits ("edited") or deletes (a second tap). Every text from staff is set with textContent.
const Tags = (() => {
  let list = [], byPlayer = {}, picker = null;
  async function load() { try { const r = await api("/api/tags"); if (r.ok) { list = r.tags; byPlayer = r.players; } } catch (e) { /* shown empty */ } }
  const of = (id) => (byPlayer[id] || []).map((t) => list.find((x) => x.id === t)).filter(Boolean);
  function chip(t) {
    const c = el("span", "tg", t.name); c.style.background = t.colour + "22"; c.style.color = t.colour; c.style.borderColor = t.colour + "66"; return c;
  }
  function chips(id, max) {
    const span = el("span", "tgs"); const ts = of(id);
    (max ? ts.slice(0, max) : ts).forEach((t) => span.appendChild(chip(t)));
    if (max && ts.length > max) span.appendChild(el("span", "tg more", "+" + (ts.length - max)));
    return span;
  }
  async function changed() {
    await load();
    if (typeof App !== "undefined") App.refresh();
    if (typeof ChatView !== "undefined") ChatView.redraw();
    if (picker) drawPicker();
    if (typeof TagsPage !== "undefined") TagsPage.redraw();
  }

  // ---------------------------------------------------------------- the picker (D320): tick from the list or type a new one
  function open(player) { picker = { id: player.id, name: player.name, q: "" }; drawPicker(); }
  function closePicker() { picker = null; const w = $("tagpicker"); if (w) w.remove(); }
  function drawPicker() {
    let w = $("tagpicker"); const focused = w && document.activeElement && document.activeElement.classList.contains("tagq");
    if (w) w.remove();
    if (!picker) return;
    w = el("div", "csheetwrap"); w.id = "tagpicker";
    const dim = el("div", "csheetdim"); dim.addEventListener("click", closePicker); w.appendChild(dim);
    const sh = el("div", "csheet");
    sh.appendChild(el("div", "sect", "TAGS FOR " + picker.name.toUpperCase()));
    const q = el("input", "tagq search"); q.type = "text"; q.maxLength = 24; q.placeholder = "Find or type a tag…"; q.value = picker.q;
    q.addEventListener("input", () => { picker.q = q.value; drawPicker(); });
    sh.appendChild(q);
    const mine = new Set(byPlayer[picker.id] || []), text = picker.q.trim().toLowerCase();
    const opt = (t, on, sub) => {
      const o = el("button", "opt" + (on ? " on" : "")); o.type = "button";
      o.appendChild(el("span", "chk" + (on ? " on" : ""), on ? "✓" : ""));
      const g = el("div", "grow"); g.appendChild(chip(t)); if (sub) g.appendChild(el("div", "sub", sub)); o.appendChild(g);
      o.addEventListener("click", async () => {
        const r = await api(on ? "/api/tags/take" : "/api/tags/put", { player: picker.id, name: picker.name, tag: String(t.id) });
        if (!r.ok) toast(r.message); else await changed();
      });
      return o;
    };
    const matching = list.filter((t) => !text || t.name.toLowerCase().includes(text));
    if (text && !list.some((t) => t.name.toLowerCase() === text)) {
      const o = el("button", "opt dashed"); o.type = "button"; o.appendChild(el("span", "chk"));
      const g = el("div", "grow"); g.appendChild(document.createTextNode("Create ")); g.appendChild(el("b", null, "“" + picker.q.trim() + "”"));
      g.appendChild(el("div", "sub", "A new tag, grey until an admin gives it a colour")); o.appendChild(g);
      o.addEventListener("click", async () => {
        const r = await api("/api/tags/put", { player: picker.id, name: picker.name, typed: picker.q.trim() });
        if (!r.ok) toast(r.message); else { picker.q = ""; await changed(); }
      });
      sh.appendChild(o);
    }
    const on = matching.filter((t) => mine.has(t.id)), off = matching.filter((t) => !mine.has(t.id));
    if (on.length) { sh.appendChild(el("div", "sect", "ON " + picker.name.toUpperCase())); on.forEach((t) => sh.appendChild(opt(t, true, t.typed ? "typed by " + t.by + ", waiting for a colour" : ""))); }
    if (off.length) { sh.appendChild(el("div", "sect", "THE LIST")); off.forEach((t) => sh.appendChild(opt(t, false, t.count + (t.count === 1 ? " player" : " players")))); }
    const done = el("button", "btn sm", "Done"); done.type = "button"; done.addEventListener("click", closePicker); sh.appendChild(done);
    w.appendChild(sh); document.body.appendChild(w);
    if (focused) { const i = w.querySelector(".tagq"); i.focus(); i.setSelectionRange(i.value.length, i.value.length); }
  }
  function toast(text) { const t = el("div", "toast", text); document.body.appendChild(t); setTimeout(() => t.remove(), 2600); }
  return { load, of, chip, chips, changed, open, list: () => list };
})();

const Notes = (() => {
  const cache = new Map(), drafts = new Map(); let editing = null, editDraft = "", menu = null, armed = 0;
  async function fetchFor(id) {
    if (cache.get(id) === "loading") return;
    cache.set(id, "loading");
    try { const r = await api("/api/notes?player=" + encodeURIComponent(id)); cache.set(id, r.ok ? r.notes : []); }
    catch (e) { cache.delete(id); return; }
    if (typeof App !== "undefined") App.refresh();
  }
  function changed(player) { if (cache.has(player)) { cache.delete(player); fetchFor(player); } }
  const when = (at) => {
    const m = Math.round((Date.now() - at) / 60000);
    return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? Math.round(m / 60) + " h ago" : new Date(at).toLocaleDateString([], { day: "numeric", month: "short" });
  };
  const time = (at) => new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  // the section in a player's sheet or card (D279, D320)
  function section(p) {
    const wrap = el("div", "notes");
    const list = cache.get(p.id);
    if (list === undefined) fetchFor(p.id);
    wrap.appendChild(el("div", "sect", "NOTES" + (Array.isArray(list) ? " · " + list.length : "")));
    if (!Array.isArray(list)) { wrap.appendChild(el("div", "note", "Loading the notes…")); return wrap; }
    list.forEach((n) => wrap.appendChild(card(p, n)));
    const f = el("form", "speakform");
    const i = el("textarea", "noteinput ta"); i.rows = 2; i.maxLength = 1000; i.placeholder = "Add a note about " + p.name + "…"; i.value = drafts.get(p.id) || "";
    i.addEventListener("input", () => drafts.set(p.id, i.value));
    const s = el("button", "btn sm", "Add"); s.type = "submit";
    f.appendChild(i); f.appendChild(s);
    f.addEventListener("submit", async (ev) => {
      ev.preventDefault(); const text = (drafts.get(p.id) || "").trim(); if (!text) return;
      const r = await api("/api/notes/add", { player: p.id, name: p.name, text });
      if (!r.ok) { alert(r.message); return; }
      drafts.delete(p.id); cache.delete(p.id); fetchFor(p.id);
    });
    wrap.appendChild(f);
    return wrap;
  }
  function card(p, n) {
    const c = el("div", "nt");
    const hd = el("div", "mhd"); hd.appendChild(el("b", null, n.author)); hd.appendChild(el("span", "t", when(n.at)));
    const can = n.authorId === me.id || me.rank !== "MODERATOR"; // the author, or an Admin or Owner (D330)
    if (can) {
      const dots = el("button", "dots", "⋯"); dots.type = "button"; dots.title = "Edit or delete";
      dots.addEventListener("click", () => { menu = menu === n.id ? null : n.id; armed = 0; App.refresh(); });
      hd.appendChild(dots);
    }
    c.appendChild(hd);
    if (editing === n.id) {
      const f = el("form", "noteedit");
      const t = el("textarea", "noteinput ta"); t.rows = 3; t.maxLength = 1000; t.value = editDraft; t.addEventListener("input", () => { editDraft = t.value; });
      const row = el("div", "row2");
      const cancel = el("button", "btn sm ghost", "Cancel"); cancel.type = "button"; cancel.addEventListener("click", () => { editing = null; App.refresh(); });
      const save = el("button", "btn sm", "Save"); save.type = "submit";
      row.appendChild(cancel); row.appendChild(save); f.appendChild(t); f.appendChild(row);
      f.addEventListener("submit", async (ev) => {
        ev.preventDefault();
        const r = await api("/api/notes/edit", { id: String(n.id), text: editDraft });
        if (!r.ok) { alert(r.message); return; }
        editing = null; cache.delete(p.id); fetchFor(p.id);
      });
      c.appendChild(f); return c;
    }
    c.appendChild(el("div", "mtext", n.text));
    if (n.editedAt) c.appendChild(el("div", "ed", "edited " + time(n.editedAt)));
    if (menu === n.id) {
      const row = el("div", "row2");
      const e = el("button", "btn sm ghost", "Edit"); e.type = "button";
      e.addEventListener("click", () => { editing = n.id; editDraft = n.text; menu = null; App.refresh(); });
      const d = el("button", "btn sm danger", Date.now() - armed < 3000 ? "Sure? Delete it" : "Delete"); d.type = "button";
      d.addEventListener("click", async () => {
        if (Date.now() - armed > 3000) { armed = Date.now(); App.refresh(); return; } // a second tap within 3 s
        const r = await api("/api/notes/delete", { id: String(n.id) });
        if (!r.ok) { alert(r.message); return; }
        menu = null; cache.delete(p.id); fetchFor(p.id);
      });
      row.appendChild(e); row.appendChild(d); c.appendChild(row);
    }
    return c;
  }
  return { section, changed };
})();

// More › Tags (D320): the tags typed by staff waiting for a colour, then the list; admins colour, rename and remove.
const TagsPage = (() => {
  const COLOURS = ["#ff6b5c", "#ffd23a", "#8fe04a", "#19d3b0", "#7fb2ff", "#b58cff", "#ff55ff", "#9aa3b5"];
  let host = null, open = null, draft = { name: "", colour: "" }, armed = 0;
  function view(main) { host = main; draw(); }
  function redraw() { if (host && host.isConnected) draw(); else host = null; }
  function draw() {
    const box = host.querySelector(".tagspage") || el("div", "tagspage");
    box.textContent = ""; if (!box.isConnected) host.appendChild(box);
    const admin = me.rank !== "MODERATOR", list = Tags.list();
    box.appendChild(el("h2", null, "Tags"));
    if (!admin) box.appendChild(el("div", "note", "Admins and Owners keep this list. Anyone puts tags on players from their sheet, and may type a new one there."));
    const typed = list.filter((t) => t.typed), kept = list.filter((t) => !t.typed);
    if (typed.length) { box.appendChild(el("div", "sect", "TYPED BY STAFF · " + typed.length)); const l = el("div", "list"); typed.forEach((t) => l.appendChild(row(t, admin))); box.appendChild(l); }
    box.appendChild(el("div", "sect", "THE LIST · " + kept.length));
    const l = el("div", "list"); kept.forEach((t) => l.appendChild(row(t, admin)));
    if (!kept.length) l.appendChild(el("div", "item note", "No tags yet: type one in a player's sheet."));
    box.appendChild(l);
  }
  function row(t, admin) {
    const wrap = el("div", "tagrow" + (open === t.id ? " open" : ""));
    const top = el("button", "item menuitem"); top.type = "button";
    const sw = el("span", "sw"); sw.style.background = t.colour; top.appendChild(sw); top.appendChild(Tags.chip(t));
    top.appendChild(el("span", "grow sub", t.typed ? "typed by " + t.by : ""));
    top.appendChild(el("span", "mu mono", t.count + (t.count === 1 ? " player" : " players")));
    if (admin) { top.appendChild(el("span", "mu", open === t.id ? "▴" : "▾")); top.addEventListener("click", () => { open = open === t.id ? null : t.id; draft = { name: t.name, colour: t.typed ? "" : t.colour }; armed = 0; draw(); }); }
    wrap.appendChild(top);
    if (open !== t.id || !admin) return wrap;
    const ed = el("form", "tagedit");
    const pal = el("div", "pal");
    COLOURS.forEach((c) => { const b = el("button", "swb" + (draft.colour === c ? " on" : "")); b.type = "button"; b.style.background = c; b.title = c; b.addEventListener("click", () => { draft.colour = c; draw(); }); pal.appendChild(b); });
    ed.appendChild(pal);
    const name = el("input"); name.type = "text"; name.maxLength = 24; name.value = draft.name; name.addEventListener("input", () => { draft.name = name.value; });
    ed.appendChild(name);
    const keys = el("div", "row2");
    const rm = el("button", "btn sm danger", Date.now() - armed < 3000 ? "Sure? Remove from everyone" : "Remove"); rm.type = "button";
    rm.addEventListener("click", async () => {
      if (Date.now() - armed > 3000) { armed = Date.now(); draw(); return; }
      const r = await api("/api/tags/remove", { id: String(t.id) }); if (!r.ok) alert(r.message); open = null; await Tags.changed();
    });
    const save = el("button", "btn sm", t.typed ? "Keep" : "Save"); save.type = "submit";
    keys.appendChild(rm); keys.appendChild(save); ed.appendChild(keys);
    ed.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      if (t.typed && !draft.colour) { alert("Pick a colour to keep it in the list."); return; }
      const r = await api("/api/tags/edit", { id: String(t.id), name: draft.name, colour: draft.colour });
      if (!r.ok) { alert(r.message); return; } open = null; await Tags.changed();
    });
    wrap.appendChild(ed); return wrap;
  }
  return { view, redraw };
})();
