"use strict";
// Server alerts on the panel (SU-168, look BL A confirmed in D835: R4 and R5 of tools/preview/alerts_refined.html).
// A strip above every tab while an alert is open (red; blue for planned restarts in their 5 minutes; gold for a server
// being checked, on WORLDS only), the state on each WORLDS tile, and RECENT ALERTS under the WORLDS grid. Read from
// /api/alerts at the start, on the live feed's "alerts" event, after a gap, and every minute.
const AlertsView = (() => {
  let data = null, loading = false, again = false, testMsg = "", testOk = false, showAll = false;
  const listeners = [];
  const RED = ["FROZEN", "CRASHED", "NO_ANSWER", "SILENT", "NOT_BACK", "PANEL"];
  const LABEL = { FROZEN: "FROZEN", CRASHED: "CRASHED", NO_ANSWER: "NO ANSWER", SILENT: "NOT SENDING", NOT_BACK: "NOT BACK", SLOW: "SLOW", PANEL: "PANEL",
    RESTARTING: "RESTARTING", CHECKING: "CHECKING" };
  const CHIP = { FROZEN: "alc-frozen", CRASHED: "alc-crashed", NO_ANSWER: "alc-crashed", SILENT: "alc-check", NOT_BACK: "alc-late", SLOW: "alc-slow", PANEL: "alc-frozen",
    RESTARTING: "alc-restart", CHECKING: "alc-check" };

  const two = (n) => String(n).padStart(2, "0");
  function time(ms) { const d = new Date(ms); return two(d.getHours()) + ":" + two(d.getMinutes()) + ":" + two(d.getSeconds()); }
  function hm(ms) { const d = new Date(ms); return two(d.getHours()) + ":" + two(d.getMinutes()); }
  function day(ms) {
    const d = new Date(ms), now = new Date(), start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    if (ms >= start) return "today " + hm(ms);
    if (ms >= start - 86400000) return "yesterday " + hm(ms);
    if (ms >= start - 6 * 86400000) return d.toLocaleDateString("en-GB", { weekday: "short" }) + " " + hm(ms);
    return d.toLocaleDateString("en-GB", { day: "numeric", month: "short" }) + " " + hm(ms);
  }
  function length(ms) {
    const s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + " s";
    if (s < 3600) return Math.floor(s / 60) + " min";
    return Math.floor(s / 3600) + " h" + (Math.floor(s % 3600 / 60) ? " " + Math.floor(s % 3600 / 60) + " min" : "");
  }
  function chip(kind, extra) {
    const c = el("span", "alchip " + (CHIP[kind] || "alc-check"), LABEL[kind] || kind);
    if (extra) c.appendChild(extra);
    return c;
  }
  function dots(n, of) { const d = el("span", "aldots"); for (let i = 0; i < of; i++) d.appendChild(el("i", i < n ? "on" : "")); return d; }
  const portWords = { OPEN: "port open", CLOSED: "port closed", NO_ANSWER: "no answer", UNKNOWN: "no address" };

  async function load() {
    if (loading) { again = true; return; }
    loading = true;
    const r = await call("/api/alerts", {});
    loading = false;
    if (r && r.ok) { data = r; changed(); }
    if (again) { again = false; load(); }
  }
  function changed() { banner(); listeners.forEach((f) => { try { f(); } catch (e) { /* one view's trouble is its own */ } }); }

  // ------------------------------------------------------------------ the strip above every tab (R4)
  function banner() {
    const bar = $("alertbar"); if (!bar) return;
    bar.textContent = "";
    const onWorlds = typeof tab !== "undefined" && tab === "Worlds";
    if (data) {
      (data.open || []).forEach((a) => bar.appendChild(alertLine(a, onWorlds)));
      const states = data.servers || {}, names = Object.keys(states);
      const restarting = names.filter((n) => states[n].state === "RESTARTING");
      if (restarting.length) {
        const b = el("div", "albanner ab-blue"); b.appendChild(chip("RESTARTING"));
        const t = el("span", "grow");
        if (restarting.length === 1) {
          const s = states[restarting[0]];
          t.appendChild(el("b", null, restarting[0]));
          t.appendChild(document.createTextNode(" said it was stopping at " + time(s.since) + " (planned). An alert if it is not back by " + time(s.until) + "."));
        } else {
          t.appendChild(el("b", null, restarting.length + " servers"));
          t.appendChild(document.createTextNode(" are restarting (planned): " + restarting.join(", ") + ". An alert for any not back within 5 minutes."));
        }
        b.appendChild(t); bar.appendChild(b);
      }
      const checking = names.filter((n) => states[n].state === "CHECKING");
      if (onWorlds && checking.length) {
        const b = el("div", "albanner ab-gold"); b.appendChild(chip("CHECKING"));
        const t = el("span", "grow");
        checking.forEach((n, i) => {
          if (i) t.appendChild(document.createTextNode("; "));
          t.appendChild(el("b", null, n));
          t.appendChild(document.createTextNode(" quiet since " + time(states[n].since) + ", check " + states[n].checks + " of " + states[n].of));
        });
        t.appendChild(document.createTextNode("."));
        b.appendChild(t); bar.appendChild(b);
      }
    }
    bar.classList.toggle("hidden", !bar.children.length);
    document.documentElement.style.setProperty("--alerth", bar.children.length ? bar.offsetHeight + "px" : "0px");
  }
  function alertLine(a, onWorlds) {
    const b = el("div", "albanner" + (a.kind === "SLOW" ? " ab-gold" : "")); b.appendChild(chip(a.kind));
    const t = el("span", "grow");
    if (a.kind === "PANEL") {
      t.appendChild(el("b", null, "The panel"));
      t.appendChild(document.createTextNode(" has heard no game server since " + time(a.since) + " (" + length(Date.now() - a.since) + "). Its own Redis or database link is the likely cause; server alerts wait until it hears one."));
    } else {
      t.appendChild(el("b", null, a.server));
      const said = {
        FROZEN: " stopped sending at " + time(a.since) + " (" + length(Date.now() - a.since) + " ago); its port still answers. Restart it in Pterodactyl.",
        CRASHED: " stopped sending at " + time(a.since) + " (" + length(Date.now() - a.since) + " ago); its port is closed: crashed, or killed.",
        NO_ANSWER: " stopped sending at " + time(a.since) + " (" + length(Date.now() - a.since) + " ago); its address does not answer.",
        SILENT: " stopped sending at " + time(a.since) + " (" + length(Date.now() - a.since) + " ago); the panel has no address to check it.",
        NOT_BACK: " said it was stopping at " + time(a.since) + " and has not come back (" + length(Date.now() - a.since) + ").",
        SLOW: " has been very slow since " + time(a.since) + ".",
      }[a.kind] || " is out since " + time(a.since) + ".";
      t.appendChild(document.createTextNode(said));
    }
    t.appendChild(document.createTextNode(" " + emailWords(a, true)));
    b.appendChild(t);
    if (!onWorlds) {
      const k = el("button", "alkey", "WORLDS"); k.type = "button";
      k.addEventListener("click", () => { if (typeof Worlds !== "undefined") Worlds.home(); tab = "Worlds"; render(); });
      b.appendChild(k);
    }
    return b;
  }
  function emailWords(a, sentence) {
    if (a.emailed > 0) return sentence ? "Emailed " + hm(a.emailed) + "." : "emailed " + hm(a.emailed);
    if (a.mailing) return sentence ? "Emailing…" : "emailing…";
    if ((a.emailNote || "").startsWith("email failed")) return sentence ? "The email failed." : "email failed";
    if (a.emailNote) return sentence ? "Not emailed." : "not emailed";
    return "";
  }

  // ------------------------------------------------------------------ a WORLDS tile's state (R4)
  /** {cls, dot, chip, line} for a server's tile, or null when it is plainly fine. */
  function tileOf(server) {
    const s = data && data.servers ? data.servers[server] : null;
    if (!s) return null;
    if (s.state === "RESTARTING") return { cls: "al-blue", dot: "ad-blue", chip: chip("RESTARTING"), line: "Planned stop " + time(s.since) + " · an alert if not back by " + time(s.until), lineCls: "al-blue" };
    if (s.state === "CHECKING") return { cls: "al-gold", dot: "ad-gold", chip: chip("CHECKING", dots(s.checks, s.of)), line: "Quiet since " + time(s.since) + " · check " + s.checks + " of " + s.of, lineCls: "al-gold" };
    if (s.state === "SLOW") return { cls: "al-gold", dot: "ad-gold", chip: chip("SLOW"), line: "Very slow since " + time(s.since), lineCls: "al-gold" };
    if (s.state === "NOT_BACK") return { cls: "al-red", dot: "ad-red", chip: chip("NOT_BACK"), line: "Stopped " + time(s.since) + ", not back · " + portWords[s.port], lineCls: "al-red" };
    return { cls: "al-red", dot: "ad-red", chip: chip(s.state), line: "Silent since " + time(s.since) + " · " + (portWords[s.port] || "no address"), lineCls: "al-red" };
  }

  // ------------------------------------------------------------------ RECENT ALERTS under the WORLDS grid (R4, R5)
  function recentBox(who) {
    const box = el("div", "alhist");
    const head = el("div", "alhh");
    head.appendChild(el("span", "wdtitle alhtitle", "RECENT ALERTS"));
    head.appendChild(el("span", "mu wdsmall", "the last 7 days"));
    head.appendChild(el("span", "grow"));
    const owner = who && who.rank === "OWNER";
    if (owner && data && data.email) {
      head.appendChild(el("span", "mu wdsmall" + (data.email.on ? "" : " alwarn"), data.email.on
        ? "Email on · to " + data.email.to + (data.email.to === 1 ? " address" : " addresses") : "Email off: " + data.email.why));
      const k = el("button", "wdkey ghost", "SEND TEST EMAIL"); k.type = "button"; k.dataset.keep = "al:test";
      k.disabled = !data.email.on;
      k.addEventListener("click", async () => {
        k.disabled = true; k.textContent = "SENDING…";
        const r = await call("/api/alerts/test", {}, true);
        testOk = !!r.ok; testMsg = r.message || (r.ok ? "Test email sent." : "The test email did not go.");
        changed();
      });
      head.appendChild(k);
    }
    box.appendChild(head);
    if (testMsg) { const m = el("div", testOk ? "okm" : "err", testMsg); m.classList.add("almsg"); box.appendChild(m); }
    if (!data) { box.appendChild(el("div", "note alrow", "Reading the alerts…")); return box; }
    const rows = data.recent || [];
    if (!rows.length) box.appendChild(el("div", "note alrow", "No alerts in the last 7 days."));
    rows.slice(0, showAll ? 50 : 8).forEach((a) => box.appendChild(row(a, owner)));
    if (rows.length > 8) {
      const k = el("button", "wdkey link alall", showAll ? "FEWER" : "ALL " + rows.length); k.type = "button";
      k.addEventListener("click", () => { showAll = !showAll; changed(); });
      box.appendChild(k);
    }
    return box;
  }
  function row(a, owner) {
    const r = el("div", "alrow");
    r.appendChild(el("span", "alt", day(a.since)));
    r.appendChild(el("span", "als", a.kind === "PANEL" ? "(the panel)" : a.server));
    const what = el("span", "alw"); what.appendChild(chip(a.kind));
    const len = (a.back || a.ended || Date.now()) - a.since;
    const words = a.ended === 0 ? "still out · " + length(Date.now() - a.since)
      : a.back > 0 ? ({ FROZEN: "port open", CRASHED: "port closed", NO_ANSWER: "no answer", SILENT: "no address", NOT_BACK: "planned stop", SLOW: "slow", PANEL: "heard no server" }[a.kind] || "") + " · " + length(len)
      : (a.note || "ended");
    what.appendChild(el("span", "mu wdsmall", " " + words)); r.appendChild(what);
    const mail = el("span", "alm" + ((a.emailNote || "").startsWith("email failed") ? " alwarn" : ""), emailWords(a, false));
    if (owner && a.emailNote) mail.title = a.emailNote; // the mail server's reason, for Owners (R4)
    r.appendChild(mail);
    r.appendChild(el("span", "alm", a.back > 0 ? "back " + hm(a.back) : ""));
    return r;
  }

  function start() {
    load();
    setInterval(load, 60000); // in case an event was missed
    setInterval(() => { if (data && (data.open || []).length) banner(); }, 30000); // "4 min ago" moves on
    addEventListener("resize", () => { const bar = $("alertbar"); if (bar) document.documentElement.style.setProperty("--alerth", bar.children.length ? bar.offsetHeight + "px" : "0px"); });
  }
  function onChange(f) { listeners.push(f); }
  return { start, load, banner, tileOf, recentBox, onChange };
})();
