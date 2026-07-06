/**
 * Shared relative-time formatter used by the Stats views (incidents list + alarm-lifecycle
 * groups) so a timestamp reads the same everywhere. Pure function — no Angular deps — trivially
 * unit-testable. Absolute timestamps are rendered separately via Angular's DatePipe; this only
 * produces the human "… ago" form.
 *
 * Contract:
 *   - undefined / null / unparseable  -> '' (caller decides whether to show a dash)
 *   - < 45s                            -> 'just now'
 *   - seconds / minutes / hours / days -> 'Ns ago' / 'Nm ago' / 'Nh ago' / 'Nd ago'
 *   - future timestamps               -> 'just now' (clock skew is not surfaced as negative)
 */
export function relativeTime(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) {
    return '';
  }
  const then = Date.parse(iso);
  if (Number.isNaN(then)) {
    return '';
  }
  const deltaMs = now - then;
  const seconds = Math.floor(deltaMs / 1000);
  if (seconds < 45) {
    return 'just now';
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 1) {
    return `${seconds}s ago`;
  }
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}
