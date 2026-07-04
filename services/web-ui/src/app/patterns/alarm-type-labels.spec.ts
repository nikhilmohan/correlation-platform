import { describe, expect, it } from 'vitest';
import { alarmTypeLabel, derivePatternName } from './alarm-type-labels';
import type { PatternView } from '../api/models';

/** Minimal PatternView satisfying the fields derivePatternName reads; the rest are irrelevant. */
function pattern(overrides: Partial<PatternView>): PatternView {
  return {
    patternId: 'PAT-x',
    trailId: 'TR-x',
    sequence: [],
    rootCauseAlarmType: 'IPLinkDown',
    support: 0.1,
    confidence: 0.9,
    lift: 3,
    instanceCount: 1,
    lifecycle: 'draft',
    ...overrides,
  };
}

describe('alarmTypeLabel', () => {
  it('maps a known token to its operator-facing label', () => {
    expect(alarmTypeLabel('IPLinkDown')).toBe('IP Link Down');
    expect(alarmTypeLabel('AdjDown')).toBe('Adjacency Down');
  });

  it('returns the raw token unchanged for an unknown alarm type', () => {
    expect(alarmTypeLabel('SomeNewAlarm')).toBe('SomeNewAlarm');
  });
});

describe('derivePatternName', () => {
  it('renders the served patternName verbatim when present (the normal path)', () => {
    const p = pattern({ patternName: 'IP Link Down Cascade · 02007ff1' });
    expect(derivePatternName(p)).toBe('IP Link Down Cascade · 02007ff1');
  });

  it('trims surrounding whitespace on the served name', () => {
    const p = pattern({ patternName: '  Adjacency Down Cascade · 10b3918b  ' });
    expect(derivePatternName(p)).toBe('Adjacency Down Cascade · 10b3918b');
  });

  it('falls back to a plain "<label> Cascade" (no id suffix) when patternName is absent', () => {
    const p = pattern({ rootCauseAlarmType: 'IPLinkDown', patternName: undefined });
    expect(derivePatternName(p)).toBe('IP Link Down Cascade');
  });

  it('falls back to the plain derivation when patternName is blank/whitespace', () => {
    const p = pattern({ rootCauseAlarmType: 'AdjDown', patternName: '   ' });
    expect(derivePatternName(p)).toBe('Adjacency Down Cascade');
  });

  it('uses the raw root-cause token in the fallback when the alarm type is unknown', () => {
    const p = pattern({ rootCauseAlarmType: 'MysteryDown', patternName: undefined });
    expect(derivePatternName(p)).toBe('MysteryDown Cascade');
  });
});
