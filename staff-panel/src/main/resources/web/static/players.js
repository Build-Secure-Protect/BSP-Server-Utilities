"use strict";
// The Players tab (D273 to D279, D287, D292 to D294): servers, players grouped by team (or by server), their stats as
// the game's own icons; a player opens in a sheet from the bottom on a phone, or a card to drag on a computer.
const Players = (() => {
  let groupBy = "team", filter = "", openId = null, collapsed = new Set(), cardPos = null;

  // ---------------------------------------------------------------- the game's icons, drawn as pixels
  const ART = {
    heart: [".XX.XX.", "XRRXRRX", "XRWRRRX", "XRRRRRX", ".XRRRX.", "..XRX..", "...X..."],
    armor: ["XX...XX", "XGXXXGX", "XGGGGGX", ".XGGGX.", ".XGGGX.", ".XGGGX.", ".XXXXX."],
    food: ["...XXX.", "..XBBBX", ".XBBBBX", ".XBBBX.", "XWXXX..", "WX.....", "X......"],
    air: [".XXXX.", "XWBBBX", "XBWBBX", "XBBBBX", "XBBBBX", ".XXXX."],
  };
  const FULL = { heart: { X: "#1d0b0b", R: "#e23b3b", W: "#ffd9d9" }, armor: { X: "#1d0b0b", G: "#c9ced8" },
    food: { X: "#1d0b0b", B: "#b5651d", W: "#eeeeee" }, air: { X: "#0c2a4a", W: "#e8f6ff", B: "#3a8ee0" } };
  const EMPTY = { heart: "#3a1414", armor: "#2c3038", food: "#2e2218", air: "#16263a" };
  function icon(kind, state, size) {
    const a = ART[kind], h = a.length, w = a[0].length, c = document.createElement("canvas");
    c.width = w; c.height = h; c.className = "px"; c.style.width = size + "px"; c.style.height = Math.round(size * h / w) + "px";
    const g = c.getContext("2d");
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const ch = a[y][x]; if (ch === ".") continue;
      let col = FULL[kind][ch] || "#000";
      const empty = state === 0 || (state === 0.5 && x >= w / 2);
      if (ch !== "X" && empty && !(kind === "food" && ch === "W")) col = EMPTY[kind];
      g.fillStyle = col; g.fillRect(x, y, 1, 1);
    }
    return c;
  }
  function icons(kind, value, max, size) {
    const d = el("div", "icons"), per = max / 10;
    for (let i = 0; i < 10; i++) { const v = value / per - i; d.appendChild(icon(kind, v >= 1 ? 1 : v >= 0.5 ? 0.5 : 0, size)); }
    return d;
  }

  // ---------------------------------------------------------------- heads: the player's own, or a plain face
  function plainFace(name, size) {
    const c = document.createElement("canvas"); c.width = 8; c.height = 8; c.className = "head"; c.style.width = c.style.height = size + "px";
    let h = 2166136261; for (const ch of name) { h ^= ch.charCodeAt(0); h = Math.imul(h, 16777619); }
    const pick = (arr) => { h = Math.imul(h ^ (h >>> 13), 1274126177) >>> 0; return arr[h % arr.length]; };
    const g = c.getContext("2d"); g.fillStyle = pick(["#f2c9a0", "#d9a066", "#a8714a", "#6b4a33"]); g.fillRect(0, 0, 8, 8);
    g.fillStyle = pick(["#3b2414", "#7a4a1f", "#d8b04a", "#1a1a1a", "#5b5f6b"]); g.fillRect(0, 0, 8, 2);
    g.fillStyle = "#fff"; g.fillRect(1, 4, 2, 1); g.fillRect(5, 4, 2, 1); g.fillStyle = "#2a2a2a"; g.fillRect(2, 4, 1, 1); g.fillRect(5, 4, 1, 1);
    g.fillStyle = "#7a3b2b"; g.fillRect(2, 6, 4, 1);
    return c;
  }
  function head(p, size) {
    if (!p.skin) return plainFace(p.name, size);
    const img = el("img", "head"); img.alt = ""; img.width = size; img.height = size; img.src = "/api/head/" + p.id;
    img.addEventListener("error", () => img.replaceWith(plainFace(p.name, size)), { once: true });
    return img;
  }

  // ---------------------------------------------------------------- words
  const ROMAN = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"];
  function effectName(e) {
    const words = e.id.replace(/^.*:/, "").split("_").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
    const lvl = e.amp > 0 ? " " + (ROMAN[e.amp] || e.amp + 1) : "";
    const time = e.sec < 0 ? "∞" : Math.floor(e.sec / 60) + ":" + String(e.sec % 60).padStart(2, "0");
    return words + lvl + " " + time;
  }
  const dim = (d) => d === "minecraft:overworld" ? "" : d.replace(/^minecraft:/, "").replace("the_nether", "nether").replace("the_end", "end") + " · ";
  const where = (p) => p.server + " · " + dim(p.dim) + Math.round(p.x) + ", " + Math.round(p.y) + ", " + Math.round(p.z);
  const minutes = (ms) => Math.max(1, Math.round(ms / 60000)) + " min";

  // ---------------------------------------------------------------- the list
  function groups(players) {
    const map = new Map();
    for (const p of players) {
      if (filter && !p.name.toLowerCase().includes(filter)) continue;
      let key, label, colour;
      if (groupBy === "team") { key = p.team ? "t:" + p.team.id : "none"; label = p.team ? p.team.name : "No team"; colour = p.team ? p.team.colour : "#6b7180"; }
      else { key = "s:" + p.server; label = p.server; colour = "#19d3b0"; }
      if (!map.has(key)) map.set(key, { key, label, colour, players: [] });
      map.get(key).players.push(p);
    }
    return [...map.values()].sort((a, b) => (a.key === "none") - (b.key === "none") || b.players.length - a.players.length || a.label.localeCompare(b.label));
  }

  function row(p) {
    const r = el("button", "prow" + (p.id === openId ? " sel" : "")); r.type = "button";
    r.appendChild(head(p, 30));
    const g = el("div", "grow"); const top = el("div", "pname"); top.appendChild(el("span", "nm", p.name));
    if (p.role) top.appendChild(el("span", "chip role", p.role));
    if (p.afkMs > 0) top.appendChild(el("span", "chip afk", "AFK " + minutes(p.afkMs)));
    if (p.mode && p.mode !== "survival") top.appendChild(el("span", "chip mode", p.mode));
    g.appendChild(top); g.appendChild(el("div", "sub mono where", where(p))); g.appendChild(el("div", "sub mono short", p.server));
    r.appendChild(g);
    const full = el("div", "statsfull"); full.appendChild(icons("heart", p.hp, p.maxHp || 20, 7.5));
    const line = el("div", "minirow"); if (p.hp <= 6) line.appendChild(el("span", "low", "LOW"));
    line.appendChild(icon("armor", 1, 8)); line.appendChild(el("span", null, String(p.armor)));
    line.appendChild(icon("food", 1, 8)); line.appendChild(el("span", null, String(p.food)));
    full.appendChild(line); r.appendChild(full);
    const mini = el("div", "statsmini minirow"); if (p.hp <= 6) mini.appendChild(el("span", "low", "LOW"));
    mini.appendChild(icon("heart", 1, 9)); mini.appendChild(el("span", null, String(Math.round(p.hp) / 2)));
    r.appendChild(mini);
    r.addEventListener("click", () => { if (openId !== p.id) { kickOpen = null; result = null; } openId = p.id; App.refresh(); });
    return r;
  }

  function view(main, live) {
    const total = live.players.length;
    // servers: chips on a phone, tiles on a computer
    const chips = el("div", "chips");
    const tiles = el("div", "tiles");
    live.servers.forEach((s) => {
      const c = el("div", "chip srv"); c.appendChild(el("i", s.count ? "dot" : "dot off")); c.appendChild(el("span", null, s.server)); c.appendChild(el("b", null, String(s.count))); chips.appendChild(c);
      const t = el("div", "tile" + (s.count ? "" : " off")); t.appendChild(el("b", null, String(s.count))); t.appendChild(document.createTextNode(s.server.replace(/^BSP-/, "")));
      t.title = s.server + " · " + s.mspt + " ms a tick"; tiles.appendChild(t);
    });
    const net = el("div", "tile net"); net.appendChild(el("b", null, String(total))); net.appendChild(document.createTextNode("Network")); tiles.appendChild(net);
    main.appendChild(chips); main.appendChild(tiles);
    if (!live.servers.length) {
      main.appendChild(el("div", "note", "No game server has sent its players yet. Each server sends them while webPanel.enabled is on in its config/bsp_server_utilities-network.toml."));
      return;
    }
    const gs = groups(live.players);
    const bar = el("div", "pbar");
    const count = el("div", "grow"); count.appendChild(el("span", "big", String(total)));
    count.appendChild(el("span", "mu", " online · " + (groupBy === "team" ? gs.filter((x) => x.key !== "none").length + " teams" : live.servers.length + " servers")));
    bar.appendChild(count);
    const search = el("input", "search"); search.type = "search"; search.placeholder = "Find a player…"; search.value = filter;
    search.addEventListener("input", () => { filter = search.value.trim().toLowerCase(); App.refresh(true); });
    bar.appendChild(search);
    const seg = el("div", "seg");
    [["team", "Teams"], ["server", "Servers"]].forEach(([k, t]) => {
      const b = el("button", groupBy === k ? "on" : "", t); b.type = "button"; b.addEventListener("click", () => { groupBy = k; App.refresh(); }); seg.appendChild(b);
    });
    bar.appendChild(seg); main.appendChild(bar);
    const grid = el("div", "groups");
    gs.forEach((gr) => {
      const card = el("div", "gcard"); const h = el("button", "ghead"); h.type = "button"; h.style.borderLeftColor = gr.colour;
      h.appendChild(el("span", "nm", gr.label)); h.appendChild(el("span", "mu", gr.players.length + " online"));
      h.appendChild(el("span", "chev", collapsed.has(gr.key) ? "⌄" : "⌃"));
      h.addEventListener("click", () => { collapsed.has(gr.key) ? collapsed.delete(gr.key) : collapsed.add(gr.key); App.refresh(); });
      card.appendChild(h);
      if (!collapsed.has(gr.key)) gr.players.forEach((p) => card.appendChild(row(p)));
      grid.appendChild(card);
    });
    if (!gs.length) grid.appendChild(el("div", "note", "Nobody online matches “" + filter + "”."));
    main.appendChild(grid);
    if (search === document.activeElement) search.focus();
  }

  // ---------------------------------------------------------------- one player: the sheet (phone) or card (computer)
  function detail(live) {
    let box = $("pdetail");
    if (!openId) { if (box) box.remove(); const d = $("pdim"); if (d) d.remove(); return; }
    const p = live.players.find((x) => x.id === openId);
    if (!box) {
      const dimmer = el("div", "pdim"); dimmer.id = "pdim"; dimmer.addEventListener("click", close); document.body.appendChild(dimmer);
      box = el("div", "pdetail"); box.id = "pdetail"; document.body.appendChild(box);
      if (cardPos) { box.style.left = cardPos[0] + "px"; box.style.top = cardPos[1] + "px"; }
    }
    box.textContent = "";
    const top = el("div", "dtop"); const grip = el("div", "grip"); grip.title = "Drag"; top.appendChild(grip);
    const x = el("button", "close", "✕"); x.type = "button"; x.title = "Close"; x.addEventListener("click", close);
    box.appendChild(el("div", "grab"));
    if (!p) {
      top.appendChild(el("div", "grow nm", "Not online any more")); top.appendChild(x); box.appendChild(top);
      box.appendChild(el("div", "note", "They left the network, or their server stopped sending."));
      drag(grip, box); return;
    }
    const who = el("div", "grow"); const n = el("div", "dname"); n.textContent = p.name; who.appendChild(n);
    const sub = el("div", "sub");
    if (p.team) { const sq = el("span", "sq", "■"); sq.style.color = p.team.colour; sub.appendChild(sq); sub.appendChild(document.createTextNode(" " + p.team.name + " · ")); }
    sub.appendChild(document.createTextNode("ping " + p.ping + " ms" + (p.role ? " · " + p.role : "") + (p.mode !== "survival" ? " · " + p.mode : "")));
    who.appendChild(sub);
    top.appendChild(head(p, 48)); top.appendChild(who); top.appendChild(x); box.appendChild(top);
    const loc = el("div", "loc mono"); loc.appendChild(el("span", "tl", "● ")); loc.appendChild(document.createTextNode(where(p) + (p.afkMs > 0 ? " · AFK " + minutes(p.afkMs) : "")));
    box.appendChild(loc);
    const st = el("div", "statbox");
    st.appendChild(icons("heart", p.hp, p.maxHp || 20, 11)); st.appendChild(icons("food", p.food, 20, 11));
    st.appendChild(icons("armor", Math.min(p.armor, 20), 20, 11));
    if (p.air < p.maxAir) st.appendChild(icons("air", p.air, p.maxAir, 11)); else st.appendChild(el("div"));
    const xp = el("div", "xprow"); xp.appendChild(el("span", "lvl", String(p.level))); const bar = el("div", "bar"); const fill = el("i"); fill.style.width = Math.round(p.xp * 100) + "%"; bar.appendChild(fill); xp.appendChild(bar);
    st.appendChild(xp); box.appendChild(st);
    const nums = el("div", "nums mono"); nums.textContent = "health " + p.hp + "/" + p.maxHp + " · hunger " + p.food + "/20 · armour " + p.armor + (p.air < p.maxAir ? " · air " + Math.round(p.air / 30) + "/10" : "");
    box.appendChild(nums);
    if (p.effects && p.effects.length) { const fx = el("div", "effects"); p.effects.forEach((e) => fx.appendChild(el("span", "eff", effectName(e)))); box.appendChild(fx); }
    box.appendChild(actionsFor(p));
    drag(grip, box);
  }

  // ---------------------------------------------------------------- actions (D278): Kick for every rank; more as they are built
  let kickOpen = null, kickReason = "", kickArmed = 0, result = null;
  function actionsFor(p) {
    const wrap = el("div", "pact");
    const keys = el("div", "keys");
    const kick = el("button", "key danger", "Kick"); kick.type = "button";
    kick.addEventListener("click", () => { kickOpen = kickOpen === p.id ? null : p.id; result = null; App.refresh(); });
    const graves = el("button", "key", "Graves"); graves.type = "button";
    graves.addEventListener("click", () => { openId = null; kickOpen = null; result = null; Players.leave(); openGraves(p.name); });
    keys.appendChild(graves); keys.appendChild(kick); wrap.appendChild(keys);
    if (kickOpen === p.id) {
      const f = el("div", "kickform");
      const why = el("input", "reason"); why.type = "text"; why.maxLength = 160; why.placeholder = "Why (they read it)"; why.value = kickReason;
      why.addEventListener("input", () => { kickReason = why.value; });
      const go = el("button", "btn sm danger", Date.now() - kickArmed < 3000 ? "Sure? Kick " + p.name : "Kick " + p.name + " from the network"); go.type = "button";
      go.addEventListener("click", async () => {
        if (Date.now() - kickArmed > 3000) { kickArmed = Date.now(); go.textContent = "Sure? Kick " + p.name; return; } // a second tap within 3 s
        kickArmed = 0; go.disabled = true; go.textContent = "Kicking…";
        try { const r = await api("/api/act", { action: "kick", player: p.id, reason: kickReason }); result = r; if (r.ok) { kickOpen = null; kickReason = ""; } }
        catch (e) { result = { ok: false, message: "The panel could not be reached." }; }
        App.refresh();
      });
      f.appendChild(why); f.appendChild(go); wrap.appendChild(f);
    }
    if (result) wrap.appendChild(el("div", result.ok ? "okm" : "err", result.message));
    return wrap;
  }

  function drag(grip, box) {
    grip.addEventListener("pointerdown", (e) => {
      if (window.innerWidth < 900) return;
      const r = box.getBoundingClientRect(), ox = e.clientX - r.left, oy = e.clientY - r.top;
      grip.setPointerCapture(e.pointerId);
      const move = (m) => { cardPos = [Math.max(0, Math.min(window.innerWidth - r.width, m.clientX - ox)), Math.max(0, Math.min(window.innerHeight - 60, m.clientY - oy))];
        box.style.left = cardPos[0] + "px"; box.style.top = cardPos[1] + "px"; };
      const up = () => { grip.removeEventListener("pointermove", move); grip.removeEventListener("pointerup", up); };
      grip.addEventListener("pointermove", move); grip.addEventListener("pointerup", up);
    });
  }

  function close() { openId = null; kickOpen = null; result = null; App.refresh(); }
  function leave() { openId = null; detail({ players: [] }); }

  return { view, detail, leave };
})();
