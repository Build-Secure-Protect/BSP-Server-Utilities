"use strict";
// Offline players (SU-116; D653 to D671): everyone who ever joined and is not online, the most recently seen first, read
// from the panel a page at a time and found by any part of the name; where each was last seen (the game servers' signed
// record). Shared by the Players tab's OFFLINE group and card and the Map tab's offline rows. Read when the tab opens, a
// few seconds after anyone leaves the network (their logout is written first), and every 60 s while shown (D665, R8).
const Offline = (() => {
  const PAGE = 50, LEFT_WAIT_MS = 4000, EVERY_MS = 60000, FOLD_KEY = "bsp.offlineFolded";
  // q: the words being read (or read); listQ: the words the list in hand was read with
  const st = { q: "", listQ: "", items: [], total: 0, read: false, loading: false, error: "", problem: "", shown: 0, gen: 0, at: 0 };
  const cards = new Map(); // id -> the newest record of one player (from a page or /api/players/seen)
  const maps = new Map(); // "server|dim" -> true / false / null (whether the Map tab shows that world), "?" while asked
  const listeners = [];
  let onlineIds = new Set(), leftTimer = 0;

  const desk = () => matchMedia("(min-width: 900px)").matches;
  const step = () => (desk() ? 50 : 20); // a computer shows 50 at a time, a phone 20 (D663, R3)
  function changed() { listeners.forEach((f) => { try { f(); } catch (e) { /* one view's trouble never stops the others */ } }); }

  // ---------------------------------------------------------------- reading
  // The pages read so far, again from the start (the first read, a search, a leave, the minute); a newer read wins, and a
  // SHOW MORE under way gives way to it (audit of SU-116: both wrote the list, a click was lost).
  const MAX_PAGES = 20; // 1,000 rows read again at most; more than that are shown from SHOW MORE as they come
  async function load(keepShown) {
    const g = ++st.gen, q = st.q, want = Math.max(keepShown ? st.shown : 0, step());
    const pages = Math.min(MAX_PAGES, Math.ceil(want / PAGE)); // every page shown, so the list never shrinks under staff (audit)
    st.loading = true; changed();
    let items = [], total = 0, problem = "", error = "";
    for (let i = 0; i < pages; i++) {
      const r = await call("/api/players/offline", { q, offset: i * PAGE, limit: PAGE });
      if (g !== st.gen) return; // a newer read is under way
      if (!r.ok) { error = r.message || "The offline players could not be read."; break; }
      const have = new Set(items.map((p) => p.id));
      (r.players || []).forEach((p) => { if (!have.has(p.id)) items.push(p); });
      total = r.total || 0; problem = r.problem || "";
      if (items.length >= total || (r.players || []).length < PAGE) break;
    }
    st.loading = false; st.read = true; st.at = Date.now(); st.error = error;
    if (!error) { st.items = items; st.total = total; st.problem = problem; st.listQ = q; items.forEach((p) => cards.set(p.id, Object.assign({}, cards.get(p.id), p))); }
    st.shown = Math.min(Math.max(want, step()), Math.max(st.items.length, step()));
    changed();
  }
  // SHOW MORE: the next rows, read when not read yet. It starts 5 rows back and drops the ones it has, so players who logged on
  // between the pages (each moving the rest up one) never make one skipped (audit).
  async function more() {
    if (st.loading) return; // one read at a time; the key is off meanwhile
    st.shown += step();
    if (st.shown <= st.items.length || st.items.length >= st.total) { changed(); return; }
    const g = st.gen, q = st.listQ || "", from = Math.max(0, st.items.length - 5);
    st.loading = true; changed();
    const r = await call("/api/players/offline", { q, offset: from, limit: PAGE });
    if (g !== st.gen) return; // a newer read (a search, a leave, the minute) took over: it reads as many rows as are shown
    st.loading = false;
    if (!r.ok) st.error = r.message || "The offline players could not be read.";
    else {
      const have = new Set(st.items.map((p) => p.id));
      (r.players || []).forEach((p) => { if (!have.has(p.id)) { st.items.push(p); cards.set(p.id, Object.assign({}, cards.get(p.id), p)); } });
      st.total = r.total || st.total; st.problem = r.problem || st.problem;
    }
    changed();
  }
  // the search box: read 250 ms after the last key (R2). The list keeps its old words until the new read lands, so SHOW MORE and
  // the count never mix the old list with the new name (audit).
  let searchTimer = 0, wantQ = "";
  function search(q) {
    const v = String(q || "").trim();
    if (v === wantQ) return;
    wantQ = v; clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { st.q = wantQ; st.gen++; load(false); }, 250);
  }
  /** The first read, when a view that shows the list is opened. */
  function ensure() { if (!st.read && !st.loading) load(false); }

  // ---------------------------------------------------------------- kept up to date (R8)
  // the live feed: someone gone from it is read again once their logout is written; someone back leaves the list at once
  function onLive(l) {
    const now = new Set((l.players || []).map((p) => String(p.id).toLowerCase()));
    let left = false;
    onlineIds.forEach((id) => { if (!now.has(id)) left = true; });
    const first = onlineIds.size === 0;
    onlineIds = now;
    if (left && !first && st.read) { clearTimeout(leftTimer); leftTimer = setTimeout(() => { load(true); refreshCards(); }, LEFT_WAIT_MS); }
  }
  // every minute while a view showing offline players is on screen
  let shownNow = () => false;
  setInterval(() => { if (st.read && !document.hidden && shownNow()) load(true); }, EVERY_MS);
  function whenShown(fn) { shownNow = fn; }

  // ---------------------------------------------------------------- one player (the card, the map)
  const asking = new Set();
  async function fetchOne(id) {
    if (asking.has(id)) return;
    asking.add(id);
    const r = await call("/api/players/seen", { player: id });
    asking.delete(id);
    if (r.ok && r.player) cards.set(id, Object.assign({}, cards.get(id), r.player, { fetched: Date.now(), problem: r.problem || "" }));
    else if (!r.ok) cards.set(id, Object.assign({}, cards.get(id), { fetched: Date.now(), error: r.message || "Their record could not be read." }));
    changed();
  }
  /** The newest record of a player, read when not read in the last minute; {@code hint} (id, name) until it comes. */
  function card(hint) {
    const id = String(hint.id).toLowerCase();
    const c = cards.get(id);
    if (!c || !c.fetched || Date.now() - c.fetched > EVERY_MS) fetchOne(id);
    if (c) c.viewed = Date.now();
    return c ? Object.assign({ name: hint.name }, c) : { id, name: hint.name || "", state: "loading" };
  }
  function remember(p) { const id = String(p.id).toLowerCase(); cards.set(id, Object.assign({ id, name: p.name }, cards.get(id) || {}, { viewed: Date.now() })); }
  // after a leave: only the cards on screen now (the open card, the map's chosen player) are read again, and records of players
  // neither listed nor looked at for 10 minutes are let go (audit: every card ever opened was read again, kept for good)
  function refreshCards() {
    const now = Date.now(), listed = new Set(st.items.map((p) => p.id));
    cards.forEach((c, id) => {
      if (c.viewed && now - c.viewed < 15000) { if (c.fetched) fetchOne(id); }
      else if (!listed.has(id) && (!c.viewed || now - c.viewed > 600000)) cards.delete(id);
    });
  }

  // whether the Map tab shows a world (asked once per world; MapView.canShow reads the list of maps)
  function mapShown(server, dim) {
    const k = server + "|" + dim;
    if (maps.has(k)) return maps.get(k) === "?" ? undefined : maps.get(k);
    maps.set(k, "?");
    if (typeof MapView === "undefined") { maps.set(k, null); return null; }
    MapView.canShow(server, dim).then((ok) => { maps.set(k, ok); changed(); }, () => { maps.set(k, null); changed(); });
    return undefined;
  }
  function mapsChanged() { maps.clear(); changed(); }

  // ---------------------------------------------------------------- what the views draw
  /** The rows to show: those read, without anyone online now, up to the number shown. */
  function rows() { return st.items.filter((p) => !onlineIds.has(String(p.id).toLowerCase())).slice(0, st.shown || step()); }
  function folded() { try { return localStorage.getItem(FOLD_KEY) === "1"; } catch (e) { return false; } }
  function fold(on) { try { localStorage.setItem(FOLD_KEY, on ? "1" : "0"); } catch (e) { /* kept for this page only */ } }

  // ---------------------------------------------------------------- words (R1, R4, R9)
  const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const two = (n) => String(n).padStart(2, "0");
  function clock(at) { const d = new Date(at); return two(d.getHours()) + ":" + two(d.getMinutes()); }
  function dayOf(at) { const d = new Date(at), y = d.getFullYear() !== new Date().getFullYear(); return d.getDate() + " " + MONTHS[d.getMonth()] + (y ? " " + d.getFullYear() : ""); }
  function isYesterday(at) { const d = new Date(at), y = new Date(); y.setDate(y.getDate() - 1); return d.toDateString() === y.toDateString(); }
  /** "12 min", "3 h" (under a day), "yest." (the day before), "4 d" (to 13 days), then "18 Oct" (with the year once not this year). */
  function ago(at) {
    const ms = Math.max(0, Date.now() - at), min = Math.floor(ms / 60000);
    if (min < 60) return Math.max(1, min) + " min";
    if (min < 1440) return Math.floor(min / 60) + " h";
    if (isYesterday(at)) return "yest.";
    const days = Math.max(1, Math.floor(ms / 86400000));
    return days <= 13 ? days + " d" : dayOf(at);
  }
  /** The map's short form: "12m", "3h", "1d", "4d", "2w", "3mo". */
  function agoShort(at) {
    const ms = Math.max(0, Date.now() - at), min = Math.floor(ms / 60000);
    if (min < 60) return Math.max(1, min) + "m";
    if (min < 1440) return Math.floor(min / 60) + "h";
    const d = Math.floor(min / 1440);
    return d < 14 ? d + "d" : d < 60 ? Math.floor(d / 7) + "w" : Math.floor(d / 30) + "mo";
  }
  /** "last seen 12 min ago (21:04)" under a day, "last seen yesterday (22:41)", then "last seen 18 Nov 22:10". */
  function lastSeen(p) {
    if (p.state === "none") return "last seen: not recorded";
    if (p.state === "bad") return "last seen: not known";
    if (!p.place) return "";
    return "last seen " + when(p.place.at, true);
  }
  /** "12 min ago (21:04)" (with {@code ago}) or "21:04" under a day, "yesterday (22:41)", then "18 Nov 22:10". */
  function when(at, withAgo) {
    if (Date.now() - at < 86400000) return withAgo ? ago(at) + " ago (" + clock(at) + ")" : clock(at);
    if (isYesterday(at)) return "yesterday (" + clock(at) + ")";
    return dayOf(at) + " " + clock(at);
  }
  /** A world's word: "nether", "end", "overworld", a modded one by its path. */
  function world(dim) {
    const d = String(dim || "").replace(/^minecraft:/, "");
    return d === "the_nether" ? "nether" : d === "the_end" ? "end" : d === "overworld" ? "overworld" : d.replace(/^.*:/, "").replace(/_/g, " ");
  }
  /** "BSP-Main · nether" (the Overworld left out, as for online players). */
  function where(place) { const w = world(place.dim); return place.server + (w === "overworld" ? "" : " · " + w); }

  return {
    ensure, load: () => load(true), search, more, onLive, card, remember, mapShown, mapsChanged, rows, folded, fold, whenShown,
    onChange: (f) => listeners.push(f), state: () => st, ago, agoShort, lastSeen, when, world, where, clock,
  };
})();
