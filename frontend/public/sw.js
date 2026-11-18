"use strict";
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let d = { title: "Bus Alert", body: "", tag: "bus-eta" };
  try { d = { ...d, ...event.data.json() }; } catch (_) { /* keep defaults */ }
  event.waitUntil(self.registration.showNotification(d.title, {
    body: d.body,
    tag: d.tag,          // same tag = replaces the previous alert instead of stacking
    renotify: true,
    icon: "icon-192.png",
    badge: "icon-192.png",
  }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
    for (const c of list) if ("focus" in c) return c.focus();
    return self.clients.openWindow("/");
  }));
});
