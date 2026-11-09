"use strict";
// The Broadcast page (D264): the same choices as /broadcast, the chat line or title drawn as the game would show it while
// typing, then SEND with a second tap within 3 seconds. Admins and Owners (D250).
const Announce = (() => {
  const COLOURS = [["white", "#FFFFFF"], ["yellow", "#FFFF55"], ["gold", "#FFAA00"], ["red", "#FF5555"], ["dark_red", "#AA0000"], ["light_purple", "#FF55FF"],
    ["dark_purple", "#AA00AA"], ["blue", "#5555FF"], ["dark_blue", "#0000AA"], ["aqua", "#55FFFF"], ["dark_aqua", "#00AAAA"], ["green", "#55FF55"],
    ["dark_green", "#00AA00"], ["gray", "#AAAAAA"], ["dark_gray", "#555555"], ["black", "#000000"]];
  const CODES = { 0: "#000000", 1: "#0000AA", 2: "#00AA00", 3: "#00AAAA", 4: "#AA0000", 5: "#AA00AA", 6: "#FFAA00", 7: "#AAAAAA", 8: "#555555", 9: "#5555FF",
    a: "#55FF55", b: "#55FFFF", c: "#FF5555", d: "#FF55FF", e: "#FFFF55", f: "#FFFFFF" };
  const st = { title: false, colour: "gold", target: "", network: true, server: "", message: "", armed: 0, result: null };

  // & codes as the game reads them: colours, &l bold, &o italic, &n underline, &m strikethrough, &r back to the start
  function render(into, text, base) {
    let colour = base, b = false, i = false, u = false, m = false, buf = "";
    const flush = () => {
      if (!buf) return;
      const s = el("span", null, buf); s.style.color = colour;
      if (b) s.style.fontWeight = "700"; if (i) s.style.fontStyle = "italic";
      const deco = [u ? "underline" : "", m ? "line-through" : ""].filter(Boolean).join(" "); if (deco) s.style.textDecoration = deco;
      into.appendChild(s); buf = "";
    };
    for (let k = 0; k < text.length; k++) {
      const c = text[k], n = (text[k + 1] || "").toLowerCase();
      if (c === "&" && /[0-9a-fk-or]/.test(n)) {
        flush(); k++;
        if (CODES[n]) { colour = CODES[n]; b = i = u = m = false; }
        else if (n === "l") b = true; else if (n === "o") i = true; else if (n === "n") u = true; else if (n === "m") m = true;
        else if (n === "r") { colour = base; b = i = u = m = false; }
        continue;
      }
      buf += c;
    }
    flush();
  }
  const hex = () => (COLOURS.find(([n]) => n === st.colour) || COLOURS[2])[1];

  function preview(box) {
    box.textContent = "";
    const scene = el("div", "scene");
    if (st.title) {
      const [t, sub] = st.message.split("|").map((x) => x.trim());
      const tt = el("div", "ttitle"); render(tt, t || "", hex()); scene.appendChild(tt);
      if (sub) { const ts = el("div", "tsub"); render(ts, sub, hex()); scene.appendChild(ts); }
    }
    const chat = el("div", "tchat"); const pre = el("span", null, "[Announcement] "); pre.style.color = "#FFAA00"; chat.appendChild(pre);
    render(chat, st.title ? st.message.split("|").map((x) => x.trim()).filter(Boolean).join(" ") : st.message, hex());
    scene.appendChild(chat);
    box.appendChild(scene);
    const who = st.target ? "only " + st.target : "everyone";
    box.appendChild(el("div", "note", (st.title ? "A title in the middle of the screen for about 5 seconds, and in chat. " : "A chat line. ") + "To " + who + " " +
      (st.network ? "on every server" : "on " + (st.server || "the chosen server")) + ", with a short chime."));
  }

  function view(main, live, me) {
    main.appendChild(el("h2", null, "Broadcast"));
    if (me.rank === "MODERATOR") { main.appendChild(el("div", "note", "Announcements are for Admins and Owners.")); return; }
    if (!st.server && live.servers.length) st.server = live.servers[0].server;
    const wrap = el("div", "bcast"); const form = el("div", "form"); const right = el("div", "bprev");
    const seg = el("div", "seg");
    [[false, "Chat line"], [true, "Title"]].forEach(([v, t]) => { const b = el("button", st.title === v ? "on" : "", t); b.type = "button"; b.addEventListener("click", () => { st.title = v; App.refreshMore(); }); seg.appendChild(b); });
    form.appendChild(seg);
    const msg = el("input"); msg.type = "text"; msg.maxLength = 256; msg.value = st.message; msg.placeholder = st.title ? "Restart | in 5 minutes" : "Message (& codes work: &lbold, &cred)";
    msg.addEventListener("input", () => { st.message = msg.value; preview(right); });
    const lm = el("label", null, st.title ? "Title, then | and a smaller line under it" : "Message"); lm.appendChild(msg); form.appendChild(lm);
    const col = el("select"); COLOURS.forEach(([n, c]) => { const o = el("option", null, n.replace("_", " ")); o.value = n; o.style.color = c; if (n === st.colour) o.selected = true; col.appendChild(o); });
    col.addEventListener("change", () => { st.colour = col.value; preview(right); });
    const lc = el("label", null, "Colour"); lc.appendChild(col); form.appendChild(lc);
    const to = el("input"); to.type = "text"; to.maxLength = 16; to.value = st.target; to.placeholder = "Everyone";
    to.addEventListener("input", () => { st.target = to.value.trim(); preview(right); });
    const lt = el("label", null, "To one player (empty for everyone)"); lt.appendChild(to); form.appendChild(lt);
    const where = el("select"); const net = el("option", null, "Every server"); net.value = ""; where.appendChild(net);
    live.servers.forEach((s) => { const o = el("option", null, s.server + " only"); o.value = s.server; if (!st.network && st.server === s.server) o.selected = true; where.appendChild(o); });
    if (st.network) net.selected = true;
    where.addEventListener("change", () => { st.network = where.value === ""; if (where.value) st.server = where.value; preview(right); });
    const lw = el("label", null, "Where"); lw.appendChild(where); form.appendChild(lw);
    const send = el("button", "btn", Date.now() - st.armed < 3000 ? "Sure? Send it" : "Send"); send.type = "button";
    send.addEventListener("click", async () => {
      if (!st.message.trim()) { st.result = { ok: false, message: "Write a message first." }; App.refreshMore(); return; }
      if (Date.now() - st.armed > 3000) { st.armed = Date.now(); send.textContent = "Sure? Send it"; return; }
      st.armed = 0; send.disabled = true; send.textContent = "Sending…";
      try { st.result = await api("/api/act", { action: "broadcast", title: st.title, colour: st.colour, target: st.target, network: st.network, server: st.server, message: st.message }); }
      catch (e) { st.result = { ok: false, message: "The panel could not be reached." }; }
      App.refreshMore();
    });
    form.appendChild(send);
    if (st.result) form.appendChild(el("div", st.result.ok ? "okm" : "err", st.result.message));
    wrap.appendChild(form); wrap.appendChild(right); main.appendChild(wrap);
    preview(right);
  }
  return { view };
})();
