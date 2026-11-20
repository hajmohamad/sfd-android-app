/**
 * SFD Service Worker
 * Caches static shell assets for instant launch on iOS / PWA
 */

const CACHE_NAME = "sfd-pwa-v1";
const STATIC_ASSETS = [
  "./",
  "./index.html",
  "./app.js",
  "./ocr.js",
  "./templates.json",
  "./manifest.json",
  "./icons/icon.svg"
];

self.addEventListener("install", event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      return cache.addAll(STATIC_ASSETS);
    })
  );
  self.skipWaiting();
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys().then(keys => {
      return Promise.all(
        keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))
      );
    })
  );
  self.clients.claim();
});

self.addEventListener("fetch", event => {
  const url = new URL(event.request.url);

  // If request is to workers.dev or external university endpoints, do NOT cache
  if (url.hostname.includes("workers.dev") || url.hostname.includes("sfd.ui.ac.ir")) {
    return event.respondWith(fetch(event.request));
  }

  // Cache-first for local static assets
  event.respondWith(
    caches.match(event.request).then(cached => {
      if (cached) return cached;
      return fetch(event.request).then(response => {
        if (response.status === 200) {
          const clone = response.clone();
          caches.open(CACHE_NAME).then(cache => cache.put(event.request, clone));
        }
        return response;
      });
    })
  );
});
