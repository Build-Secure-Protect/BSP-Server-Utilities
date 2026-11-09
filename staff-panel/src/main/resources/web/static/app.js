"use strict";
// The panel's frame: the five tabs (bottom on a phone, a menu on the left from 900 px, D276, D287), staff online on the
// left (D293), the live feed, and the More tab with your login and, for Owners, the staff accounts (D291).
const TABS = ["Players", "Chat", "Map", "Staff", "More"];
let me = null, live = { servers: [], players: [], onPanel: [] }, tab = "Players";

function nav() {
  const bottom = $("tabs"), side = $("sidenav");
  bottom.textContent = ""; side.textContent = "";
  for (const t of TABS) {
    const b = el("button", t === tab ? "on" : ""); b.type = "button";
    b.innerHTML = ICONS[t]; b.appendChild(el("span", null, t));
    b.addEventListener("click", () => { tab = t; render(); });
    bottom.appendChild(b);
    const s = el("button", "nav" + (t === tab ? " on" : "")); s.type = "button";
    s.innerHTML = ICONS[t]; s.appendChild(el("span", null, t));
    s.addEventListener("click", () => { tab = t; render(); });
    side.appendChild(s);
  }
}

function staffOnline() {
  const box = $("staffonline"); box.textContent = "";
  const inGame = live.players.filter((p) => p.role);
  const names = new Set(inGame.map((p) => p.name));
  inGame.forEach((p) => {
    const r = el("div", "staffon" + (p.afkMs > 0 ? " away" : ""));
    const g = el("div"); g.appendChild(el("div", "nm", p.name));
    g.appendChild(el("div", "sub", p.role + " · " + p.server + (p.afkMs > 0 ? " · AFK " + Math.round(p.afkMs / 60000) + " min" : "")));
    r.appendChild(g);
    if (live.onPanel.includes(p.name)) r.appendChild(el("span", "onpanel", "▣"));
    box.appendChild(r);
  });
  live.onPanel.filter((n) => !names.has(n)).forEach((n) => {
    const r = el("div", "staffon"); const g = el("div");
    g.appendChild(el("div", "nm", n)); g.appendChild(el("div", "sub", "on the panel"));
    r.appendChild(g); r.appendChild(el("span", "onpanel", "▣")); box.appendChild(r);
  });
  $("staffcount").textContent = String(box.children.length);
}

let moreView = "home";
async function more(main) {
  if (moreView === "graves") {
    const back = el("button", "btn sm ghost back", "‹ More"); back.type = "button"; back.addEventListener("click", () => { moreView = "home"; render(); });
    main.appendChild(back); Graves.view(main, me); return;
  }
  if (moreView === "broadcast") {
    const back = el("button", "btn sm ghost back", "‹ More"); back.type = "button"; back.addEventListener("click", () => { moreView = "home"; render(); });
    main.appendChild(back); Announce.view(main, live, me); return;
  }
  main.appendChild(el("h2", null, "Your login"));
  const mine = el("div", "list"); const i = el("div", "item"); const g = el("div", "grow");
  g.appendChild(el("div", "nm", me.mcName)); g.appendChild(el("div", "sub", me.email + " · " + me.backupLeft + " backup codes left"));
  i.appendChild(g); i.appendChild(el("span", "rk " + me.rank, me.rankLabel));
  const out = el("button", "btn sm ghost", "Log out"); out.type = "button";
  out.addEventListener("click", async () => { await api("/api/logout", {}); location.href = "/login"; });
  i.appendChild(out); mine.appendChild(i); main.appendChild(mine);
  // what does not fit in the tabs (the More list of the refined page); a rank's locked items stay, dimmed with the rank (D278)
  const menu = el("div", "list");
  const b = el("button", "item menuitem" + (me.rank === "MODERATOR" ? " locked-item" : "")); b.type = "button";
  const bg = el("div", "grow"); bg.appendChild(el("div", "nm", "Broadcast")); bg.appendChild(el("div", "sub", "A chat line or title to everyone, with a preview"));
  b.appendChild(bg); b.appendChild(el("span", "rk ADMIN", "Admin")); b.appendChild(el("span", "mu", "›"));
  b.addEventListener("click", () => {
    if (me.rank === "MODERATOR") { alert("Announcements are for Admins and Owners."); return; }
    moreView = "broadcast"; render();
  });
  menu.appendChild(b);
  const gr = el("button", "item menuitem"); gr.type = "button";
  const gg = el("div", "grow"); gg.appendChild(el("div", "nm", "Graves")); gg.appendChild(el("div", "sub", "A player's graves, online or not; Admins return them"));
  gr.appendChild(gg); gr.appendChild(el("span", "mu", "›"));
  gr.addEventListener("click", () => { moreView = "graves"; render(); });
  menu.appendChild(gr); main.appendChild(menu);
  if (me.rank !== "OWNER") return;
  main.appendChild(el("h2", null, "Staff accounts"));
  if (me.lockouts && me.lockouts.length) {
    const who = me.lockouts.map((l) => l.who + " at " + new Date(l.at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }));
    main.appendChild(el("div", "gold", me.lockouts.length + (me.lockouts.length === 1 ? " lock-out" : " lock-outs") + " since your last visit: " + who.join(", ") + "."));
  }
  const area = el("div"); main.appendChild(area);
  await staffAccounts(area);
}

