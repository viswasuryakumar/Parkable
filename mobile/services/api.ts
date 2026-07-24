export type VerdictResponse = {
  verdict: 'PARKABLE' | 'NOT_PARKABLE' | 'DEPENDS';
  reason: string | null;
  rule_id?: string | null;
  valid_until?: string | null;
  source?: string | null;
  trace?: string[];
};

// GET /check answers 404 with this body when no rule data exists within 25m.
// That is a first-class outcome ("go scan the sign"), not an error.
export type NoDataResponse = {
  status: 'NO_DATA';
  message: string;
};

export type CheckResult =
  | { kind: 'verdict'; verdict: VerdictResponse }
  | { kind: 'no_data'; message: string };

export type ScanRequest = {
  photo_base64: string;
  media_type: string;
  lat: number;
  lng: number;
  at?: string;
  zone?: string;
  side?: 'LEFT' | 'RIGHT';
};

// POST /scan answers 422 when extraction could not confidently read the sign.
// Also a first-class outcome — the honest "retake the photo" path the whole
// architecture is built around, so it must not surface as a thrown error.
export type ScanResult =
  | { kind: 'verdict'; verdict: VerdictResponse }
  | { kind: 'needs_review'; message: string };

export type NeedsReviewResponse = {
  status: 'NEEDS_REVIEW';
  message: string;
};

// GET /nearby lists rule summaries — it never computes a verdict, so this
// shape is deliberately unrelated to VerdictResponse (plan §3).
export type NearbyRule = {
  rule_id: string;
  description: string;
  source: string;
  parser_version: string;
  scan_id: string;
  days: string;
  hours: string;
  lat: number;
  lng: number;
  distance_m: number;
};

const DEFAULT_BASE_URL = 'https://example.invalid';

export function buildBaseUrl(): string {
  return process.env.EXPO_PUBLIC_API_BASE_URL ?? DEFAULT_BASE_URL;
}

export async function scanParking(payload: ScanRequest): Promise<ScanResult> {
  const response = await fetch(`${buildBaseUrl()}/scan`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });

  if (response.status === 422) {
    const body = (await response.json().catch(() => ({}))) as Partial<NeedsReviewResponse>;
    return {
      kind: 'needs_review',
      message: body.message ?? 'The sign could not be read confidently. Please retake the photo.',
    };
  }

  if (!response.ok) {
    throw new Error(`Scan request failed: ${response.status}`);
  }

  return { kind: 'verdict', verdict: (await response.json()) as VerdictResponse };
}

export async function checkParking(lat: number, lng: number): Promise<CheckResult> {
  const response = await fetch(`${buildBaseUrl()}/check?lat=${lat}&lng=${lng}`);

  if (response.status === 404) {
    const body = (await response.json().catch(() => ({}))) as Partial<NoDataResponse>;
    return {
      kind: 'no_data',
      message: body.message ?? 'No rule data near you yet. Scan the sign.',
    };
  }

  if (!response.ok) {
    throw new Error(`Check request failed: ${response.status}`);
  }

  return { kind: 'verdict', verdict: (await response.json()) as VerdictResponse };
}

export async function nearbyParking(lat: number, lng: number): Promise<NearbyRule[]> {
  const response = await fetch(`${buildBaseUrl()}/nearby?lat=${lat}&lng=${lng}`);

  if (!response.ok) {
    throw new Error(`Nearby request failed: ${response.status}`);
  }

  // Server wraps the list as {"rules": [...]} (plan §3), not a bare array.
  const body = (await response.json()) as { rules: NearbyRule[] };
  return body.rules;
}
