"use strict";
// The Graves page (D295 to D302): a player typed by name, online or not, and their graves from every server, newest
// first, each marked in the world, not placed or put away; RETURN on the ones that may be returned, for Admins and Owners,
// with a second tap. Each grave's items as slots like a chest (D301), with their pictures (SU-41); tap one for its name,
// enchantments and what it holds; backpacks and danks inside the grave under it (SU-42, BSP-InventorySync's SU-IS3).
const Graves = (() => {
  const st = { query: "", result: null, busy: false, armed: {}, answers: {}, items: {}, tip: null };
  const ago = (ms) => {
    const m = Math.round((Date.now() - ms) / 60000);
    if (m < 60) return Math.max(1, m) + " min ago";
    const hrs = Math.round(m / 60); if (hrs < 48) return hrs + " h ago";
    return Math.round(hrs / 24) + " days ago";
  };
  const dim = (d) => (d || "").replace(/^minecraft:/, "").replace("the_nether", "nether").replace("the_end", "end");

  async function look(name) {
    st.query = name; st.busy = true; st.result = null; st.answers = {}; st.armed = {}; st.items = {}; st.tip = null; App.refreshMore();
    try { st.result = await api("/api/graves", { player: name }); } catch (e) { st.result = { ok: false, message: "The panel could not be reached." }; }
    st.busy = false; App.refreshMore();
  }

  function card(g, p, me) {
    const c = el("div", "gcard");
    const top = el("div", "gtop");
    const kind = g.placed === true && g.archived !== true ? ["world", "in the world"] : g.archived === true ? ["away", "put away"] : ["np", "not placed"];
    top.appendChild(el("span", "gst " + kind[0], kind[1])); top.appendChild(el("span", "gid mono", g.shortId || ""));
    const sp = el("span", "grow"); top.appendChild(sp); top.appendChild(el("span", "sub", g.diedAt ? ago(Number(g.diedAt)) : ""));
    c.appendChild(top);
    c.appendChild(el("div", "sub mono", (g.serverId || "?") + " · " + dim(g.dimension) + " · " + g.x + ", " + g.y + ", " + g.z + " · " + g.xp + " XP"));
    c.appendChild(itemsOf(g, p));
    const ans = st.answers[g.shortId];
    if (ans) c.appendChild(el("div", ans.ok ? "okm" : "err", ans.message));
    if (g.returnable === true && !(ans && ans.ok)) {
      const can = me.rank !== "MODERATOR";
      const b = el("button", "btn sm" + (can ? "" : " ghost locked-item"), can ? (Date.now() - (st.armed[g.shortId] || 0) < 3000 ? "Sure? Return it to " + p.name : "Return to " + p.name) : "Return (Admin)");
      b.type = "button";
      b.addEventListener("click", async () => {
        if (!can) { alert("Graves are returned by Admins and Owners."); return; }
        if (Date.now() - (st.armed[g.shortId] || 0) > 3000) { st.armed[g.shortId] = Date.now(); b.textContent = "Sure? Return it to " + p.name; return; }
        st.armed[g.shortId] = 0; b.disabled = true; b.textContent = "Returning…";
        try { st.answers[g.shortId] = await api("/api/grave/return", { player: p.id, grave: g.shortId }); }
        catch (e) { st.answers[g.shortId] = { ok: false, message: "The panel could not be reached." }; }
        App.refreshMore();
      });
      c.appendChild(b);
    } else if (g.returnable !== true) {
      c.appendChild(el("div", "note", "Standing in the world: " + p.name + " recovers it there, or staff open it where it stands."));
    }
    return c;
  }

  // one grave at a time: a player with many graves must not take every action the panel may have waiting (audit)
  let queue = Promise.resolve();
  function fetchItems(g, p) {
    const items = st.items; // a later search starts a fresh set: an older answer lands in its own
    items[g.shortId] = "loading";
    queue = queue.then(async () => {
      if (items !== st.items) return;
      let r;
      try { r = await api("/api/grave/items", { player: p.id, grave: g.shortId }); } catch (e) { r = { ok: false, message: "The panel could not be reached." }; }
      items[g.shortId] = r;
      if (items === st.items) App.refreshMore();
    });
  }
  // the grave's items as slots like a chest (D301), with backpacks and danks inside it below (SU-42)
  function itemsOf(g, p) {
    const box = el("div", "gitems");
    const r = st.items[g.shortId];
    if (r === undefined) fetchItems(g, p);
    if (!r || r === "loading" || !r.ok || !r.items) {
      const slots = el("div", "gslots");
      const n = Math.min(Number(g.stacks) || 0, 36);
      for (let i = 0; i < Math.max(9, Math.ceil(n / 9) * 9); i++) slots.appendChild(el("div", "gslot" + (i < n ? " full" : "")));
      box.appendChild(slots);
      const why = r && r !== "loading" ? (/cannot list/.test(r.message || "") ? "The items show once BSP-InventorySync can list them." : r.message) : "Reading the items…";
      box.appendChild(el("div", "note", g.stacks + (g.stacks === 1 ? " stack" : " stacks") + ". " + why));
      return box;
    }
    (r.items.areas || []).forEach((a) => {
      if (a.id !== "grave") {
        const at = a.place ? "in slot " + (a.place.slot + 1) + (a.place.area !== "grave" ? " of another backpack" : "") : "";
        box.appendChild(el("div", "ilab2", (a.kind === "DANK" ? "DANK" : "BACKPACK") + (at ? " " + at.toUpperCase() : "") + " · " + a.slots.length + " / " + a.size));
      }
      const by = new Map(a.slots.map((s) => [s.i, s]));
      const size = a.id === "grave" ? Math.max(9, Math.ceil(Math.max(a.size, ...a.slots.map((s) => s.i + 1)) / 9) * 9) : Math.max(9, a.size);
      const grid = el("div", "igrid");
      let tipItem = null;
      for (let i = 0; i < size; i++) {
        const it = by.get(i);
        const b = el("button", "islot" + (it && it.lk ? " lk" : "")); b.type = "button";
        if (!it) { b.disabled = true; grid.appendChild(b); continue; }
        const key = g.shortId + " " + a.id + " " + i;
        if (st.tip === key) { b.classList.add("sel"); tipItem = it; }
        b.appendChild(Items.icon(it, 24) || el("span", "ilt", Items.letters(it)));
        if (it.n > 1) b.appendChild(el("span", "ct", it.n > 9999 ? Math.round(it.n / 1000) + "k" : String(it.n)));
        b.title = Items.name(it) + (it.n > 1 ? " × " + it.n : "");
        b.addEventListener("click", () => { st.tip = st.tip === key ? null : key; App.refreshMore(); });
        grid.appendChild(b);
      }
      box.appendChild(grid);
      if (tipItem) box.appendChild(Items.describe(tipItem, a.id));
    });
    return box;
  }

  function view(main, me) {
    main.appendChild(el("h2", null, "Graves"));
    const f = el("form", "gsearch");
    const q = el("input", "search"); q.type = "search"; q.placeholder = "A player's name, online or not"; q.value = st.query; q.maxLength = 16;
    const go = el("button", "btn sm", "Look"); go.type = "submit";
    f.appendChild(q); f.appendChild(go);
    f.addEventListener("submit", (e) => { e.preventDefault(); if (q.value.trim()) look(q.value.trim()); });
    main.appendChild(f);
    if (st.busy) { main.appendChild(el("div", "note", "Looking…")); return; }
    const r = st.result;
    if (!r) return;
    if (!r.ok && !r.player) { main.appendChild(el("div", "err", r.message)); return; }
    const p = r.player;
    const who = el("div", "item"); const g = el("div", "grow");
    g.appendChild(el("div", "nm", p.name)); if (p.id) g.appendChild(Tags.chips(p.id, 2)); // D331
    g.appendChild(el("div", "sub", (p.server ? "online on " + p.server : "offline") + " · " + r.graves.length + (r.graves.length === 1 ? " grave" : " graves")));
    who.appendChild(g); const box = el("div", "list"); box.appendChild(who); main.appendChild(box);
    if (!r.ok) { main.appendChild(el("div", "err", r.message)); return; }
    if (!r.graves.length) { main.appendChild(el("div", "note", p.name + " has no grave holding anything.")); return; }
    const list = el("div", "glist"); r.graves.forEach((gr) => list.appendChild(card(gr, p, me))); main.appendChild(list);
  }
  return { view, look };
})();
