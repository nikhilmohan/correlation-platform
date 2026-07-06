import { describe, expect, it } from 'vitest';
import { relativeTime } from './relative-time';

describe('relativeTime', () => {
  const now = Date.parse('2026-06-01T12:00:00Z');

  it('returns empty string for undefined / null', () => {
    expect(relativeTime(undefined, now)).toBe('');
    expect(relativeTime(null, now)).toBe('');
  });

  it('returns empty string for an unparseable timestamp', () => {
    expect(relativeTime('not-a-date', now)).toBe('');
  });

  it('renders "just now" under 45 seconds', () => {
    expect(relativeTime('2026-06-01T11:59:59Z', now)).toBe('just now');
    expect(relativeTime('2026-06-01T11:59:20Z', now)).toBe('just now');
  });

  it('renders seconds between 45s and 60s', () => {
    expect(relativeTime('2026-06-01T11:59:10Z', now)).toBe('50s ago');
  });

  it('renders minutes', () => {
    expect(relativeTime('2026-06-01T11:58:00Z', now)).toBe('2m ago');
    expect(relativeTime('2026-06-01T11:01:00Z', now)).toBe('59m ago');
  });

  it('renders hours', () => {
    expect(relativeTime('2026-06-01T09:00:00Z', now)).toBe('3h ago');
  });

  it('renders days', () => {
    expect(relativeTime('2026-05-30T12:00:00Z', now)).toBe('2d ago');
  });

  it('treats future timestamps (clock skew) as "just now"', () => {
    expect(relativeTime('2026-06-01T12:05:00Z', now)).toBe('just now');
  });
});
