/**
 * Client-side view-models aligned 1:1 to each producer's FROZEN OpenAPI 3.1 shape. The
 * producer's published `openapi.json` is the single source of truth (see design.md → Data
 * model). These are hand-pinned to the checked-in producer specs; on a collaborator contract
 * change they are regenerated (contract-change procedure: architecture.md + human approval).
 */

/** Open attribute map; the UI never validates the schema (Knowledge owns the catalogue). */
export type AttributeMap = Readonly<Record<string, unknown>>;

/** Logical layers derived from objectType (P1-G9). */
export type LogicalLayer = 'fiber' | 'IP' | 'IGP' | 'LSP' | 'service' | 'other';

// ---- Topology (frozen P1-G7/G8/G9) ----
export interface SiteDto {
  siteId: string;
  name: string;
  latitude: number;
  longitude: number;
  region: string;
}
export interface SiteListDto {
  domain: string;
  snapshotId: string;
  count: number;
  sites: SiteDto[];
}
export interface NodeDto {
  managedObjectId: string;
  objectType: string;
  domain: string;
  snapshotId: string;
  name?: string;
  attributes: AttributeMap;
}
export interface EdgeDto {
  edgeId: string;
  from: string;
  to: string;
  relation: string;
  domain: string;
  snapshotId: string;
  attributes: AttributeMap;
}
export interface SiteObjectsDto {
  siteId: string;
  domain: string;
  snapshotId: string;
  nodeCount: number;
  edgeCount: number;
  nodes: NodeDto[];
  edges: EdgeDto[];
}

/**
 * One neighbour of a node: the neighbour `node` and the `via` edge connecting them. Mirrors the
 * Topology OpenAPI `Neighbor` schema (the producer names it `Neighbor`; we use `NeighborEntry`
 * for the local view-model — the wire shape `{node, via}` is identical).
 */
export interface NeighborEntry {
  node: NodeDto;
  via: EdgeDto;
}
/** GET /topology/nodes/{id}/neighbors — Topology OpenAPI `NeighborsDto`. */
export interface NeighborsDto {
  managedObjectId: string;
  domain: string;
  neighbors: NeighborEntry[];
}
/** GET /topology/traversal — Topology OpenAPI `TraversalDto` (bounded BFS, maxDepth ≤ 32). */
export interface TraversalDto {
  start: string;
  domain: string;
  relations: string[];
  maxDepth: number;
  crossDomain: boolean;
  reached: NodeDto[];
  edges: EdgeDto[];
}

// ---- Trail Builder (frozen P1-G4/G10) ----
export interface TrailSummary {
  trailId: string;
  domain: string;
  memberCount: number;
  igpArea?: string | null;
  srlgGroup?: string | null;
}
export interface ListTrailsResponse {
  snapshotId: string;
  domain: string;
  count: number;
  trails: TrailSummary[];
}
export interface TrailMember {
  managedObjectId: string;
  objectType: string;
}
export interface TrailDetail {
  trailId: string;
  domain: string;
  snapshotId: string;
  members: TrailMember[];
  memberCount: number;
  igpArea?: string | null;
  srlgGroup?: string | null;
}
export interface TrailsForObjectResponse {
  managedObjectId: string;
  domain: string;
  trailIds: string[];
}

/** Platform-canonical pagination envelope used by every list API. */
export interface Page<T> {
  items: T[];
  total: number;
  limit: number;
  offset: number;
}

// ---- Pattern Manager (frozen P2-GAP-06/08, P3-G1) ----
export type PatternLifecycle = 'draft' | 'approved' | 'deprecated' | 'rejected';
export interface SequenceElement {
  alarmType: string;
  optional: boolean;
}
export interface SessionWindow {
  windowMs: number;
  type: string;
}
/**
 * Inter-arrival / span timing for a pattern. All fields optional numerics — the Pattern Store
 * populates whichever it computed. Types-only widening of the wire shape; no contract change.
 */
export interface PatternTiming {
  timeframeMs?: number;
  medianInterArrivalMs?: number;
  maxInterArrivalMs?: number;
  stddevInterArrivalMs?: number;
}
/**
 * One supporting-instance / provenance reference behind a pattern — a window + snapshot handle.
 * These are the source-window / snapshot provenance handles (demoted to a sub-section now that
 * the store serves real member alarms on `PatternView.sampleAlarms`). Extra occurrence keys are
 * preserved via the index signature.
 */
export interface SupportingInstance {
  sourceWindowId?: string;
  snapshotId?: string;
  occurrence?: { anchorScenarioId?: string | null; [k: string]: unknown };
}
/**
 * A concrete member alarm supporting a pattern — the REAL per-alarm evidence now served by the
 * Pattern Store (Pattern Manager OpenAPI `SampleAlarmView`). Bounded by K (miner-side, ~10) and
 * may be absent/empty for older patterns that predate the feature.
 */
