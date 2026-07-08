import { AlarmSummary } from '../api/models';

/**
 * Alarm-severity attribution for the topology views (geo site map + site device graph).
 *
 * A site or a device NODE is coloured by the WORST severity among its ACTIVE alarms:
 *   - `red`   — any active `critical` OR `major` alarm (both collapse to ONE red bucket per spec),
 *   - `amber` — an active `minor` alarm (and NO critical/major),
 *   - `green` — otherwise (no active fault, or only `warning`/`cleared`).
 *
 * ACTIVE = `lifecycleState !== 'cleared'` (open, in-progress, correlated all represent a live
 * fault). A cleared alarm colours NOTHING. `warning` never yields amber — only `minor` does.
 *
 * NODE MATCHING is by the `N<number>` node TOKEN carried in the managedObjectId: an alarm on
 * `IPLink:N30_N31` attributes to BOTH `Node:N30` and `Node:N31`; `Port:N30-LC1-P1` and
 * `LineCard:N30-LC1` attribute to `Node:N30`; `Node:N30` attributes to `N30`. A moid can carry two
 * node tokens (link / adjacency / SRLG endpoints) → both endpoints light up. When a moid carries NO
 * `N<number>` token (e.g. the demo/mock ids like `Router:lon-r1`) the FULL moid string is used as
 * its sole token, so the same attribution works against both real (`Node:N30`) and mock data.
 *
 * All functions here are PURE (no Angular, no I/O) and unit-testable in isolation.
 */

/** The three colour buckets, ordered green < amber < red by worst-severity rank. */
export type SeverityBucket = 'green' | 'amber' | 'red';

/** Numeric rank so the worst bucket wins a reduction (higher = worse). */
const BUCKET_RANK: Record<SeverityBucket, number> = { green: 0, amber: 1, red: 2 };

/** Matches every `N<number>` node token in a managedObjectId (e.g. `IPLink:N30_N31` → N30, N31). */
const NODE_TOKEN_RE = /N\d+/g;

/**
 * Extract the set of node tokens a managedObjectId attributes to. Returns every `N<number>` token
 * found (a link/adjacency/SRLG moid yields two); when none is present the FULL moid string is the
 * sole token (so non-`N##` ids — demo/mock data — still match by exact moid). Empty/blank input
 * yields an empty set.
 */
export function nodeTokensOf(managedObjectId: string | null | undefined): Set<string> {
  const moid = (managedObjectId ?? '').trim();
  if (moid.length === 0) {
    return new Set();
  }
  const tokens = moid.match(NODE_TOKEN_RE);
  if (tokens && tokens.length > 0) {
    return new Set(tokens);
  }
  return new Set([moid]);
}

/** True when the alarm is ACTIVE (its lifecycle is anything other than `cleared`). */
export function isActiveAlarm(alarm: Pick<AlarmSummary, 'lifecycleState'>): boolean {
  return alarm.lifecycleState !== 'cleared';
}

/** Map a raw `perceivedSeverity` to its colour bucket (critical|major → red, minor → amber, else green). */
export function severityBucketOf(perceivedSeverity: string | null | undefined): SeverityBucket {
  switch ((perceivedSeverity ?? '').toLowerCase()) {
    case 'critical':
    case 'major':
      return 'red';
    case 'minor':
      return 'amber';
    default:
      // warning / cleared / unknown / empty → no colour contribution.
      return 'green';
  }
}

/** The worse of two buckets (higher rank wins). */
function worse(a: SeverityBucket, b: SeverityBucket): SeverityBucket {
  return BUCKET_RANK[a] >= BUCKET_RANK[b] ? a : b;
}

/**
 * WORST active-severity bucket among the alarms whose node token(s) intersect `tokens`.
 *
 * An alarm counts iff it is ACTIVE and shares at least one node token with `tokens`. The result is
 * the worst bucket across all such alarms — `red` if any is critical/major, else `amber` if any is
 * minor, else `green`. An empty `tokens` set or no matching active alarm → `green`.
 *
 * @param alarms the current alarm snapshot (a generous page — active + cleared mixed).
 * @param tokens the node tokens to attribute to (e.g. the tokens of one device, or the union of a
 *               site's devices). Compute with {@link nodeTokensOf}.
 */
export function worstBucketForTokens(
  alarms: readonly AlarmSummary[],
  tokens: ReadonlySet<string>,
): SeverityBucket {
  if (tokens.size === 0) {
    return 'green';
  }
  let worst: SeverityBucket = 'green';
  for (const alarm of alarms) {
    if (!isActiveAlarm(alarm)) {
      continue;
    }
    const alarmTokens = nodeTokensOf(alarm.managedObjectId);
    let matches = false;
    for (const t of alarmTokens) {
      if (tokens.has(t)) {
        matches = true;
        break;
      }
    }
    if (!matches) {
      continue;
    }
    worst = worse(worst, severityBucketOf(alarm.perceivedSeverity));
    if (worst === 'red') {
      return 'red'; // can't get worse — short-circuit.
    }
  }
  return worst;
}

/** The union of node tokens across a list of managedObjectIds (a site's devices → its token set). */
export function tokensForMoids(moids: readonly string[]): Set<string> {
  const union = new Set<string>();
  for (const moid of moids) {
    for (const t of nodeTokensOf(moid)) {
      union.add(t);
    }
  }
  return union;
}
