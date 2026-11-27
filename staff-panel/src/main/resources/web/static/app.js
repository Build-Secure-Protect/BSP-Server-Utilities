"use strict";
// The panel's frame: the six tabs (TOTEMS added by SU-99) (bottom on a phone, a menu on the left from 900 px, D276, D287), staff online on the
// left (D293), the live feed, and the More tab with your login and, for Owners, the staff accounts (D291).
const TABS = ["Players", "Chat", "Map", "Worlds", "Shops", "Kits", "Totems", "Staff", "More"]; // TOTEMS between Map and Staff (D613); WORLDS between Map and Totems (D765); SHOPS after WORLDS (D788); KITS after SHOPS (D838)
let me = null, live = { servers: [], players: [], onPanel: [] }, tab = "Players";

function nav() {
  const bottom = $("tabs"), side = $("sidenav"), h = hold();
  bottom.textContent = ""; side.textContent = "";
  for (const t of TABS) {
    // a phone's bottom bar keeps its six: WORLDS and SHOPS are in More there (D765, D814), which is lit while one is open
    const b = el("button", t === tab || (t === "More" && (tab === "Worlds" || tab === "Shops" || tab === "Kits")) ? "on" : ""); b.type = "button";
    b.innerHTML = ICONS[t]; b.appendChild(el("span", null, t));
    b.addEventListener("click", () => { tab = t; render(); });
    if (t !== "Worlds" && t !== "Shops" && t !== "Kits") bottom.appendChild(b); // KITS is in More on a phone too (D838)
    const s = el("button", "nav" + (t === tab ? " on" : "")); s.type = "button";
    s.innerHTML = ICONS[t]; s.appendChild(el("span", null, t));
    s.addEventListener("click", () => { tab = t; render(); });
    side.appendChild(s);
    // new chat while it is not on screen (D308): a red number for lines naming you or asking for staff, else a dot
    const bd = t === "Chat" && typeof ChatView !== "undefined" ? ChatView.badge() : t === "Staff" && typeof StaffView !== "undefined" ? StaffView.badge()
      : t === "More" && typeof PlayerMsg !== "undefined" ? PlayerMsg.badge() : null; // More: replies waiting on staff (D639, R7)
    if (bd) { b.appendChild(el("span", bd === "dot" ? "bdot" : "badge", bd === "dot" ? "" : bd)); s.appendChild(el("span", bd === "dot" ? "bdot" : "badge", bd === "dot" ? "" : bd)); }
    b.dataset.keep = "tab:" + t; s.dataset.keep = "nav:" + t;
  }
  give(h);
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
  if (moreView === "find") {
    const back = el("button", "btn sm ghost back", "‹ More"); back.type = "button";
    back.addEventListener("click", () => { moreView = "home"; render(); });
    main.appendChild(back); FindPlayer.view(main); return;
  }
  if (moreView === "bans") {
    const back = el("button", "btn sm ghost back", "‹ More"); back.type = "button"; back.addEventListener("click", () => { moreView = "home"; render(); });
    main.appendChild(back); Bans.view(main); return;
  }
  if (moreView === "pictures") {
    const back = el("button", "btn sm ghost back", "‹ More"); back.type = "button"; back.addEventListener("click", () => { moreView = "home"; render(); });
    main.appendChild(back); await ItemsPage.view(main); return;
  }
  if (moreView === "tags") {
    const back = el("button", "btn sm ghost back", "‹ More"); back.type = "button"; back.addEventListener("click", () => { moreView = "home"; render(); });
    main.appendChild(back); TagsPage.view(main); return;
  }
  if (moreView === "bounties") { // SU-77
    const back = el("button", "btn sm ghost back", "‹ More"); back.type = "button"; back.addEventListener("click", () => { moreView = "home"; render(); });
    main.appendChild(back); const page = el("div", "bkpagewrap bypagewrap"); main.appendChild(page); Bounties.view(page); return;
  }
  if (moreView === "bank") { // SU-72: back to the player's sheet (online) or their Find a player page
    const w = Bank.who();
    const back = el("button", "btn sm ghost back", "‹ " + (w ? w.name : "More")); back.type = "button";
    back.addEventListener("click", () => { if (!w) { moreView = "home"; render(); return; } if (live.players.some((x) => x.id === w.id)) openPlayerSheet(w.id); else openOffline(w); });
    main.appendChild(back); const page = el("div", "bkpagewrap"); main.appendChild(page); Bank.view(page); return;
  }
  if (moreView === "market" || moreView === "lottery") { // SU-52
    const back = el("button", "btn sm ghost back", "‹ More"); back.type = "button"; back.addEventListener("click", () => { moreView = "home"; render(); });
    main.appendChild(back); if (moreView === "market") Market.view(main); else Lottery.view(main); return;
  }
  if (moreView === "pmsg") { // SU-111: the open conversations with players (R7)
    const back = el("button", "btn sm ghost back", "‹ More"); back.type = "button"; back.addEventListener("click", () => { moreView = "home"; render(); });
    main.appendChild(back); PlayerMsg.view(main); return;
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
  out.addEventListener("click", async () => {
    out.disabled = true;
    const r = await call("/api/logout", {}, true);
    if (r.failed) { out.disabled = false; alert(r.message); return; }
    location.href = "/login";
  });
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
  const wd = el("button", "item menuitem phoneonly"); wd.type = "button"; wd.dataset.keep = "more:worlds"; // WORLDS on a phone (D765, R9)
  const wdg = el("div", "grow"); wdg.appendChild(el("div", "nm", "Worlds")); wdg.appendChild(el("div", "sub", "Every server's dimensions and its regions"));
  wd.appendChild(wdg); wd.appendChild(el("span", "mu", "›"));
  wd.addEventListener("click", () => { Worlds.home(); tab = "Worlds"; render(); });
  menu.appendChild(wd);
  const sh = el("button", "item menuitem phoneonly"); sh.type = "button"; sh.dataset.keep = "more:shops"; // SHOPS on a phone (D814)
  const shg = el("div", "grow"); shg.appendChild(el("div", "nm", "Shops")); shg.appendChild(el("div", "sub", "Every shop, its sections, stalls and sales"));
  sh.appendChild(shg); sh.appendChild(el("span", "mu", "›"));
  sh.addEventListener("click", () => { ShopsTab.home(); tab = "Shops"; render(); });
  menu.appendChild(sh);
  const kt = el("button", "item menuitem phoneonly"); kt.type = "button"; kt.dataset.keep = "more:kits"; // KITS on a phone (D838)
  const ktg = el("div", "grow"); ktg.appendChild(el("div", "nm", "Kits")); ktg.appendChild(el("div", "sub", "Every kit, its items and rules"));
  kt.appendChild(ktg); kt.appendChild(el("span", "mu", "›"));
  kt.addEventListener("click", () => { KitsTab.home(); tab = "Kits"; render(); });
  menu.appendChild(kt);
  const pm = el("button", "item menuitem"); pm.type = "button"; pm.dataset.keep = "more:pmsg";
  const pmg = el("div", "grow"); pmg.appendChild(el("div", "nm", "Player messages")); pmg.appendChild(el("div", "sub", "Open conversations with players, replies waiting first"));
  pm.appendChild(pmg); const waitingNow = PlayerMsg.badge(); if (waitingNow) pm.appendChild(el("span", "pcountbadge", waitingNow)); pm.appendChild(el("span", "mu", "›"));
  pm.addEventListener("click", () => { PlayerMsg.refreshList(); moreView = "pmsg"; render(); });
  menu.appendChild(pm);
  const fp = el("button", "item menuitem"); fp.type = "button";
  const fg = el("div", "grow"); fg.appendChild(el("div", "nm", "Find a player")); fg.appendChild(el("div", "sub", "Anyone who has joined, online or not: items, graves, notes"));
  fp.appendChild(fg); fp.appendChild(el("span", "mu", "›"));
  fp.addEventListener("click", () => { moreView = "find"; render().then(() => FindPlayer.focus()); });
  menu.appendChild(fp);
  const gr = el("button", "item menuitem"); gr.type = "button";
  const gg = el("div", "grow"); gg.appendChild(el("div", "nm", "Graves")); gg.appendChild(el("div", "sub", "A player's graves, online or not; Admins return them"));
  gr.appendChild(gg); gr.appendChild(el("span", "mu", "›"));
  gr.addEventListener("click", () => { moreView = "graves"; render(); });
  menu.appendChild(gr);
  const bn = el("button", "item menuitem"); bn.type = "button";
  const bng = el("div", "grow"); bng.appendChild(el("div", "nm", "Bans")); bng.appendChild(el("div", "sub", "Who is banned from the network, past bans; Admins ban and lift"));
  bn.appendChild(bng); bn.appendChild(el("span", "mu", "›"));
  bn.addEventListener("click", () => { moreView = "bans"; Bans.reset(); render(); });
  menu.appendChild(bn);
  const ip = el("button", "item menuitem"); ip.type = "button";
  const ipg = el("div", "grow"); ipg.appendChild(el("div", "nm", "Items")); ipg.appendChild(el("div", "sub", "The item pictures from an admin's game (/su icons)"));
  ip.appendChild(ipg); ip.appendChild(el("span", "mu", "›"));
  ip.addEventListener("click", () => { moreView = "pictures"; ItemsPage.reset(); render(); });
  menu.appendChild(ip);
  const tg = el("button", "item menuitem"); tg.type = "button";
  const tgg = el("div", "grow"); tgg.appendChild(el("div", "nm", "Tags")); tgg.appendChild(el("div", "sub", me.rank === "MODERATOR" ? "The list of tags and who carries them" : "The list of tags: colours, names, typed by staff"));
  tg.appendChild(tgg); if (me.rank === "MODERATOR") tg.appendChild(el("span", "rk ADMIN", "Admin edits")); tg.appendChild(el("span", "mu", "›"));
  tg.addEventListener("click", () => { moreView = "tags"; render(); });
  menu.appendChild(tg);
  const mk = el("button", "item menuitem"); mk.type = "button";
  const mkg = el("div", "grow"); mkg.appendChild(el("div", "nm", "Market")); mkg.appendChild(el("div", "sub", "The Market Board's lots and the Wanted Board's buy orders"));
  mk.appendChild(mkg); mk.appendChild(el("span", "mu", "›"));
  mk.addEventListener("click", () => { moreView = "market"; Market.reset(); render(); });
  menu.appendChild(mk);
  const bt = el("button", "item menuitem"); bt.type = "button";
  const btg = el("div", "grow"); btg.appendChild(el("div", "nm", "Bounties")); btg.appendChild(el("div", "sub", "The Bounty Board: open bounties, their notes, the history" + (me.bountyPower ? "; add and remove" : "")));
  bt.appendChild(btg); if (!me.bountyPower) bt.appendChild(el("span", "rk ADMIN", "Admin changes")); bt.appendChild(el("span", "mu", "›"));
  bt.addEventListener("click", () => { Bounties.open(null); moreView = "bounties"; render(); });
  menu.appendChild(bt);
  const lt = el("button", "item menuitem"); lt.type = "button";
  const ltg = el("div", "grow"); ltg.appendChild(el("div", "nm", "Lottery")); ltg.appendChild(el("div", "sub", "The pot, who is in, the last wins" + (me.rank === "MODERATOR" ? "" : "; DRAW")));
  lt.appendChild(ltg); if (me.rank === "MODERATOR") lt.appendChild(el("span", "rk ADMIN", "Admin draws")); lt.appendChild(el("span", "mu", "›"));
  lt.addEventListener("click", () => { moreView = "lottery"; Lottery.reset(); render(); });
  menu.appendChild(lt); main.appendChild(menu);
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
  const r = await call("/api/staff");
  area.textContent = "";
  if (!r.ok) {
    area.appendChild(el("div", "err", r.message));
    const again = el("button", "btn sm ghost", "Try again"); again.type = "button"; again.addEventListener("click", () => staffAccounts(area)); area.appendChild(again);
    return;
  }
  const top = el("div", "actions"); const add = el("button", "btn sm", "+ Add staff"); add.type = "button";
  top.appendChild(add); area.appendChild(top);
  const form = addForm(area); form.classList.add("hidden"); add.addEventListener("click", () => form.classList.toggle("hidden"));
  const table = el("table", "staff"); const head = el("tr");
  ["Name", "Rank", "Email", "Status", "Last login", ""].forEach((h) => head.appendChild(el("th", null, h)));
  table.appendChild(head);
  const list = el("div", "list stafflist");
  r.staff.forEach((s, at) => {
    const [st, cls] = status(s, r.now);
    const last = s.lastLogin ? new Date(s.lastLogin).toLocaleString([], { dateStyle: "short", timeStyle: "short" }) : "never";
    const tr = el("tr");
    const nmc = el("td", "nm"); const nmw = el("span", "stname"); nmw.appendChild(staffFace(s.mcName, 20)); nmw.appendChild(el("span", null, s.mcName)); nmc.appendChild(nmw); tr.appendChild(nmc); // D291: name and face
    const rk = el("td"); rk.appendChild(el("span", "rk " + s.rank, s.rankLabel)); if (s.itemsPower) rk.appendChild(el("span", "pw", "ITEMS")); if (s.bankPower) rk.appendChild(el("span", "pw", "BANK")); if (s.bountyPower) rk.appendChild(el("span", "pw", "BOUNTY")); tr.appendChild(rk);
    tr.appendChild(el("td", "mu", s.email)); tr.appendChild(el("td", cls, st)); tr.appendChild(el("td", "mu", last));
    const m = el("td"); m.appendChild(menu(s, area)); tr.appendChild(m); table.appendChild(tr);
    const it = el("div", "item"); const g = el("div", "grow"); const n = el("div", "nm", s.mcName + " "); n.appendChild(el("span", "rk " + s.rank, s.rankLabel)); if (s.itemsPower) n.appendChild(el("span", "pw", "ITEMS")); if (s.bankPower) n.appendChild(el("span", "pw", "BANK")); if (s.bountyPower) n.appendChild(el("span", "pw", "BOUNTY"));
    g.appendChild(n); g.appendChild(el("div", "sub", s.email)); g.appendChild(el("div", "sub " + cls, st)); it.appendChild(g); it.appendChild(menu(s, area, r.staff.length > 3 && at >= r.staff.length - 2)); list.appendChild(it);
  });
  area.appendChild(table); area.appendChild(list);
  area.appendChild(el("div", "note", "Owners are added and reset from the panel's console. Every change here is written to the panel's log."));
}

// a staff member's face: their own head while they are in game, else a plain one
function staffFace(name, size) {
  const p = live.players.find((x) => x.name.toLowerCase() === String(name).toLowerCase());
  return Players.face(p || { name: String(name) }, size);
}

function menu(s, area, up) {
  const box = el("div", "menu");
  if (s.you || s.rank === "OWNER") return box;
  const b = el("button", null, "⋯"); b.type = "button"; b.title = "Actions"; const pop = el("div", "pop hidden" + (up ? " up" : ""));
  const acts = []; let busy = false;
  if (s.lockedUntil) acts.push(["Unlock now", "unlock"]);
  acts.push([s.rank === "ADMIN" ? "Make Moderator" : "Make Admin", "rank"]);
  if (s.rank === "MODERATOR") acts.push([s.itemsPower ? "May add and remove items ✓ (tap to take back)" : "May add and remove items", "items"]); // D335, D363
  if (s.rank === "MODERATOR") acts.push([s.bankPower ? "May refund and cancel ✓ (tap to take back)" : "May refund and cancel (bank & market)", "bank"]); // D504
  if (s.rank === "MODERATOR") acts.push([s.bountyPower ? "May add and remove bounties ✓ (tap to take back)" : "May add and remove bounties", "bounty"]); // D527
  acts.push(["Reset authenticator", "reset-code"], ["Reset whole login", "reset-login"], ["Remove", "remove", 1]);
  acts.forEach(([label, action, danger]) => {
    const x = el("button", danger ? "danger" : "", label); x.type = "button";
    let armed = 0;
    x.addEventListener("click", async () => {
      if (busy) return;
      if (Date.now() - armed > 3000) { armed = Date.now(); x.textContent = label + ": sure?"; unarm(x, 3000, label); return; } // two taps within 3 s
      busy = true; pop.querySelectorAll("button").forEach((k) => { k.disabled = true; });
      const r = await call("/api/staff/action", { id: s.id, action, rank: s.rank === "ADMIN" ? "MODERATOR" : "ADMIN", on: action === "bank" ? !s.bankPower : action === "bounty" ? !s.bountyPower : !s.itemsPower }, true);
      busy = false; armed = 0; x.textContent = label; pop.querySelectorAll("button").forEach((k) => { k.disabled = false; });
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
    if (go.disabled) return; // one account per press, though Enter is pressed twice
    go.disabled = true;
    const r = await call("/api/staff/add", { email: email.value, mcName: name.value, rank: rank.value }, true);
    go.disabled = false;
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
  const main = $("main");
  ChatView.unmount(main); StaffView.unmount(main); MapView.unmount(main); Totems.unmount(main); Worlds.unmount(main); ShopsTab.unmount(main); KitsTab.unmount(main); main.textContent = "";
  main.classList.toggle("chatmain", tab === "Chat");
  main.classList.toggle("staffmain", tab === "Staff");
  if (tab !== "More") moreView = "home";
  placeChat();
  nav();
  AlertsView.banner(); // the alert strip above every tab (SU-168)
  if (tab === "Players") { Players.view(main, live); Players.detail(live); }
  else {
    Players.leave();
    if (tab === "More") await more(main);
    else if (tab === "Staff") StaffView.mount(main);
    else if (tab === "Map") MapView.mount(main); // SU-47
    else if (tab === "Totems") Totems.mount(main); // SU-99
    else if (tab === "Worlds") Worlds.mount(main); // SU-155
    else if (tab === "Shops") ShopsTab.mount(main); // SU-161
    else if (tab === "Kits") KitsTab.mount(main); // SU-172
    else if (tab !== "Chat") main.appendChild(el("div", "note", tab + " is not part of the panel yet."));
  }
}

// Where the chat is drawn (D292, D294): the Chat tab itself; else, on a computer, the column on the right (over 1300 px
// unless folded away, 900 to 1300 px behind the CHAT button); on a phone nowhere (the tab's badge counts new lines).
// Its width (SU-80; D557, D558, D561, D562, D566, D567): dragged by the grip on its left edge, 280 px to half the window,
// a double-click back to 360; under 280 it folds into the CHAT tab, which sits on its edge on every computer screen.
// The width and a wide screen's fold are kept in this browser; a smaller window narrows it without forgetting.
let chatOpen = false;
const CHAT_KEY = "bsp.chat", CHAT_MIN = 280, CHAT_START = 360;
let chatPref = loadChat();
function loadChat() {
  try {
    const s = JSON.parse(localStorage.getItem(CHAT_KEY) || "null");
    if (s && typeof s.w === "number" && isFinite(s.w)) return { w: Math.max(CHAT_MIN, s.w), folded: !!s.folded };
  } catch (e) { /* storage refused: 360 px, open */ }
  return { w: CHAT_START, folded: false };
}
function saveChat() { try { localStorage.setItem(CHAT_KEY, JSON.stringify(chatPref)); } catch (e) { /* kept for this page only */ } }
const chatWidth = () => Math.round(Math.max(CHAT_MIN, Math.min(chatPref.w, innerWidth / 2)));
const wideScreen = () => matchMedia("(min-width: 1300px)").matches;
function placeChat() {
  const col = $("chatcol"), wide = wideScreen(), mid = matchMedia("(min-width: 900px)").matches;
  const shown = tab !== "Chat" && (wide ? !chatPref.folded : mid && chatOpen);
  document.body.classList.toggle("chattab", tab === "Chat");
  document.body.classList.toggle("chatfold", wide && chatPref.folded);
  document.documentElement.style.setProperty("--chatw", chatWidth() + "px");
  col.classList.toggle("open", mid && !wide && chatOpen && tab !== "Chat");
  $("chatbtn").classList.toggle("on", shown);
  $("chatbtn").title = shown ? "Fold the chat away" : "Open the chat";
  $("chatgrip").classList.toggle("on", shown);
  if (tab === "Chat") { ChatView.unmount(col); col.textContent = ""; ChatView.mount($("main")); return; }
  if (shown) ChatView.mount(col);
  else { ChatView.unmount(col); col.textContent = ""; }
}
function toggleChat() {
  if (wideScreen()) { chatPref.folded = !chatPref.folded; saveChat(); } else chatOpen = !chatOpen;
  placeChat(); nav(); chatMoved();
}
// the Map tab keeps its players window inside the map when the chat changes width
function chatMoved() { if (typeof MapView !== "undefined") MapView.redraw(); }
function chatGrip() {
  const g = $("chatgrip"), tag = $("chattag");
  g.addEventListener("pointerdown", (e) => {
    if (e.button !== 0) return;
    e.preventDefault();
    const start = chatWidth(), sx = e.clientX; let raw = start;
    g.setPointerCapture(e.pointerId); g.classList.add("drag"); document.body.classList.add("chatdrag");
    const wasShown = g.classList.contains("on"); let done = false;
    const move = (m) => {
      raw = start + sx - m.clientX;
      const w = Math.round(Math.max(CHAT_MIN, Math.min(raw, innerWidth / 2)));
      document.documentElement.style.setProperty("--chatw", w + "px");
      tag.textContent = raw < CHAT_MIN ? "let go to fold" : w + " px"; // D562
      tag.style.right = (w + 16) + "px"; tag.style.top = Math.max(8, m.clientY - 12) + "px"; tag.classList.remove("hidden");
    };
    const up = () => {
      if (done) return; done = true; // once, though capture is lost after the release too
      ["pointermove", "pointerup", "pointercancel", "lostpointercapture"].forEach((t) => g.removeEventListener(t, t === "pointermove" ? move : up));
      g.classList.remove("drag"); document.body.classList.remove("chatdrag"); tag.classList.add("hidden");
      if (raw < CHAT_MIN) { if (wideScreen()) chatPref.folded = true; else chatOpen = false; saveChat(); placeChat(); nav(); chatMoved(); return; } // folded; the width before the drag kept (D558)
      if (raw !== start) chatPref.w = Math.round(Math.min(raw, innerWidth / 2));
      saveChat(); setChatWidth(wasShown);
    };
    g.addEventListener("pointermove", move); ["pointerup", "pointercancel", "lostpointercapture"].forEach((t) => g.addEventListener(t, up));
  });
  g.addEventListener("dblclick", () => { chatPref.w = CHAT_START; saveChat(); setChatWidth(true); });
}
// a new width for a chat already shown: only its width changes, never drawn again, so where it was read stays (audit)
function setChatWidth(shown) {
  if (!shown) { placeChat(); nav(); chatMoved(); return; }
  document.documentElement.style.setProperty("--chatw", chatWidth() + "px");
  chatMoved();
}
let resizing = 0;
addEventListener("resize", () => { clearTimeout(resizing); resizing = setTimeout(() => { placeChat(); nav(); chatMoved(); }, 150); });

// a name tapped in chat opens that player's sheet (D304)
function openPlayerSheet(id) { tab = "Players"; Players.open(id); render(); }

// the Graves key in a player's sheet or card opens the Graves page for them (D297)
function openGraves(name) { tab = "More"; moreView = "graves"; render().then(() => Graves.look(name)); }
// the market line in a player's sheet opens the market filtered to them (D405, D415 R2)
function openMarket(p) { tab = "More"; moreView = "market"; Market.forPlayer(p); render(); }
// the bounties line in a player's sheet opens the board filtered to them (D528)
function openBounties(p) { Bounties.open({ id: p.id, name: p.name }); tab = "More"; moreView = "bounties"; render(); }
// the Bank & Market key in a player's sheet opens their bank and market (D517)
function openBank(p) { Bank.open({ id: p.id, name: p.name }); tab = "More"; moreView = "bank"; render(); }
// a player who is not online opens their offline card on the Players tab (D660, R5)
function openOffline(p) { tab = "Players"; Players.openOffline(p); render(); }

// A press under way: the live feed's redraw waits for it to end, so a tap is never lost on a key drawn again under it.
let pressing = 0, tickWaiting = false;
addEventListener("pointerdown", () => { pressing = Date.now(); }, true);
["pointerup", "pointercancel"].forEach((t) => addEventListener(t, () => {
  pressing = 0;
  if (tickWaiting) { tickWaiting = false; setTimeout(() => App.tick(), 60); }
}, true));

const App = {
  // Drawn again for staff or for an answer that arrived: the Players tab and an open player, or the Map's card, or an
  // offline player's page (More › Find a player: their items or notes). Focus, caret and scrolling are kept.
  refresh() {
    if (tab === "More") { if (moreView === "find" || moreView === "pmsg") App.refreshMore(); return; }
    if (tab === "Map") { MapView.redraw(); return; }
    if (tab === "Totems") { Totems.redraw(); return; } // a player's notes read (NOTES under their row)
    if (tab !== "Players") return;
    const h = hold();
    Players.view($("main"), live); Players.detail(live);
    give(h);
  },
  // The live feed (at most every 5 s): the Players tab, with only what the feed changes in an open player's sheet, or the
  // Map tab. More pages are never drawn again by it.
  tick() {
    if (pressing && Date.now() - pressing < 8000) { tickWaiting = true; return; }
    if (tab === "Map") { MapView.live(live); return; }
    if (tab !== "Players") return;
    const h = hold();
    Players.view($("main"), live); Players.detail(live, true);
    give(h);
  },
  async refreshMore() {
    if (tab !== "More") return; // an answer arriving after staff moved to another tab draws nothing (audit)
    const g = ++moreGen, h = hold(), main = $("main");
    main.textContent = ""; await more(main);
    if (g === moreGen && tab === "More") give(h);
  },
};
let moreGen = 0;
// offline players (SU-116): a new read draws the Players tab or the map again; read each minute while either shows them
Offline.onChange(() => { if (tab === "Players") Players.offlineChanged(live); else if (tab === "Map") MapView.redraw(); });
Offline.whenShown(() => tab === "Players" || (tab === "Map" && typeof MapView.offlineOn === "function" && MapView.offlineOn()));

// The live feed. The browser retries by itself after a network error, but not after a refusal (an ended login, the
// Cloudflare session running out, too many pages): then the page says so, checks why and reconnects or goes to log in
// (audit). The panel sends something at least every 20 s (a "ping" when nothing else): 45 s of silence is a dead line,
// such as a phone in a tunnel, and is treated as one.
let connected = false, feed = null, lastEvent = 0, feedErrors = 0;
const SILENT_MS = 45000;
function drop(es) { try { es.close(); } catch (e) { /* closed */ } if (feed === es) feed = null; }
function connect() {
  if (feed) drop(feed);
  const es = new EventSource("/api/live");
  feed = es; lastEvent = Date.now(); feedErrors = 0;
  const on = (name, fn) => es.addEventListener(name, (e) => { if (feed !== es) return; lastEvent = Date.now(); fn(e); });
  on("open", () => {
    feedErrors = 0; offline(false);
    if (connected) afterGap(); // what was sent while the page was away (audit)
    connected = true;
  });
  on("ping", () => { /* the line is alive */ });
  let servers = "";
  on("live", (e) => {
    live = JSON.parse(e.data);
    Offline.onLive(live); // someone who left is read again once their logout is written (R8)
    staffOnline();
    Lottery.onLive(live); // a draw counting somewhere (D411)
    Worlds.onLive(live); // a server's regions changed: WORLDS and the map read them again (D781)
    ShopsTab.onLive(live); // a shop or a stall changed, or a minute passed: SHOPS reads again (D811)
    KitsTab.onLive(live); // a kit changed, or a minute passed: KITS reads again (D845)
    App.tick();
    const now = live.servers.map((s) => s.server + ":" + s.count).join(",");
    if (now !== servers) { servers = now; ChatView.serversChanged(); } // the server chips and the TO list
  });
  on("chat", (e) => ChatView.incoming(JSON.parse(e.data).lines)); // a batch, drawn once
  on("room", (e) => StaffView.room(JSON.parse(e.data)));
  on("messages", () => StaffView.messagesChanged());
  on("notes", (e) => Notes.changed(JSON.parse(e.data).player));
  on("tags", () => Tags.changed());
  on("maps", () => { MapView.mapsChanged(); Offline.mapsChanged(); }); // an Owner changed the maps shown (D583)
  on("lottery", (e) => Lottery.event(JSON.parse(e.data))); // a draw began or ended (D411)
  on("totems", (e) => Totems.event(JSON.parse(e.data))); // the board read, a steal starting or ending (SU-99)
  on("pmsg", (e) => PlayerMsg.event(JSON.parse(e.data))); // a private line to or from a player (SU-111)
  on("alerts", () => AlertsView.load()); // a server checked, out or back (SU-168)
  on("ended", () => { drop(es); location.href = "/login"; });
  // this staff member opened the panel on more pages than the limit: this, the oldest, stops until asked (audit)
  on("replaced", () => { drop(es); replaced(); });
  es.onerror = () => {
    if (feed !== es) return;
    offline(true); feedErrors++;
    // closed by the browser, or retrying without getting anywhere (a login page in the way): check why
    if (es.readyState === EventSource.CLOSED || feedErrors >= 3) { drop(es); setTimeout(() => recheck(), 3000); }
  };
}
setInterval(() => {
  if (feed && Date.now() - lastEvent > SILENT_MS) { drop(feed); offline(true); recheck(); }
}, 5000);

// after a gap in the feed: the chat, the room and messages, tags, the lottery and notes read again
function afterGap() {
  ChatView.resync(); StaffView.resync(); Tags.changed(); Lottery.resync(); Notes.reset(); MapView.mapsChanged(); Totems.resync(); PlayerMsg.resync();
  AlertsView.load();
}

function replaced() {
  offline(true);
  const b = $("offline"); b.textContent = "Open on another page or device: tap here to use this one."; b.classList.add("tap");
  b.tabIndex = 0; b.setAttribute("role", "button"); placeGold();
  const use = () => {
    b.onclick = null; b.onkeydown = null; b.classList.remove("tap"); b.removeAttribute("role"); b.removeAttribute("tabindex");
    b.textContent = "Not live: reconnecting…"; connect();
  };
  b.onclick = use;
  b.onkeydown = (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); use(); } };
}

function offline(on) {
  let b = $("offline");
  if (!b) { b = el("div", "offline", "Not live: reconnecting…"); b.id = "offline"; document.body.appendChild(b); }
  if (on && !b.classList.contains("tap")) b.textContent = "Not live: reconnecting…";
  b.classList.toggle("hidden", !on);
  document.body.classList.toggle("notlive", on);
  placeGold();
}
// the lottery's gold line sits below the "Not live" line (however many lines that takes), never over it (audit)
function placeGold() {
  const g = $("goldline"), b = $("offline");
  if (!g) return;
  g.style.top = b && !b.classList.contains("hidden") ? Math.round(b.getBoundingClientRect().bottom + 6) + "px" : "";
}

let rechecking = false;
async function recheck() {
  if (rechecking || feed) return;
  rechecking = true;
  const ctl = new AbortController(), t = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    const r = await fetch("/api/me", { credentials: "same-origin", redirect: "manual", signal: ctl.signal });
    clearTimeout(t); rechecking = false;
    if (r.type === "opaqueredirect" || r.status === 0) { location.reload(); return; } // Cloudflare wants its login again
    if (r.status === 401) { location.href = "/login"; return; }
    connect();
  } catch (e) {
    clearTimeout(t); rechecking = false;
    setTimeout(() => recheck(), 10000);
  }
}

// The start: who is logged in. A failed first read says so and tries again, never a blank page.
async function whoAmI() {
  let wait = 2000;
  for (;;) {
    try { return await api("/api/me"); } catch (e) {
      if (e.kind === "out") return null;
      const main = $("main"); main.textContent = "";
      main.appendChild(el("div", "err", failText(e) + " Trying again in " + Math.round(wait / 1000) + " s."));
      const now = el("button", "btn sm ghost back", "Try now"); now.type = "button"; main.appendChild(now);
      await new Promise((done) => { const t = setTimeout(done, wait); now.addEventListener("click", () => { clearTimeout(t); done(); }); });
      main.textContent = ""; main.appendChild(el("div", "note", "Reading your login…"));
      wait = Math.min(wait * 2, 30000);
    }
  }
}

(async () => {
  me = await whoAmI();
  if (!me) return;
  $("main").textContent = "";
  const m = $("me"); m.textContent = ""; m.appendChild(el("span", "nm", me.mcName + " ")); m.appendChild(el("span", "rk " + me.rank, me.rankLabel));
  if (me.rank === "MODERATOR" && me.itemsPower) m.appendChild(el("span", "pw", "ITEMS"));
  if (me.rank === "MODERATOR" && me.bankPower) m.appendChild(el("span", "pw", "BANK"));
  if (me.rank === "MODERATOR" && me.bountyPower) m.appendChild(el("span", "pw", "BOUNTY"));
  $("chatbtn").addEventListener("click", toggleChat);
  chatGrip();
  await Tags.load();
  Pictures.load().then((c) => { if (c) App.refresh(); }); // item pictures and names (SU-41), when there are any
  await render();
  ChatView.start();
  StaffView.start();
  PlayerMsg.start(); // the red count on More
  AlertsView.start(); // server alerts (SU-168)
  connect();
})();
