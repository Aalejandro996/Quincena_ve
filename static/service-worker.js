const CACHE_NAME = 'quincena-shell-v25';
const SHELL = ['/app', '/static/manifest.webmanifest', '/static/icons/icon-192.png', '/static/icons/icon-512.png', '/static/icons/icon.svg'];
self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  // Authentication and financial API requests are never cached.
  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/') || url.pathname.startsWith('/descargar/')) return;
  if (event.request.method !== 'GET') return;
  event.respondWith(fetch(event.request).then((response) => {
    if (response.ok && (url.pathname === '/app' || url.pathname.startsWith('/static/'))) {
      const clone = response.clone();
      caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
    }
    return response;
  }).catch(async () => (await caches.match(event.request)) || (await caches.match('/app'))));
});


// Push de recordatorios de vencimientos; el service worker puede recibirlos
// aunque la interfaz de la PWA no esté abierta.
self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch (_error) {
    payload = { body: event.data ? event.data.text() : '' };
  }
  const title = String(payload.title || 'Quincena VE').slice(0, 120);
  const options = {
    body: String(payload.body || 'Tienes un pago pendiente. Abre Quincena VE para revisarlo.').slice(0, 240),
    icon: '/static/icons/icon-192.png',
    badge: '/static/icons/icon-192.png',
    tag: String(payload.tag || 'control-financiero-pago').slice(0, 160),
    renotify: false,
    data: { url: String(payload.url || '/app') }
  };
  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  let destination;
  try {
    destination = new URL(event.notification.data && event.notification.data.url || '/', self.location.origin);
    if (destination.origin !== self.location.origin) destination = new URL('/', self.location.origin);
  } catch (_error) {
    destination = new URL('/', self.location.origin);
  }
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(async (clientList) => {
    for (const client of clientList) {
      if (new URL(client.url).origin === self.location.origin && 'focus' in client) {
        try { await client.navigate(destination.href); } catch (_error) { /* Focus is still useful if navigation fails. */ }
        return client.focus();
      }
    }
    if (self.clients.openWindow) return self.clients.openWindow(destination.href);
    return undefined;
  }));
});
