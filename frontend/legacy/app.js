"use strict";
const $ = (id) => document.getElementById(id);
const DAYS = ["Isn", "Sel", "Rab", "Kha", "Jum", "Sab", "Ahd"]; // Monday = 0 (matches Python weekday())
const LS = "busalert.subs";

let routes = [];
let stops = [];

// ---------- small helpers ----------
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const say = (el, text, cls = "") => { el.className = "msg " + cls; el.textContent = text; };

async function api(path, opts) {
  const r = await fetch(path, opts);
  let data = null;
  try { data = await r.json(); } catch (_) { /* no body */ }
  if (!r.ok) throw new Error((data && data.detail) || `Ralat ${r.status}`);
  return data;
}

function b64ToBytes(b64) {
  const pad = "=".repeat((4 - (b64.length % 4)) % 4);
  const raw = atob((b64 + pad).replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}

const isIOS = /iphone|ipad|ipod/i.test(navigator.userAgent) ||
  (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
const isStandalone = window.matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
const pushSupported = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;

// ---------- route / stop pickers ----------
function fillSelect(sel, items, valueKey, textFn) {
  sel.innerHTML = items.map((i) => `<option value="${esc(i[valueKey])}">${esc(textFn(i))}</option>`).join("");
}

function filterRoutes() {
  const q = $("routeSearch").value.trim().toLowerCase();
  const list = routes.filter((r) => r.label.toLowerCase().includes(q));
  fillSelect($("route"), list, "key", (r) => r.label);
  $("route").dispatchEvent(new Event("change"));
}

function filterStops() {
  const q = $("stopSearch").value.trim().toLowerCase();
  const list = stops.filter((s) => s.name.toLowerCase().includes(q));
  fillSelect($("stop"), list, "id", (s) => s.name);
  updateButtons();
}

function updateButtons() {
  const ok = !!$("route").value && !!$("stop").value;
  $("btnEta").disabled = !ok;
  $("btnSub").disabled = !ok || !pushSupported;
}

async function loadRoutes() {
  try {
    routes = await api("/api/routes");
    filterRoutes();
  } catch (e) {
    $("route").innerHTML = `<option>${esc(e.message)}</option>`;
    setTimeout(loadRoutes, 5000); // server may still be downloading GTFS
  }
}

$("routeSearch").addEventListener("input", filterRoutes);
$("stopSearch").addEventListener("input", filterStops);
$("route").addEventListener("change", async () => {
  stops = [];
  $("stop").disabled = true;
  $("stop").innerHTML = "";
  $("etaOut").textContent = "";
  updateButtons();
  const key = $("route").value;
  if (!key) return;
  try {
    stops = await api("/api/stops?route=" + encodeURIComponent(key));
    $("stop").disabled = false;
    $("stopSearch").value = "";
    filterStops();
  } catch (e) { say($("etaOut"), e.message, "bad"); }
});
$("stop").addEventListener("change", updateButtons);

// ---------- ETA now ----------
$("btnEta").addEventListener("click", async () => {
  const out = $("etaOut");
  say(out, "Mengira…");
  try {
    const q = new URLSearchParams({ route: $("route").value, stop: $("stop").value });
    const { etas, note } = await api("/api/eta?" + q);
    if (!etas.length) { say(out, "Tiada bas yang menuju stop ni sekarang (mungkin di luar waktu operasi).", "warn"); return; }
    out.className = "msg";
    out.innerHTML = etas.map((e) =>
      `<div class="eta"><b>~${e.minutes} min</b> · ${e.stops_away <= 0 ? "hampir sampai" : e.stops_away + " stop lagi"}
       <small>(${e.km} km, bas ${esc(e.vehicle)})</small></div>`).join("") +
      `<small>${esc(note)}</small>`;
  } catch (e) { say(out, e.message, "bad"); }
});

// ---------- schedule form ----------
$("days").innerHTML = DAYS.map((d, i) =>
  `<label><input type="checkbox" value="${i}" ${i < 5 ? "checked" : ""}> ${d}</label>`).join("");

// ---------- subscribe ----------
async function ensureSubscription() {
  if (isIOS && !isStandalone) throw new Error("Di iPhone, kena buka dari ikon Home Screen dulu (Share → Add to Home Screen).");
  const reg = await navigator.serviceWorker.register("sw.js");
  await navigator.serviceWorker.ready;
  const perm = await Notification.requestPermission();
  if (perm !== "granted") throw new Error("Kebenaran notifikasi ditolak. Boleh tukar dalam tetapan browser/telefon.");
  const { vapidPublicKey } = await api("/api/config");
  let sub = await reg.pushManager.getSubscription();
  if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(vapidPublicKey) });
  return sub.toJSON();
}

$("btnSub").addEventListener("click", async () => {
  const out = $("subOut");
  const days = [...document.querySelectorAll("#days input:checked")].map((x) => +x.value);
  if (!days.length) { say(out, "Pilih sekurang-kurangnya satu hari.", "warn"); return; }
  $("btnSub").disabled = true;
  say(out, "Menyediakan…");
  try {
    const subscription = await ensureSubscription();
    const body = {
      subscription, route: $("route").value, stop_id: $("stop").value, days,
      start: $("start").value, end: $("end").value, interval_min: +$("interval").value,
    };
    const { id, token } = await api("/api/subscriptions", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    const mine = JSON.parse(localStorage.getItem(LS) || "[]");
    mine.push({
      id, token,
      title: $("route").selectedOptions[0].text.split(" – ")[0] + " · " + $("stop").selectedOptions[0].text,
      when: `${days.map((d) => DAYS[d]).join(",")} ${body.start}–${body.end}, setiap ${body.interval_min} min`,
    });
    localStorage.setItem(LS, JSON.stringify(mine));
    renderMine();
    say(out, "Siap! Kau patut dapat satu notifikasi ujian sekarang.", "ok");
  } catch (e) { say(out, e.message, "bad"); }
  updateButtons();
});

function renderMine() {
  const mine = JSON.parse(localStorage.getItem(LS) || "[]");
  $("mineCard").hidden = !mine.length;
  $("mine").innerHTML = mine.map((m) =>
    `<div class="mine"><div>${esc(m.title)}<small>${esc(m.when)}</small></div>
     <button class="ghost small" data-id="${esc(m.id)}">Padam</button></div>`).join("");
}

$("mine").addEventListener("click", async (ev) => {
  const id = ev.target.dataset && ev.target.dataset.id;
  if (!id) return;
  const mine = JSON.parse(localStorage.getItem(LS) || "[]");
  const m = mine.find((x) => x.id === id);
  try {
    await api(`/api/subscriptions/${encodeURIComponent(id)}?token=${encodeURIComponent(m.token)}`, { method: "DELETE" });
  } catch (_) { /* already gone on the server: remove locally anyway */ }
  localStorage.setItem(LS, JSON.stringify(mine.filter((x) => x.id !== id)));
  renderMine();
});

// ---------- init ----------
if (isIOS && !isStandalone) $("installHint").hidden = false;
if (!pushSupported) {
  say($("subOut"), "Browser ni tak support Web Push. Guna Chrome (Android) atau Safari 16.4+ (iPhone, via Home Screen).", "warn");
}
renderMine();
loadRoutes();
