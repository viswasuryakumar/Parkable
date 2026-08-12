/// <reference lib="dom" />
// DOM types are pulled in for this file alone rather than added to
// tsconfig's global `lib`: everything here is browser-only by definition, and
// enabling DOM project-wide would let `window`/`document` typecheck happily
// inside React Native screens where they don't exist at runtime.

import { Platform } from 'react-native';
import { buildBaseUrl } from '../services/api';

/**
 * Web Push registration for the browser build.
 *
 * The native app schedules its reminders locally with the OS. A browser
 * cannot do that - there is no shipped API for "show a notification at 3pm"
 * - so the deadline is registered with the server, which pushes at the right
 * moment via a one-shot schedule. That's why this file talks to the backend
 * at all, where utils/notifications.ts stays entirely on-device.
 *
 * iOS caveat: Safari only permits push once the site has been added to the
 * Home Screen. Everything here degrades to `false` rather than throwing when
 * that hasn't happened, so the caller can tell the user the truth.
 */

/**
 * The application server's public key, committed rather than injected.
 *
 * It is not a credential: the browser pins it when it subscribes, and every
 * web-push site ships it in its client bundle. The matching private key lives
 * in SSM and never leaves the server.
 *
 * It lives here because Expo inlines EXPO_PUBLIC_* at build time, so an env
 * var has to be mirrored into every build environment separately - and when
 * one is missed, push disables itself with no error and no prompt. That
 * happened on the Vercel deploy. A committed default cannot drift out of
 * sync with the code that uses it; the env var still wins if it is set, so a
 * separate key per environment remains possible.
 *
 * Rotating this invalidates every existing subscription, so it is a
 * deliberate breaking change either way - not something worth optimising for.
 */
const DEFAULT_VAPID_PUBLIC_KEY =
  'BLRBaMUazjXHagWMqttrr8iPp0ZBJf7H5-lZuePtxMFgASTnRCy8IeaRO96ECGBa01WkkrjqlpS7l5a5WCvW6rg';

const VAPID_PUBLIC_KEY = process.env.EXPO_PUBLIC_VAPID_PUBLIC_KEY || DEFAULT_VAPID_PUBLIC_KEY;

export function isWebPushSupported(): boolean {
  return (
    Platform.OS === 'web' &&
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    'Notification' in window &&
    VAPID_PUBLIC_KEY !== ''
  );
}

/** True once the browser has actually granted permission (never prompts twice). */
export async function ensureWebPushPermission(): Promise<boolean> {
  if (!isWebPushSupported()) {
    return false;
  }
  if (Notification.permission === 'granted') {
    return true;
  }
  // Denied is terminal - the browser will not re-prompt, and asking again
  // does nothing but return 'denied' immediately.
  if (Notification.permission === 'denied') {
    return false;
  }
  try {
    return (await Notification.requestPermission()) === 'granted';
  } catch {
    return false;
  }
}

async function getSubscription(): Promise<PushSubscription | null> {
  try {
    const registration = await navigator.serviceWorker.register('/sw.js');
    // register() resolves before activation on a first visit; pushManager is
    // only usable once the worker is actually ready.
    await navigator.serviceWorker.ready;

    const existing = await registration.pushManager.getSubscription();
    if (existing) {
      return existing;
    }
    return await registration.pushManager.subscribe({
      // Chrome rejects any subscription that might push silently.
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(VAPID_PUBLIC_KEY),
    });
  } catch {
    return null;
  }
}

/**
 * Registers (or clears) the server-side reminders for the current session.
 * Pass the parking deadline as an ISO instant, or null when the car moves.
 * Returns whether the server accepted the request.
 */
export async function syncWebPushReminders(validUntil: string | null): Promise<boolean> {
  if (!isWebPushSupported() || !(await ensureWebPushPermission())) {
    return false;
  }
  const subscription = await getSubscription();
  if (!subscription) {
    return false;
  }

  const json = subscription.toJSON();
  if (!json.keys?.p256dh || !json.keys?.auth) {
    return false;
  }

  try {
    const response = await fetch(`${buildBaseUrl()}/push/reminders`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        endpoint: subscription.endpoint,
        p256dh: json.keys.p256dh,
        auth: json.keys.auth,
        valid_until: validUntil,
      }),
    });
    return response.ok;
  } catch {
    // Offline, or the API is down. The countdown in the UI still works; the
    // caller decides whether to tell the user the reminder isn't armed.
    return false;
  }
}

/**
 * The VAPID key travels as unpadded base64url, but `applicationServerKey`
 * wants raw bytes - and standard atob() rejects the url-safe alphabet, so the
 * characters have to be mapped back first.
 */
function urlBase64ToUint8Array(base64Url: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64Url.length % 4)) % 4);
  const base64 = (base64Url + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = window.atob(base64);
  // Backed by an explicit ArrayBuffer so the result is a BufferSource
  // `applicationServerKey` accepts; a bare `new Uint8Array(length)` widens to
  // ArrayBufferLike, which includes SharedArrayBuffer and is rejected.
  const output = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) {
    output[i] = raw.charCodeAt(i);
  }
  return output;
}
