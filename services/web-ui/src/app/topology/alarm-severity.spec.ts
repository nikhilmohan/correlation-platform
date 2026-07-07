import { describe, expect, it } from 'vitest';
import { AlarmSummary } from '../api/models';
import {
  isActiveAlarm,
  nodeTokensOf,
  severityBucketOf,
  tokensForMoids,
  worstBucketForTokens,
} from './alarm-severity';

/** Build a minimal AlarmSummary for the attribution tests (only the fields the logic reads matter). */
function alarm(partial: Partial<AlarmSummary> & { managedObjectId: string }): AlarmSummary {
  return {
    alarmId: partial.alarmId ?? `alm-${Math.random()}`,
    managedObjectId: partial.managedObjectId,
    eventType: partial.eventType ?? 'X',
    perceivedSeverity: partial.perceivedSeverity,
    lifecycleState: partial.lifecycleState ?? 'open',
    role: partial.role ?? 'none',
  };
}

describe('alarm-severity — node-token extraction', () => {
  it('pulls the N<number> token(s) from a moid; a link/adjacency/SRLG moid yields BOTH endpoints', () => {
    expect([...nodeTokensOf('Node:N30')]).toEqual(['N30']);
    expect([...nodeTokensOf('Port:N30-LC1-P1')]).toEqual(['N30']);
    expect([...nodeTokensOf('LineCard:N36-LC1')]).toEqual(['N36']);
    // Two-token link moid → both endpoints.
    expect([...nodeTokensOf('IPLink:N30_N31')].sort()).toEqual(['N30', 'N31']);
    expect([...nodeTokensOf('IGPAdj:N0_N2')].sort()).toEqual(['N0', 'N2']);
  });

  it('falls back to the FULL moid string when there is no N<number> token (mock/demo ids)', () => {
    expect([...nodeTokensOf('Router:lon-r1')]).toEqual(['Router:lon-r1']);
    expect([...nodeTokensOf('FiberSpan:lon-fra-1')]).toEqual(['FiberSpan:lon-fra-1']);
  });

  it('empty / blank input yields an empty token set', () => {
    expect(nodeTokensOf('').size).toBe(0);
    expect(nodeTokensOf('   ').size).toBe(0);
    expect(nodeTokensOf(null).size).toBe(0);
    expect(nodeTokensOf(undefined).size).toBe(0);
  });
});

describe('alarm-severity — severity → bucket mapping', () => {
  it('critical and major both map to the SAME red bucket', () => {
    expect(severityBucketOf('critical')).toBe('red');
    expect(severityBucketOf('major')).toBe('red');
    expect(severityBucketOf('MAJOR')).toBe('red'); // case-insensitive
  });
  it('minor → amber; warning/cleared/unknown/empty → green', () => {
    expect(severityBucketOf('minor')).toBe('amber');
    expect(severityBucketOf('warning')).toBe('green');
    expect(severityBucketOf('cleared')).toBe('green');
    expect(severityBucketOf('info')).toBe('green');
    expect(severityBucketOf(undefined)).toBe('green');
  });
});

describe('alarm-severity — active-only', () => {
  it('an alarm is active iff its lifecycle is not cleared', () => {
    expect(isActiveAlarm({ lifecycleState: 'open' })).toBe(true);
    expect(isActiveAlarm({ lifecycleState: 'in-progress' })).toBe(true);
    expect(isActiveAlarm({ lifecycleState: 'correlated' })).toBe(true);
    expect(isActiveAlarm({ lifecycleState: 'cleared' })).toBe(false);
  });
});

describe('alarm-severity — worstBucketForTokens attribution (the spec table)', () => {
  it('critical on a link + minor on a port → both endpoints RED (worst wins, critical)', () => {
    const alarms = [
      alarm({ managedObjectId: 'IPLink:N30_N31', perceivedSeverity: 'critical', lifecycleState: 'open' }),
      alarm({ managedObjectId: 'Port:N31-LC1-P1', perceivedSeverity: 'minor', lifecycleState: 'open' }),
    ];
    // N30 sees only the critical link → red; N31 sees critical link + minor port → red (worst wins).
    expect(worstBucketForTokens(alarms, nodeTokensOf('Node:N30'))).toBe('red');
    expect(worstBucketForTokens(alarms, nodeTokensOf('Node:N31'))).toBe('red');
  });

  it('a node with ONLY a minor alarm → amber', () => {
    const alarms = [alarm({ managedObjectId: 'Port:N40-LC1-P1', perceivedSeverity: 'minor', lifecycleState: 'open' })];
    expect(worstBucketForTokens(alarms, nodeTokensOf('Node:N40'))).toBe('amber');
  });

  it('a node with ONLY warning/cleared alarms → green (warning is NOT amber; cleared ignored)', () => {
    const alarms = [
      alarm({ managedObjectId: 'Node:N50', perceivedSeverity: 'warning', lifecycleState: 'open' }),
      alarm({ managedObjectId: 'Node:N50', perceivedSeverity: 'critical', lifecycleState: 'cleared' }),
    ];
    expect(worstBucketForTokens(alarms, nodeTokensOf('Node:N50'))).toBe('green');
  });

  it('a CLEARED critical does NOT colour anything (active-only)', () => {
    const alarms = [alarm({ managedObjectId: 'Node:N60', perceivedSeverity: 'critical', lifecycleState: 'cleared' })];
    expect(worstBucketForTokens(alarms, nodeTokensOf('Node:N60'))).toBe('green');
  });

  it('a two-token link moid lights BOTH endpoints (a critical link → both nodes red)', () => {
    const alarms = [alarm({ managedObjectId: 'IPLink:N30_N31', perceivedSeverity: 'major', lifecycleState: 'correlated' })];
    expect(worstBucketForTokens(alarms, nodeTokensOf('Node:N30'))).toBe('red');
    expect(worstBucketForTokens(alarms, nodeTokensOf('Node:N31'))).toBe('red');
    // A node NOT on the link is unaffected.
    expect(worstBucketForTokens(alarms, nodeTokensOf('Node:N99'))).toBe('green');
  });

  it('an empty token set → green (no attribution)', () => {
    const alarms = [alarm({ managedObjectId: 'Node:N30', perceivedSeverity: 'critical', lifecycleState: 'open' })];
    expect(worstBucketForTokens(alarms, new Set())).toBe('green');
  });
});

describe('alarm-severity — site-level token union', () => {
  it('a site colours by the WORST active alarm across ALL its devices', () => {
    const siteMoids = ['Node:N30', 'LineCard:N30-LC1', 'Port:N31-LC1-P1'];
    const tokens = tokensForMoids(siteMoids);
    expect([...tokens].sort()).toEqual(['N30', 'N31']);
    const alarms = [
      alarm({ managedObjectId: 'Node:N30', perceivedSeverity: 'minor', lifecycleState: 'open' }),
      alarm({ managedObjectId: 'IPLink:N31_N32', perceivedSeverity: 'critical', lifecycleState: 'open' }),
    ];
    // N31 is a site device; the critical link touches N31 → the site is red (worst across its nodes).
    expect(worstBucketForTokens(alarms, tokens)).toBe('red');
  });
});
