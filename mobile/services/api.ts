export type VerdictResponse = {
  verdict: 'PARKABLE' | 'NOT_PARKABLE' | 'DEPENDS';
  reason: string | null;
  rule_id?: string | null;
  valid_until?: string | null;
  source?: string | null;
  trace?: string[];
};

export type ScanRequest = {
  photo_base64: string;
  media_type: string;
  lat: number;
  lng: number;
  at?: string;
  zone?: string;
  side?: 'LEFT' | 'RIGHT';
};

export type ScanResult = VerdictResponse;

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
    const body = await response.json().catch(() => ({}));
    throw new Error((body as NeedsReviewResponse).message ?? 'Please retake the photo and try again.');
  }

  if (!response.ok) {
    throw new Error(`Scan request failed: ${response.status}`);
  }

  return response.json() as Promise<ScanResult>;
}

export async function checkParking(lat: number, lng: number): Promise<VerdictResponse> {
  const response = await fetch(`${buildBaseUrl()}/check?lat=${lat}&lng=${lng}`);

  if (!response.ok) {
    throw new Error(`Check request failed: ${response.status}`);
  }

  return response.json() as Promise<VerdictResponse>;
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
