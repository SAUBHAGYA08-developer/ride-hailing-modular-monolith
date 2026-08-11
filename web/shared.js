// Shared client for every page. Base URL is runtime configurable: the API lives on another host.
const RH = (() => {
  const K_URL = "rh.baseUrl", K_TOK = "rh.token", K_ROLE = "rh.role", K_EMAIL = "rh.email";
  // Same origin by default, which is exactly right when the backend serves these pages itself.
  const DEFAULT_BASE = (typeof location !== "undefined" && location.origin.startsWith("http"))
    ? location.origin + "/api/v1"
    : "http://localhost:8080/api/v1";

  const get = (k, d = "") => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
  const put = (k, v) => { try { v === null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch {} };

  const baseUrl = () => (get(K_URL) || DEFAULT_BASE).replace(/\/+$/, "");
  const setBaseUrl = (v) => put(K_URL, (v || "").trim().replace(/\/+$/, ""));
  const token = () => get(K_TOK) || null;
  const role = () => get(K_ROLE) || null;
  const email = () => get(K_EMAIL) || null;

  function session(tok, r, em) { put(K_TOK, tok); put(K_ROLE, r); put(K_EMAIL, em); }
  function logout() { put(K_TOK, null); put(K_ROLE, null); put(K_EMAIL, null); }

  // Read only; the server re-checks every call, so this never grants anything.
  function roleFromJwt(jwt) {
    try {
      const b = jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
      return JSON.parse(decodeURIComponent(escape(atob(b)))).role || null;
    } catch { return null; }
  }

  class ApiError extends Error {
    constructor(code, message, status) { super(message); this.code = code; this.status = status; }
  }

  async function api(path, opts = {}) {
    const headers = { Accept: "application/json", ...(opts.headers || {}) };
    if (opts.body !== undefined) headers["Content-Type"] = "application/json";
    const t = token();
    if (t) headers["Authorization"] = "Bearer " + t;

    let res;
    const started = performance.now();
    try {
      res = await fetch(baseUrl() + path, {
        method: opts.method || "GET", headers,
        body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      });
    } catch {
      // A wrong base URL and a missing CORS origin both land here; name both.
      throw new ApiError("NETWORK", "Cannot reach " + baseUrl() + " — check the Base URL, that the backend is up, and that this origin is listed in CORS_ALLOWED_ORIGINS.", 0);
    }
    const ms = Math.round(performance.now() - started);
    const text = await res.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch {
      log((opts.method || "GET") + " " + path, res.status, ms);
      throw new ApiError("BAD_RESPONSE", "Server returned non-JSON (HTTP " + res.status + ")", res.status);
    }
    log((opts.method || "GET") + " " + path, res.status, ms);
    if (!res.ok || body?.success === false) {
      throw new ApiError(body?.error?.code || "HTTP_" + res.status, body?.error?.message || "Request failed", res.status);
    }
    return body?.data ?? body;
  }

  // One shared request log, so a failed call is always visible somewhere on the page.
  const LOG = [];
  function log(line, status, ms) {
    LOG.unshift({ line, status, ms, at: new Date().toLocaleTimeString() });
    if (LOG.length > 60) LOG.length = 60;
    const box = document.getElementById("reqLog");
    if (box) box.innerHTML = LOG.map(e =>
      `<div class="logrow"><span class="${e.status < 400 ? "ok" : "bad"}">${e.status}</span> <span class="mono">${esc(e.line)}</span> <span class="muted">${e.ms}ms · ${e.at}</span></div>`).join("");
  }

  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const $ = (id) => document.getElementById(id);

  function banner(id, msg, kind = "err") {
    const el = $(id);
    if (!el) return;
    el.className = "banner " + kind;
    el.textContent = msg || "";
    el.style.display = msg ? "block" : "none";
  }

  // Pages call this to refuse the wrong role; the server enforces it regardless.
  function requireRole(expected) {
    if (!token()) { location.href = "index.html"; return false; }
    if (expected && role() !== expected) { location.href = homeFor(role()); return false; }
    return true;
  }
  const homeFor = (r) => r === "DRIVER" ? "driver.html" : r === "ADMIN" ? "admin.html" : r === "USER" ? "rider.html" : "index.html";

  // Same header everywhere, so the base URL stays editable from any page.
  function mountChrome(title) {
    const host = $("chrome");
    if (!host) return;
    host.innerHTML = `
      <div class="chrome">
        <div class="brand">RideHailing <span class="muted">· ${esc(title)}</span></div>
        <div class="grow"></div>
        <label class="muted">Base URL</label>
        <input id="baseUrlBox" value="${esc(baseUrl())}" spellcheck="false">
        <button id="saveBase" class="sm">Save</button>
        <span class="pill">${esc(role() || "guest")}</span>
        <span class="muted mono hide-sm">${esc(email() || "")}</span>
        <button id="logoutBtn" class="sm">Log out</button>
      </div>`;
    $("saveBase").onclick = () => { setBaseUrl($("baseUrlBox").value); location.reload(); };
    $("logoutBtn").onclick = () => { logout(); location.href = "index.html"; };
  }

  const fmt = (n) => (n === null || n === undefined || n === "") ? "—" : Number(n).toFixed(2);
  const when = (iso) => iso ? new Date(iso).toLocaleString() : "—";

  return { api, ApiError, baseUrl, setBaseUrl, token, role, email, session, logout, roleFromJwt,
           requireRole, homeFor, mountChrome, banner, esc, $, fmt, when, DEFAULT_BASE };
})();