function status(s, now) {
  if (s.lockedUntil) return ["locked until " + new Date(s.lockedUntil).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }), "locked"];
  if (!s.password) return ["setup link waiting", "waiting"];
  if (!s.setUp) return ["sets up a new authenticator at their next login", "waiting"];
  return [(s.you ? "you · " : "") + (s.onPanel ? "on the panel now" : "logged in before") + " · " + s.backupLeft + " backup codes", ""];
}

async function staffAccounts(area) {
  area.textContent = "";
  const r = await api("/api/staff");
  if (!r.ok) { area.appendChild(el("div", "err", r.message)); return; }
  const top = el("div", "actions"); const add = el("button", "btn sm", "+ Add staff"); add.type = "button";
  top.appendChild(add); area.appendChild(top);
  const form = addForm(area); form.classList.add("hidden"); add.addEventListener("click", () => form.classList.toggle("hidden"));
  const table = el("table", "staff"); const head = el("tr");
  ["Name", "Rank", "Email", "Status", "Last login", ""].forEach((h) => head.appendChild(el("th", null, h)));
  table.appendChild(head);
  const list = el("div", "list stafflist");
  r.staff.forEach((s) => {
    const [st, cls] = status(s, r.now);
    const last = s.lastLogin ? new Date(s.lastLogin).toLocaleString([], { dateStyle: "short", timeStyle: "short" }) : "never";
    const tr = el("tr");
    tr.appendChild(el("td", "nm", s.mcName)); const rk = el("td"); rk.appendChild(el("span", "rk " + s.rank, s.rankLabel)); tr.appendChild(rk);
    tr.appendChild(el("td", "mu", s.email)); tr.appendChild(el("td", cls, st)); tr.appendChild(el("td", "mu", last));
    const m = el("td"); m.appendChild(menu(s, area)); tr.appendChild(m); table.appendChild(tr);
    const it = el("div", "item"); const g = el("div", "grow"); const n = el("div", "nm", s.mcName + " "); n.appendChild(el("span", "rk " + s.rank, s.rankLabel));
    g.appendChild(n); g.appendChild(el("div", "sub", s.email)); g.appendChild(el("div", "sub " + cls, st)); it.appendChild(g); it.appendChild(menu(s, area)); list.appendChild(it);
  });
  area.appendChild(table); area.appendChild(list);
  area.appendChild(el("div", "note", "Owners are added and reset from the panel's console. Every change here is written to the panel's log."));
}

function menu(s, area) {
  const box = el("div", "menu");
  if (s.you || s.rank === "OWNER") return box;
  const b = el("button", null, "⋯"); b.type = "button"; b.title = "Actions"; const pop = el("div", "pop hidden");
  const acts = [];
  if (s.lockedUntil) acts.push(["Unlock now", "unlock"]);
  acts.push([s.rank === "ADMIN" ? "Make Moderator" : "Make Admin", "rank"], ["Reset authenticator", "reset-code"], ["Reset whole login", "reset-login"], ["Remove", "remove", 1]);
  acts.forEach(([label, action, danger]) => {
    const x = el("button", danger ? "danger" : "", label); x.type = "button";
    let armed = 0;
    x.addEventListener("click", async () => {
      if (Date.now() - armed > 3000) { armed = Date.now(); x.textContent = label + ": sure?"; return; } // two taps within 3 s
      const r = await api("/api/staff/action", { id: s.id, action, rank: s.rank === "ADMIN" ? "MODERATOR" : "ADMIN" });
      if (!r.ok) { alert(r.message); return; }
      if (r.link) showLink(area, "New setup link for " + s.mcName + ", valid 15 minutes and once:", r.link);
      else await staffAccounts(area);
    });
    pop.appendChild(x);
  });
  b.addEventListener("click", () => pop.classList.toggle("hidden"));
  box.appendChild(b); box.appendChild(pop); return box;
}

