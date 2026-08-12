/*
 * Parkable service worker - Web Push only.
 *
 * Deliberately does NOT cache anything. Expo's Metro export ships
 * content-hashed bundles, and a naive cache-first worker is the classic way
 * to pin users to a stale build forever. The only reason this file exists is
 * that a browser cannot show a notification while its tab is closed - that
 * has to come from a push event handled here, out of process.
 */

// Take over immediately instead of waiting for every tab to close, so a
// reminder never gets handled by a worker from an old deploy.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
  // Fall back to generic wording rather than dropping the notification: the
  // user is standing next to a meter, and "something about your parking"
  // beats silence if the payload is ever malformed.
  let payload = { title: 'Parkable', body: 'Check your parking time.', tag: 'parkable' };
  try {
    if (event.data) {
      payload = { ...payload, ...event.data.json() };
    }
  } catch {
    // Keep the fallback.
  }

  event.waitUntil(
    self.registration.showNotification(payload.title, {
      body: payload.body,
      // Same tag replaces rather than stacks, so the deadline notice
      // supersedes the earlier warning instead of leaving two in the tray.
      tag: payload.tag,
      renotify: true,
      icon: '/icon-192.png',
      badge: '/icon-192.png',
      // A parking reminder is worth an explicit dismissal - auto-hiding it
      // after a few seconds defeats the point if the phone is in a pocket.
      requireInteraction: true,
      vibrate: [200, 100, 200],
      data: { url: '/' },
    })
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      // Reuse an already-open Parkable tab rather than piling up new ones.
      for (const client of windows) {
        if ('focus' in client) {
          return client.focus();
        }
      }
      return self.clients.openWindow(event.notification.data?.url || '/');
    })
  );
});
