import React from 'react';

/**
 * Live timer support for the parking session. Previously the only "time
 * left" readout was FindMyCarScreen's formatRemaining(), which rounded to
 * whole minutes and was computed once at render - so it sat frozen on
 * "45m left" until something else re-rendered the screen. A parking meter
 * that never visibly moves reads as stale data rather than a running clock,
 * hence seconds and a real tick.
 */

/** Milliseconds until `deadline` (negative once passed), or null if untimed. */
export function useCountdown(deadline: string | null): number | null {
  const target = React.useMemo(() => (deadline ? Date.parse(deadline) : NaN), [deadline]);
  const [msLeft, setMsLeft] = React.useState<number | null>(() =>
    Number.isNaN(target) ? null : target - Date.now()
  );

  React.useEffect(() => {
    if (Number.isNaN(target)) {
      setMsLeft(null);
      return;
    }
    // Recompute from the wall clock on every tick rather than decrementing a
    // stored counter. A locked phone or a backgrounded browser tab throttles
    // (or entirely suspends) timers, so a decrementing counter would fall
    // steadily behind real time - exactly the direction of error that makes
    // someone think they still have 10 minutes on an expired meter.
    const tick = () => setMsLeft(target - Date.now());
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [target]);

  return msLeft;
}

/** Milliseconds elapsed since `since`, ticking every second. */
export function useElapsed(since: string): number {
  const start = React.useMemo(() => Date.parse(since), [since]);
  const [ms, setMs] = React.useState(() => Date.now() - start);

  React.useEffect(() => {
    if (Number.isNaN(start)) {
      return;
    }
    const tick = () => setMs(Date.now() - start);
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [start]);

  return ms;
}

/**
 * `H:MM:SS` past an hour, `M:SS` under it - always with seconds, so the
 * display visibly moves once a second and reads as a running clock.
 */
export function formatDuration(ms: number): string {
  // Ceil so the final second shows "0:01" rather than sitting on "0:00"
  // for a full second before expiry.
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

export type Urgency = 'expired' | 'urgent' | 'soon' | 'normal';

const URGENT_MS = 5 * 60_000;
const SOON_MS = 15 * 60_000;

export function urgencyOf(msLeft: number): Urgency {
  if (msLeft <= 0) {
    return 'expired';
  }
  if (msLeft < URGENT_MS) {
    return 'urgent';
  }
  if (msLeft < SOON_MS) {
    return 'soon';
  }
  return 'normal';
}
