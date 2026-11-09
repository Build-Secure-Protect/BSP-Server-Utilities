"use strict";
(async () => {
  try { $("who").textContent = (await api("/api/whoami")).email; } catch (e) { $("who").textContent = "?"; }
  $("password").focus();
  $("form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    $("go").disabled = true;
    try {
      const r = await api("/api/login", { password: $("password").value, code: $("code").value });
      if (r.ok) { location.href = "/"; return; }
      if (r.enrol) { location.href = "/setup#enrol:" + r.enrol; return; }
      say("err", r.message);
      $("code").value = "";
      if (r.outcome === "WRONG") $("password").select();
    } catch (e) {
      say("err", "The panel could not be reached. Try again.");
    } finally {
      $("go").disabled = false;
    }
  });
})();
