// "2h ago" style ages for report / suspension timestamps (FIGMA_SPEC.md Screen
// 20: "Last report: 2h ago"). now is injected rather than read from Date.now()
// inside, so the function stays pure -- the caller decides what "now" is (a
// ticking clock, a fixed value in a test).
const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

export function relativeTime(iso: string | null | undefined, nowMs: number): string | null {
  if (!iso) {
    return null;
  }
  const thenMs = Date.parse(iso);
  if (Number.isNaN(thenMs)) {
    return null;
  }
  // A timestamp slightly in the future (client/server clock skew) reads as
  // "just now", never as a negative age.
  const ageMs = Math.max(0, nowMs - thenMs);
  if (ageMs < MINUTE_MS) {
    return 'just now';
  }
  if (ageMs < HOUR_MS) {
    return `${Math.floor(ageMs / MINUTE_MS)}m ago`;
  }
  if (ageMs < DAY_MS) {
    return `${Math.floor(ageMs / HOUR_MS)}h ago`;
  }
  return `${Math.floor(ageMs / DAY_MS)}d ago`;
}

// Whole-second age for the stats "Last updated: X seconds ago" caption.
export function describeAgeSeconds(seconds: number): string {
  if (seconds < 60) {
    return seconds === 1 ? '1 second ago' : `${seconds} seconds ago`;
  }
  const minutes = Math.floor(seconds / 60);
  return minutes === 1 ? '1 minute ago' : `${minutes} minutes ago`;
}
