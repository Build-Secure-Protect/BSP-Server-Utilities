"use strict";
(async () => {
  // the token is in the address after #, never sent in an address; it is taken out of the address bar at once
  const hash = location.hash.slice(1);
  history.replaceState(null, "", location.pathname);
  const enrol = hash.startsWith("enrol:");
  const token = enrol ? hash.slice(6) : hash;
  let secret = "";
  if (!token) { say("err", "Open the link you were sent, or ask an Owner for one."); return; }
  const info = await api(enrol ? "/api/enrol/info" : "/api/setup/info", { token });
  if (!info.ok) { say("err", info.message); return; }
  secret = info.secret;
  if (enrol) {
    $("title").textContent = "Set up a new authenticator";
    show("pwpart", false);
    $("n2").textContent = "1"; $("n3").textContent = "2";
    $("welcome").textContent = "Your authenticator was reset. Scan the new code, then type what it shows.";
  } else {
    $("welcome").textContent = "Welcome, " + info.mcName + ". " + (info.createdBy === "console" ? "You were made" : info.createdBy + " added you as") + " " +
      (info.createdBy === "console" ? "an " + info.rank : "a " + info.rank) + ". This link works once and only for a few minutes.";
  }
  $("qr").src = info.qr;
  $("addapp").href = info.otpauth;
  $("secret").textContent = secret.replace(/(.{4})/g, "$1 ").trim();
  show("form", true);
  $("form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    say("err", "");
    if (!enrol && $("password").value !== $("again").value) { say("err", "The two passwords are not the same."); return; }
    $("go").disabled = true;
    try {
      const body = enrol ? { token, secret, code: $("code").value } : { token, secret, code: $("code").value, password: $("password").value };
      const r = await api(enrol ? "/api/enrol" : "/api/setup", body);
      if (!r.ok) { say("err", r.message); return; }
      showCodes(r.codes, r.email);
    } catch (e) {
      say("err", "The panel could not be reached. Try again.");
    } finally {
      $("go").disabled = false;
    }
  });
  function showCodes(codes, email) {
    show("form", false);
    $("title").textContent = "Your backup codes";
    const ol = $("codes");
    codes.forEach((c) => ol.appendChild(el("li", null, c)));
    show("done", true);
    const text = "BSP Staff backup codes for " + email + "\n" + codes.map((c, i) => (i + 1) + ". " + c).join("\n") + "\n";
    $("copy").addEventListener("click", async () => {
      try { await navigator.clipboard.writeText(text); $("copy").textContent = "Copied"; } catch (e) { $("copy").textContent = "Copy failed"; }
    });
    $("save").addEventListener("click", () => {
      const a = el("a");
      a.href = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
      a.download = "bsp-staff-backup-codes.txt";
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    });
    $("saved").addEventListener("change", () => { $("enter").disabled = !$("saved").checked; });
    $("enter").addEventListener("click", () => { location.href = "/"; });
  }
})();
