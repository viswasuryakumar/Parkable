export type VerdictResponse = {
  verdict: 'PARKABLE' | 'NOT_PARKABLE' | 'DEPENDS';
  reason: string;
  validUntil?: string;
  confidence?: number;
};

export type ScanRequest = {
  photoBase64?: string;
  lat?: number;
  lng?: number;
};

export type ScanResult = VerdictResponse & {
  scanId?: string;
};

const DEFAULT_BASE_URL = 'https://example.invalid';

function buildBaseUrl(): string {
  return process.env.EXPO_PUBLIC_API_BASE_URL ?? DEFAULT_BASE_URL;
}

export async function scanParking(payload: ScanRequest): Promise<ScanResult> {
  const response = await fetch(`${buildBaseUrl()}/scan`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });

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

export async function nearbyParking(lat: number, lng: number): Promise<VerdictResponse[]> {
  const response = await fetch(`${buildBaseUrl()}/nearby?lat=${lat}&lng=${lng}`);

  if (!response.ok) {
    throw new Error(`Nearby request failed: ${response.status}`);
  }

  return response.json() as Promise<VerdictResponse[]>;
}
