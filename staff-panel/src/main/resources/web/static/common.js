// Shared by the panel's pages. Text from the network (names, chat) is always set with textContent, never as HTML.
"use strict";
const $ = (id) => document.getElementById(id);
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = text;
  return e;
}

// ---------------------------------------------------------------- asking the panel
// Every request gives up after 20 s (a stalled phone connection would otherwise hold a key for minutes). A failure is
// thrown as an Error with a kind: "out" (logged out, the page goes to /login), "timeout", "network" (nothing came
// back), "server" (the panel answered 5xx), "said" (the panel's own short answer, such as a 403), "proxy" (a page from
// Cloudflare instead of the panel's answer).
const TIMEOUT_MS = 20000;
function failure(kind, message) { const e = new Error(message || kind); e.kind = kind; return e; }
async function api(path, body) {
  const ctl = new AbortController(), timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    let r;
    try {
      r = await fetch(path, {
        method: body === undefined ? "GET" : "POST",
        credentials: "same-origin",
        headers: body === undefined ? {} : { "Content-Type": "application/json", "X-BSP": "1" },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: ctl.signal,
      });
    } catch (e) { throw failure(ctl.signal.aborted ? "timeout" : "network"); }
    if (r.status === 401) { location.href = "/login"; throw failure("out", "logged out"); }
    const type = r.headers.get("Content-Type") || "";
    if (type.startsWith("application/json")) {
      try { return await r.json(); } catch (e) { throw failure(ctl.signal.aborted ? "timeout" : "network"); }
    }
    let text = "";
    try { text = await r.text(); } catch (e) { /* the words are not needed to say what happened */ }
    if (type.includes("html") || /^\s*</.test(text)) throw failure(r.status === 524 ? "timeout" : "proxy"); // Cloudflare's own page: never shown as it is
    if (r.status >= 500) throw failure("server");
    const said = text.trim();
    throw failure(said && said.length <= 200 ? "said" : "server", said);
  } finally {
    clearTimeout(timer);
  }
}
/** What a failed request says. {@code action}: the request changes something, so a lost answer may still have been done. */
function failText(e, action) {
  const k = e && e.kind;
  if (k === "timeout") return action ? "No answer: check before trying again." : "The panel did not answer in time. Try again.";
  if (k === "server") return "The panel answered with an error (it is in the panel's console).";
  if (k === "said") return e.message;
  if (k === "proxy") return action ? "No answer from the panel: check before trying again." : "The panel could not be reached.";
  return "The panel could not be reached.";
}
/** As {@link api}, but a failure comes back as {ok: false, message} instead of being thrown. */
async function call(path, body, action) {
  try { return await api(path, body); } catch (e) { return { ok: false, message: failText(e, action), failed: e.kind || "network" }; }
}

function show(id, on) { $(id).classList.toggle("hidden", !on); }
function say(id, text) { const e = $(id); e.textContent = text || ""; e.classList.toggle("hidden", !text); }

// ---------------------------------------------------------------- redraws that keep what staff were doing
// Before a part of the page is drawn again: the focused field (by its data-keep key; a key without one by its kind and
// words), its caret, and the scroll of every inner scroller marked data-scroll, the page's and main's own included.
// After: each is put back. A field that was not drawn again keeps its focus untouched.
const FOCUSABLE = "button, input, select, textarea, a[href], [tabindex]";
const sigOf = (x) => x.tagName + "|" + (typeof x.className === "string" ? x.className : "") + "|" + (x.textContent || x.title || "").trim().slice(0, 80);
function hold() {
  const a = document.activeElement, m = $("main");
  const h = { key: null, sig: null, nth: 0, s: null, e: null, scrolls: new Map(), y: window.scrollY, main: m ? m.scrollTop : 0, had: false };
  if (a && a !== document.body && a !== document.documentElement) {
    h.had = true;
    h.key = a.dataset && a.dataset.keep ? a.dataset.keep : null;
    if (!h.key) { h.sig = sigOf(a); h.nth = [...document.querySelectorAll(FOCUSABLE)].filter((x) => sigOf(x) === h.sig).indexOf(a); }
    try { if (typeof a.selectionStart === "number") { h.s = a.selectionStart; h.e = a.selectionEnd; } } catch (e) { /* a field without a caret */ }
  }
  document.querySelectorAll("[data-scroll]").forEach((x) => h.scrolls.set(x.dataset.scroll, [x.scrollTop, x.scrollLeft]));
  return h;
}
function give(h) {
  if (!h) return;
  document.querySelectorAll("[data-scroll]").forEach((x) => { const v = h.scrolls.get(x.dataset.scroll); if (v) { x.scrollTop = v[0]; x.scrollLeft = v[1]; } });
  const m = $("main"); if (m && m.scrollTop !== h.main) m.scrollTop = h.main;
  if (window.scrollY !== h.y) window.scrollTo(0, h.y);
  if (!h.had) return;
  const a = document.activeElement;
  if (a && a !== document.body && a !== document.documentElement && a.isConnected) return; // the field was kept as it was
  let t = null;
  if (h.key) t = document.querySelector('[data-keep="' + CSS.escape(h.key) + '"]');
  else if (h.sig) { const all = [...document.querySelectorAll(FOCUSABLE)].filter((x) => sigOf(x) === h.sig); t = all[Math.max(0, Math.min(h.nth, all.length - 1))] || null; }
  if (!t || t.disabled) return;
  try { t.focus({ preventScroll: true }); } catch (e) { t.focus(); }
  if (h.s !== null) { try { t.setSelectionRange(h.s, h.e); } catch (e) { /* not a text field */ } }
}
/** Is a text field or chooser focused inside {@code box}? (A redraw then waits, or leaves that part alone.) */
function typingIn(box) {
  const a = document.activeElement;
  return !!(box && a && a !== document.body && box.contains(a) && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName) && a.type !== "checkbox");
}
/** Runs {@code fn} now, or once the field being typed in inside {@code box} is left. */
function whenFree(box, fn) {
  if (!typingIn(box)) { fn(); return; }
  const a = document.activeElement;
  a.addEventListener("blur", () => setTimeout(fn, 0), { once: true });
}
// A "Sure?" key goes back to its plain words once its 3 s are over (it was changed in place, or drawn while armed).
function unarm(b, left, plain, plainCls) {
  if (left <= 0) return;
  setTimeout(() => { if (b.isConnected && !b.disabled) { b.textContent = plain; if (plainCls !== undefined) b.className = plainCls; } }, left + 30);
}

const ICONS = {
  Players: '<svg viewBox="0 0 24 24"><circle cx="9" cy="8" r="4"/><path d="M2 21c0-4 3-6 7-6s7 2 7 6"/><path d="M17 4a4 4 0 0 1 0 8M22 21c0-3-2-5-4-5.5"/></svg>',
  Chat: '<svg viewBox="0 0 24 24"><path d="M21 12a8 8 0 0 1-12 7l-5 1 1-4.5A8 8 0 1 1 21 12z"/></svg>',
  Map: '<svg viewBox="0 0 24 24"><path d="m3 6 6-2 6 2 6-2v14l-6 2-6-2-6 2z"/><path d="M9 4v14M15 6v14"/></svg>',
  Staff: '<svg viewBox="0 0 24 24"><path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z"/></svg>',
  More: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="19" cy="12" r="1.5"/></svg>',
};