export interface SampleAlarmView {
  alarmId: string;
  alarmType: string;
  /** ISO-8601 date-time string. */
  raisedAt: string;
  managedObjectId: string;
  perceivedSeverity: string;
}
export interface PatternView {
  patternId: string;
  /**
   * Readable pattern name served by the Pattern Store, of the form
   * `"<label> Cascade · <short8>"` (e.g. "IP Link Down Cascade · 02007ff1"). The name is owned +
   * persisted by Pattern Manager; the UI renders it verbatim. Optional for backward-compat with
   * older responses / mock fixtures that predate the field.
   */
  patternName?: string;
  trailId: string;
  sequence: SequenceElement[];
  rootCauseAlarmType: string;
  support: number;
  confidence: number;
  lift: number;
  timing?: PatternTiming;
  sessionWindow?: SessionWindow;
  codebookMatchId?: string | null;
  reconcileStatus?: string;
  structurallyValidated?: boolean;
  structuralValidationReason?: string | null;
  instanceCount: number;
  /**
   * Pattern impact / popularity metrics (Pattern Manager PR #360). `occurrenceCount` = times the
   * signature was observed (mined); `trailCount` = number of DISTINCT trails the signature spans
   * (the cross-trail significance signal); `firstSeen`/`lastSeen` = ISO-8601 observation span.
   * The producer serves these always-present now; kept optional for backward-compat with older
   * responses that predate the feature (the UI degrades gracefully — no NaN/undefined shown).
   */
  occurrenceCount?: number;
  trailCount?: number;
  firstSeen?: string;
  lastSeen?: string;
  supportingInstances?: SupportingInstance[];
  sampleAlarms?: SampleAlarmView[];
  lifecycle: PatternLifecycle;
  domain?: string | null;
  createdAt?: string;
  updatedAt?: string;
}
export type PatternPage = Page<PatternView>;
export interface SequenceFlag {
  index: number;
  optional: boolean;
}
export interface PatternEdit {
  sequenceFlags: SequenceFlag[];
  reviewer: string;
  notes?: string;
}
export interface PatternDecision {
  decision: 'approve' | 'reject';
  reviewer: string;
  notes?: string;
}

// ---- Knowledge (frozen P2-GAP-07) ----
export interface ModelParam {
  key: string;
  type: string;
  value: number | string | boolean;
  min?: number;
  max?: number;
  unit?: string;
}
export interface ModelParamsPayload {
  paramSet: string;
  params: ModelParam[];
}
export interface ModelParamsRecord {
  domain: string;
  recordType: string;
  recordId: string;
  version: string;
  isCurrent: boolean;
  payload: ModelParamsPayload;
}

// ---- Correlation Engine (frozen P3-G3/G4 + PR #166) ----
export interface IncidentVM {
  incidentId: string;
  rootCauseAlarmId: string;
  rootCauseAlarmType?: string;
  childAlarmIds: string[];
  matchedPatternId?: string | null;
  matchedCodebookId?: string | null;
  confidence: number;
  trailId: string;
  createdAt?: string;
}
export type IncidentPage = Page<IncidentVM>;
export interface StatsVM {
  totalAlarmsProcessed: number;
  correlatedAlarmCount?: number;
  totalIncidentsCreated: number;
  patternMatchCount?: number;
  codebookMatchCount?: number;
  confidenceDistribution?: Readonly<Record<string, number>>;
  rcaAccuracy?: number | null;
}

// ---- Alarm Manager (frozen P3-G3) ----
export type LifecycleState = 'open' | 'in-progress' | 'correlated' | 'cleared';
export type AlarmRole = 'root-cause' | 'child' | 'none';
export interface AlarmSummary {
  alarmId: string;
  managedObjectId: string;
  eventType: string;
  /**
   * Canonical Core-IP alarm-type token (e.g. `LOS`, `LinkDown`) when the Alarm Manager `/alarms`
   * response carries it. Optional because it post-dates the frozen `eventType` field; the grouped
   * Alarm-Lifecycle view falls back to `eventType` when absent. Passes through the generic client
   * deserialization untouched.
   */
  alarmType?: string;
  perceivedSeverity?: string;
  raisedAt?: string;
  lifecycleState: LifecycleState;
  role: AlarmRole;
  incidentId?: string | null;
  trailIds?: string[];
}
export type AlarmPage = Page<AlarmSummary>;
/**
 * One correlation (incident) group for the Alarm-Lifecycle view: the root-cause alarm (may be
 * null if the incident's RCA alarm role is not yet resolved in the Alarm Manager) plus its child
 * alarms. `rootCauseAlarmType` carries the incident-declared RCA type used as a graceful fallback
 * label when `rootCause` is null.
 */
export interface CorrelationGroup {
  incidentId: string;
  rootCause: AlarmSummary | null;
  rootCauseAlarmType?: string | null;
  children: AlarmSummary[];
}
export interface AlarmTransition {
  toState: string;
  reason?: string;
  source?: string;
  changedAt?: string;
  occurredAt?: string;
}
export interface AlarmDetail extends AlarmSummary {
  transitions: AlarmTransition[];
}

// ---- Noise Filter run-stats + observed-chatter ----
export interface RunStatsRow {
  runId: string;
  runTimestamp: string;
  trailId: string;
  snapshotId: string;
  domain?: string | null;
  windowStart: string;
  windowEnd: string;
  eps: number;
  minSamples: number;
  windowSize: number;
  algorithm: string;
  alarmsIn: number;
  clustersFormed: number;
  alarmsKept: number;
  alarmsDropped: number;
  noiseRatio: number;
  stormReductionRatio?: number;
  stormMaxClusterSize?: number;
  retentionVsOracle?: number | null;
  hopFeatureEnabled?: boolean;
}
export type RunStatsPage = Page<RunStatsRow>;
export interface ObservedChatterSignature {
  managedObjectId: string | null;
  alarmType: string;
  eventType: string;
  trailId: string | null;
  occurrenceCount: number;
  firstSeen: string;
  lastSeen: string;
}
export type ObservedChatterPage = Page<ObservedChatterSignature>;

// ---- Enrichment chatter (FLAGGED — expected shape until producer OpenAPI lands) ----
export interface EnrichmentChatterEntry {
  managedObjectId: string | null;
  eventType: string;
}
export interface EnrichmentChatterList {
  source: string;
  chatterList: EnrichmentChatterEntry[];
}

// ---- Simulator labels (frozen, demo/eval RCA oracle) ----
export interface GroundTruthLabel {
  scenarioId: string;
  scenarioType: string;
  rootCause: string;
  rootCauseManagedObjectId: string;
  rootCauseAlarmType: string;
  children: string[];
}
