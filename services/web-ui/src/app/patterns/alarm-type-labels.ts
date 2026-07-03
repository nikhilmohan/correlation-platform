/**
 * Human-readable labels for the Core IP alarm vocabulary + pattern-name derivation.
 *
 * Generic, reusable, side-effect-free. Kept beside the Patterns feature (not in core/) because
 * it encodes Patterns-view presentation, not shared platform contract. The Pattern Store's raw
 * `alarmType` tokens (e.g. `AdjDown`) are the source of truth on the wire; these labels are a
 * pure presentation layer over them and never change the value sent back to the API.
 */

import type { PatternView } from '../api/models';

/** Raw alarm-type token -> operator-facing label. Unknown tokens fall back to the raw token. */
export const ALARM_TYPE_LABELS: Readonly<Record<string, string>> = Object.freeze({
  AdjDown: 'Adjacency Down',
  BGPPeerDown: 'BGP Peer Down',
  ISISAdjacencyDown: 'IS-IS Adjacency Down',
  OSPFAdjacencyDown: 'OSPF Adjacency Down',
  RouteFlap: 'Route Flap',
  LDPSessionDown: 'LDP Session Down',
  LSPDown: 'LSP Down',
  FRRSwitchover: 'FRR Switchover',
  TETunnelDown: 'TE Tunnel Down',
  LinkDown: 'Link Down',
  IPLinkDown: 'IP Link Down',
  FiberFault: 'Fiber Fault',
  LOS: 'Loss of Signal',
  LOF: 'Loss of Frame',
  InterfaceDown: 'Interface Down',
  PortDown: 'Port Down',
  PortFlap: 'Port Flap',
});

/**
 * Readable label for an alarm-type token. Unknown tokens return the raw token unchanged (never
 * blank) so a newly-authored alarm type still renders something meaningful.
 */
export function alarmTypeLabel(alarmType: string): string {
  return ALARM_TYPE_LABELS[alarmType] ?? alarmType;
}

/**
 * A logical, scannable name for a pattern. Prefers a future `patternName` field once the Pattern
 * Store serves one (cast because it is not yet on the frozen contract); otherwise derives a
 * "<Root cause> Cascade" name from the readable root-cause label.
 */
export function derivePatternName(p: PatternView): string {
  const authored = (p as { patternName?: string }).patternName?.trim();
  if (authored) {
    return authored;
  }
  return `${alarmTypeLabel(p.rootCauseAlarmType)} Cascade`;
}