function addForm(area) {
  const f = el("form", "form");
  const email = el("input"); email.type = "email"; email.required = true;
  const name = el("input"); name.type = "text"; name.required = true; name.maxLength = 16;
  const rank = el("select"); [["MODERATOR", "Moderator"], ["ADMIN", "Admin"]].forEach(([v, t]) => { const o = el("option", null, t); o.value = v; rank.appendChild(o); });
  [["Their email (the one they sign in to Cloudflare with)", email], ["Their Minecraft name", name], ["Rank", rank]].forEach(([t, input]) => {
    const l = el("label", null, t); l.appendChild(input); f.appendChild(l);
  });
  const go = el("button", "btn sm", "Add and make their link"); go.type = "submit"; f.appendChild(go);
  const err = el("div", "err hidden"); f.appendChild(err);
  f.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const r = await api("/api/staff/add", { email: email.value, mcName: name.value, rank: rank.value });
    if (!r.ok) { err.textContent = r.message; err.classList.remove("hidden"); return; }
    showLink(area, "Send this to " + name.value + ". It works once, within 15 minutes, signed in to Cloudflare as " + email.value + ":", r.link);
  });
  area.appendChild(f);
  return f;
}

function showLink(area, text, link) {
  area.textContent = "";
  const f = el("div", "form"); f.appendChild(el("div", "okm", text)); f.appendChild(el("div", "linkbox", link));
  const row = el("div", "row2"); const copy = el("button", "btn sm ghost", "Copy link"); copy.type = "button";
  copy.addEventListener("click", async () => { try { await navigator.clipboard.writeText(link); copy.textContent = "Copied"; } catch (e) { copy.textContent = "Copy failed"; } });
  const back = el("button", "btn sm", "Back to staff accounts"); back.type = "button"; back.addEventListener("click", () => staffAccounts(area));
  row.appendChild(copy); row.appendChild(back); f.appendChild(row); area.appendChild(f);
}

async function render() {
  nav();
  const main = $("main"); main.textContent = "";
  if (tab !== "More") moreView = "home";
  if (tab === "Players") { Players.view(main, live); Players.detail(live); }
  else { Players.leave(); if (tab === "More") await more(main); else main.appendChild(el("div", "note", tab + " is not part of the panel yet.")); }
}

// Redraws the Players tab in place: the scroll position, the search box's focus and caret, and an open player kept.
// the Graves key in a player's sheet or card opens the Graves page for them (D297)
function openGraves(name) { tab = "More"; moreView = "graves"; render().then(() => Graves.look(name)); }

const App = {
  refresh() {
    if (tab !== "Players") return;
    const main = $("main"), active = document.activeElement;
    const keep = active && (active.classList.contains("search") ? ".search" : active.classList.contains("reason") ? ".reason" : null);
    const caret = keep ? active.selectionStart : 0, sy = window.scrollY, my = main.scrollTop;
    nav(); main.textContent = ""; Players.view(main, live); Players.detail(live);
    window.scrollTo(0, sy); main.scrollTop = my;
    if (keep) { const s = document.querySelector(keep); if (s) { s.focus(); s.setSelectionRange(caret, caret); } }
  },
  async refreshMore() {
    if (tab !== "More") return; // an answer arriving after staff moved to another tab draws nothing (audit)
    const main = $("main"), sy = window.scrollY; main.textContent = ""; await more(main); window.scrollTo(0, sy);
  },
};

// The live feed. The browser retries by itself after a network error, but not after a refusal (an ended login, the
// Cloudflare session running out, too many pages): then the page says so, checks why and reconnects or goes to log in (audit).
function connect() {
  const es = new EventSource("/api/live");
  es.addEventListener("open", () => offline(false));
  es.addEventListener("live", (e) => {
    live = JSON.parse(e.data);
    staffOnline();
    App.refresh();
  });
  es.addEventListener("ended", () => { es.close(); location.href = "/login"; });
  // this staff member opened the panel on more pages than the limit: this, the oldest, stops until asked (audit)
  es.addEventListener("replaced", () => { es.close(); replaced(); });
  es.onerror = () => {
    offline(true);
    if (es.readyState === EventSource.CLOSED) setTimeout(() => recheck(), 3000);
  };
}

function replaced() {
  offline(true);
  const b = $("offline"); b.textContent = "Open on another page or device: tap here to use this one."; b.classList.add("tap");
  b.onclick = () => { b.onclick = null; b.classList.remove("tap"); b.textContent = "Not live: reconnecting…"; connect(); };
}

function offline(on) {
  let b = $("offline");
  if (!b) { b = el("div", "offline", "Not live: reconnecting…"); b.id = "offline"; document.body.appendChild(b); }
  b.classList.toggle("hidden", !on);
}

async function recheck() {
  try {
    const r = await fetch("/api/me", { credentials: "same-origin", redirect: "manual" });
    if (r.type === "opaqueredirect" || r.status === 0) { location.reload(); return; } // Cloudflare wants its login again
    if (r.status === 401) { location.href = "/login"; return; }
    connect();
  } catch (e) {
    setTimeout(() => recheck(), 10000);
  }
}

(async () => {
  me = await api("/api/me");
  const m = $("me"); m.textContent = ""; m.appendChild(el("span", "nm", me.mcName + " ")); m.appendChild(el("span", "rk " + me.rank, me.rankLabel));
  await render();
  connect();
})();
