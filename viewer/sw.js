// App-shell cache so the viewer opens instantly; data always comes from Drive (cached separately in app.js).
const SHELL = "wa-shell-v1";
const FILES = ["./", "index.html", "style.css", "app.js", "config.js", "manifest.webmanifest", "icons/icon.svg"];
self.addEventListener("install", (e) => e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).then(() => self.skipWaiting())));
self.addEventListener("activate", (e) => e.waitUntil(
  caches.keys().then((ks) => Promise.all(ks.filter((k) => k.startsWith("wa-shell-") && k !== SHELL).map((k) => caches.delete(k))))
    .then(() => self.clients.claim())));
self.addEventListener("fetch", (e) => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET" || u.origin !== location.origin) return;
  // network first (so updates show up), cache as offline fallback
  e.respondWith(fetch(e.request).then((r) => {
    const copy = r.clone(); caches.open(SHELL).then((c) => c.put(e.request, copy)); return r;
  }).catch(() => caches.match(e.request)));
});
