const SHORT_DAY_TO_INDEX: Record<string, number> = {
  Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6,
};

/**
 * Parses NearbyHandler.formatDays()'s output back into day-of-week indices.
 * Only handles the "Every day" and "Mon, Wed, Fri"-style outputs (the
 * overwhelming majority of real signs) - nth-weekday-of-month patterns
 * ("1st & 3rd Tue") are NOT parsed here and return null, since reimplementing
 * that logic client-side risks silently drifting from the real engine
 * (backend/.../TemporalRuleEvaluator) that's the actual source of truth.
 * Skipping is the honest choice, not a guess.
 */
export function parseDaysLabel(daysLabel: string): number[] | null {
  if (daysLabel === 'Every day') {
    return [0, 1, 2, 3, 4, 5, 6];
  }
  const parts = daysLabel.split(',').map((p) => p.trim());
  const indices: number[] = [];
  for (const part of parts) {
    const index = SHORT_DAY_TO_INDEX[part];
    if (index === undefined) {
      return null; // an nth-weekday label or something else unrecognized
    }
    indices.push(index);
  }
  return indices.length > 0 ? indices : null;
}

/** Parses NearbyHandler.formatHours()'s "8:00 AM–6:00 PM" output; returns the START hour/minute only. Null for "Any time" or anything unparseable. */
export function parseStartTime(hoursLabel: string): { hour: number; minute: number } | null {
  const match = hoursLabel.match(/^(\d{1,2}):(\d{2})\s*(AM|PM)/i);
  if (!match) {
    return null;
  }
  let hour = parseInt(match[1], 10) % 12;
  if (match[3].toUpperCase() === 'PM') {
    hour += 12;
  }
  return { hour, minute: parseInt(match[2], 10) };
}

/**
 * The next Date (today or later) on which one of `dayIndices` matches at
 * `hour`:`minute`, in local time. Returns null if the rule has no
 * parseable schedule to compute against.
 */
export function nextOccurrence(dayIndices: number[], hour: number, minute: number, from: Date = new Date()): Date | null {
  if (dayIndices.length === 0) {
    return null;
  }
  for (let offset = 0; offset < 8; offset++) {
    const candidate = new Date(from);
    candidate.setDate(candidate.getDate() + offset);
    candidate.setHours(hour, minute, 0, 0);
    if (dayIndices.includes(candidate.getDay()) && candidate.getTime() > from.getTime()) {
      return candidate;
    }
  }
  return null;
}
