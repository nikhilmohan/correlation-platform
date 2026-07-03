import { describe, expect, it } from 'vitest';
import {
  alarmTypeLabel,
  derivePatternName,
  shortPatternId,
  PATTERN_NAME_SEPARATOR,
} from './alarm-type-labels';
import type { PatternView } from '../api/models';

/** Minimal PatternView factory — only the fields derivePatternName reads matter here. */
function pattern(over: Partial<PatternView> & { patternId: string }): PatternView {
  return {
    patternId: over.patternId,
    trailId: 'TR-1',
    sequence: [{ alarmType: 'IPLinkDown', optional: false }],
    rootCauseAlarmType: 'IPLinkDown',
    support: 0.1,
    confidence: 0.9,
    lift: 3,
    timing: { timeframeMs: 1000, medianInterArrivalMs: 100, maxInterArrivalMs: 200 },
    sessionWindow: { windowMs: 30000, type: 'session-gap' },
    codebookMatchId: null,
    structurallyValidated: true,
    structuralValidationReason: null,
    instanceCount: 1,
    occurrenceCount: 1,
    trailCount: 1,
    firstSeen: '2026-05-01T00:00:00Z',
    lastSeen: '2026-05-01T00:00:00Z',
    supportingInstances: [],
    lifecycle: 'draft',
    domain: 'core-ip',
    createdAt: '2026-05-01T00:00:00Z',
    ...over,
  } as PatternView;
}

describe('alarmTypeLabel', () => {
  it('maps known tokens to readable labels', () => {
    expect(alarmTypeLabel('IPLinkDown')).toBe('IP Link Down');
    expect(alarmTypeLabel('AdjDown')).toBe('Adjacency Down');
  });

  it('returns the raw token for unknown alarm types (never blank)', () => {
    expect(alarmTypeLabel('BrandNewAlarm')).toBe('BrandNewAlarm');
  });
});

describe('shortPatternId', () => {
  it('returns the first 8 hex chars of a UUID, dashes stripped, lower-cased', () => {
    expect(shortPatternId('02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f')).toBe('02007ff1');
    expect(shortPatternId('10B3918B-2c4d-4f6a-8b1e-9d0c1a2b3c4d')).toBe('10b3918b');
  });

  it('degrades to null for missing / non-hex / too-short ids (defensive, no "undefined")', () => {
    expect(shortPatternId(undefined)).toBeNull();
    expect(shortPatternId(null)).toBeNull();
    expect(shortPatternId('')).toBeNull();
    expect(shortPatternId('PAT-3')).toBeNull();
    expect(shortPatternId('abc')).toBeNull();
  });
});

describe('derivePatternName', () => {
  it('appends the 8-char patternId suffix to the derived "<Root cause> Cascade" name', () => {
    const name = derivePatternName(
      pattern({ patternId: '02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f', rootCauseAlarmType: 'IPLinkDown' }),
    );
    expect(name).toBe(`IP Link Down Cascade${PATTERN_NAME_SEPARATOR}02007ff1`);
  });

  it('gives two same-root-cause patterns DIFFERENT names via their distinct patternIds', () => {
    const a = derivePatternName(
      pattern({ patternId: '02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f', rootCauseAlarmType: 'IPLinkDown' }),
    );
    const b = derivePatternName(
      pattern({ patternId: '10b3918b-2c4d-4f6a-8b1e-9d0c1a2b3c4d', rootCauseAlarmType: 'IPLinkDown' }),
    );
    expect(a).not.toBe(b);
    expect(a).toContain('02007ff1');
    expect(b).toContain('10b3918b');
    // Same readable stem, different suffix.
    expect(a.startsWith('IP Link Down Cascade')).toBe(true);
    expect(b.startsWith('IP Link Down Cascade')).toBe(true);
  });

  it('returns an authored patternName AS-IS, without appending a suffix', () => {
    const authored = pattern({ patternId: '02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f' }) as PatternView & {
      patternName: string;
    };
    authored.patternName = 'London Metro Fiber Cut';
    expect(derivePatternName(authored)).toBe('London Metro Fiber Cut');
  });

  it('degrades gracefully to the bare label name when patternId is missing/short', () => {
    const missing = pattern({ patternId: '', rootCauseAlarmType: 'IPLinkDown' });
    expect(derivePatternName(missing)).toBe('IP Link Down Cascade');
    const shortId = pattern({ patternId: 'PAT-3', rootCauseAlarmType: 'IPLinkDown' });
    expect(derivePatternName(shortId)).toBe('IP Link Down Cascade');
  });
});
