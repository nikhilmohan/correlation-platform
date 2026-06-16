import { HttpRequest } from '@angular/common/http';
import {
  AlarmDetail,
  AlarmPage,
  EnrichmentChatterList,
  GroundTruthLabel,
  IncidentPage,
  IncidentVM,
  ListTrailsResponse,
  ModelParamsRecord,
  NeighborsDto,
  ObservedChatterPage,
  PatternPage,
  RunStatsPage,
  SiteListDto,
  SiteObjectsDto,
  StatsVM,
  TraversalDto,
  TrailDetail,
  TrailsForObjectResponse,
} from '../api/models';

interface MockHandler {
  matches(req: HttpRequest<unknown>): boolean;
  respond(req: HttpRequest<unknown>): unknown;
}

const has = (url: string, frag: string) => url.includes(frag);

// --- Topology fixtures (P1 — fully functional path) ---
const SITES: SiteListDto = {
  domain: 'core-ip',
  snapshotId: 'current',
  count: 3,
  sites: [
    { siteId: 'Site:LON', name: 'London PoP', latitude: 51.5, longitude: -0.12, region: 'EU-West' },
    { siteId: 'Site:FRA', name: 'Frankfurt PoP', latitude: 50.11, longitude: 8.68, region: 'EU-Central' },
    { siteId: 'Site:MAD', name: 'Madrid PoP', latitude: 40.42, longitude: -3.7, region: 'EU-SouthWest' },
  ],
};

const SITE_OBJECTS: SiteObjectsDto = {
  siteId: 'Site:LON',
  domain: 'core-ip',
  snapshotId: 'current',
  nodeCount: 6,
  edgeCount: 5,
  nodes: [
    {
      managedObjectId: 'Router:lon-r1',
      objectType: 'Router',
      domain: 'core-ip',
      snapshotId: 'current',
      name: 'lon-r1',
      attributes: { vendor: 'Acme', model: 'R8000', equipmentType: 'router', slotCount: 16 },
    },
    {
      managedObjectId: 'Interface:lon-r1-e1',
      objectType: 'Interface',
      domain: 'core-ip',
      snapshotId: 'current',
      name: 'e1',
      attributes: { vendor: 'Acme', model: 'X1', equipmentType: 'port' },
    },
    {
      managedObjectId: 'FiberSpan:lon-fra-1',
      objectType: 'FiberSpan',
      domain: 'core-ip',
      snapshotId: 'current',
      name: 'LON-FRA fiber',
      attributes: { capacity: '100G' },
    },
    {
      managedObjectId: 'LSP:lon-fra-lsp1',
      objectType: 'LSP',
      domain: 'core-ip',
      snapshotId: 'current',
      name: 'lsp1',
      attributes: {},
    },
    {
      managedObjectId: 'SRLG:srlg-2',
      objectType: 'SRLG',
      domain: 'core-ip',
      snapshotId: 'current',
      name: 'SRLG-2',
      attributes: { riskGroup: 'shared-conduit' },
    },
    {
      managedObjectId: 'LineCard:lon-r1-lc1',
      objectType: 'LineCard',
      domain: 'core-ip',
      snapshotId: 'current',
      name: 'lc1',
      attributes: { slot: 1 },
    },
  ],
  edges: [
    {
      edgeId: 'e-1',
      from: 'Router:lon-r1',
      to: 'Interface:lon-r1-e1',
      relation: 'HAS_PORT',
      domain: 'core-ip',
      snapshotId: 'current',
      attributes: {},
    },
    {
      edgeId: 'e-2',
      from: 'Interface:lon-r1-e1',
      to: 'FiberSpan:lon-fra-1',
      relation: 'CONNECTS',
      domain: 'core-ip',
      snapshotId: 'current',
      attributes: { linkType: 'fiber', capacity: '100G', protectionRole: 'primary' },
    },
    {
      edgeId: 'e-3',
      from: 'Router:lon-r1',
      to: 'LSP:lon-fra-lsp1',
      relation: 'CARRIES',
      domain: 'core-ip',
      snapshotId: 'current',
      attributes: {},
    },
    // ── #263 regression fixtures ──────────────────────────────────────────────────────────────
    // Two typed edges whose endpoint-prefix derivation used to fall through to the un-toggleable
    // `other` layer (so they survived all-layers-off): an SRLG `MEMBER_OF` (shared-risk fiber
    // grouping) and a `HOSTED_ON` structural containment. The relation→layer mapping now governs
    // them (MEMBER_OF → fiber, HOSTED_ON → IGP), so all-off renders 0 edges. Mirrors the real
    // Topology site subgraph that surfaced #263.
    {
      edgeId: 'e-4',
      from: 'FiberSpan:lon-fra-1',
      to: 'SRLG:srlg-2',
      relation: 'MEMBER_OF',
      domain: 'core-ip',
      snapshotId: 'current',
      attributes: { srlgGroup: 'SRLG-2' },
    },
    {
      edgeId: 'e-5',
      from: 'LineCard:lon-r1-lc1',
      to: 'Router:lon-r1',
      relation: 'HOSTED_ON',
      domain: 'core-ip',
      snapshotId: 'current',
      attributes: {},
    },
    // LOCATED_AT placement edges device→Site so nodeSiteMap is populated at root (compound boxes).
    ...['Router:lon-r1', 'Interface:lon-r1-e1', 'FiberSpan:lon-fra-1', 'LSP:lon-fra-lsp1', 'SRLG:srlg-2', 'LineCard:lon-r1-lc1'].map(
      (id, i) => ({
        edgeId: `loc-lon-${i}`,
        from: id,
        to: 'Site:LON',
        relation: 'LOCATED_AT',
        domain: 'core-ip',
        snapshotId: 'current',
        attributes: {},
      }),
    ),
  ],
};

// Second site (Frankfurt) — its own objects + LOCATED_AT edges so rooting at it shows its own box,
// and so cross-site expand / trail-explode (which pulls FRA nodes) produces a SECOND site box.
const FRA_OBJECTS: SiteObjectsDto = {
  siteId: 'Site:FRA',
  domain: 'core-ip',
  snapshotId: 'current',
  nodeCount: 3,
  edgeCount: 2,
  nodes: [
    { managedObjectId: 'Router:fra-r1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', name: 'fra-r1', attributes: { vendor: 'Acme', model: 'R8000', equipmentType: 'router' } },
    { managedObjectId: 'Interface:fra-r1-e1', objectType: 'Interface', domain: 'core-ip', snapshotId: 'current', name: 'e1', attributes: { vendor: 'Acme', model: 'X1', equipmentType: 'port' } },
    { managedObjectId: 'FiberSpan:lon-fra-1', objectType: 'FiberSpan', domain: 'core-ip', snapshotId: 'current', name: 'LON-FRA fiber', attributes: { capacity: '100G' } },
  ],
  edges: [
    { edgeId: 'fe-1', from: 'Router:fra-r1', to: 'Interface:fra-r1-e1', relation: 'HAS_PORT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    ...['Router:fra-r1', 'Interface:fra-r1-e1'].map((id, i) => ({
      edgeId: `loc-fra-${i}`,
      from: id,
      to: 'Site:FRA',
      relation: 'LOCATED_AT',
      domain: 'core-ip',
      snapshotId: 'current',
      attributes: {},
    })),
  ],
};

const SITE_OBJECTS_BY_ID: Record<string, SiteObjectsDto> = {
  'Site:LON': SITE_OBJECTS,
  'Site:FRA': FRA_OBJECTS,
};

/**
 * Neighbour fixtures keyed by node id. The Router:lon-r1 entry pulls a node in ANOTHER site
 * (Router:fra-r1) plus a LOCATED_AT edge to Site:FRA, so an EXPAND of lon-r1 crosses the site
 * boundary → distinct-site count 1 → 2. Trail members in FRA resolve through here too (so a
 * trail-explode pulls the cross-site member node + its LOCATED_AT placement).
 */
const NEIGHBORS_BY_ID: Record<string, NeighborsDto> = {
  'Router:lon-r1': {
    managedObjectId: 'Router:lon-r1',
    domain: 'core-ip',
    neighbors: [
      {
        node: { managedObjectId: 'Router:fra-r1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', name: 'fra-r1', attributes: { vendor: 'Acme', model: 'R8000' } },
        via: { edgeId: 'nx-1', from: 'Router:lon-r1', to: 'Router:fra-r1', relation: 'ADJACENCY_OVER', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      },
      {
        // Carry the cross-site placement so nodeSiteMap learns Router:fra-r1 → Site:FRA on expand.
        node: { managedObjectId: 'Site:FRA', objectType: 'Site', domain: 'core-ip', snapshotId: 'current', name: 'Frankfurt PoP', attributes: {} },
        via: { edgeId: 'nx-2', from: 'Router:fra-r1', to: 'Site:FRA', relation: 'LOCATED_AT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      },
    ],
  },
  'Router:fra-r1': {
    managedObjectId: 'Router:fra-r1',
    domain: 'core-ip',
    neighbors: [
      {
        node: { managedObjectId: 'Interface:fra-r1-e1', objectType: 'Interface', domain: 'core-ip', snapshotId: 'current', name: 'e1', attributes: {} },
        via: { edgeId: 'nx-3', from: 'Router:fra-r1', to: 'Interface:fra-r1-e1', relation: 'HAS_PORT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      },
      {
        node: { managedObjectId: 'Site:FRA', objectType: 'Site', domain: 'core-ip', snapshotId: 'current', name: 'Frankfurt PoP', attributes: {} },
        via: { edgeId: 'nx-4', from: 'Router:fra-r1', to: 'Site:FRA', relation: 'LOCATED_AT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      },
    ],
  },
};

function neighborsFor(managedObjectId: string): NeighborsDto {
  return (
    NEIGHBORS_BY_ID[managedObjectId] ?? {
      managedObjectId,
      domain: 'core-ip',
      neighbors: [],
    }
  );
}

function objectsForSite(siteId: string): SiteObjectsDto {
  return SITE_OBJECTS_BY_ID[siteId] ?? { ...SITE_OBJECTS, siteId };
}

const TRAILS: ListTrailsResponse = {
  snapshotId: 'current',
  domain: 'core-ip',
  count: 2,
  trails: [
    { trailId: 'TR-7', domain: 'core-ip', memberCount: 4, igpArea: '0.0.0.0', srlgGroup: null },
    { trailId: 'TR-8', domain: 'core-ip', memberCount: 2, igpArea: '0.0.0.0', srlgGroup: 'SRLG-2' },
  ],
};

// TR-7 spans TWO sites (LON + FRA) so selectTrail explodes cross-site and the distinct-site count
// increases (Router:fra-r1 resolves through NEIGHBORS_BY_ID, pulling its LOCATED_AT → Site:FRA).
const TRAIL_DETAIL: TrailDetail = {
  trailId: 'TR-7',
  domain: 'core-ip',
  snapshotId: 'current',
  memberCount: 4,
  igpArea: '0.0.0.0',
  srlgGroup: null,
  members: [
    { managedObjectId: 'Router:lon-r1', objectType: 'Router' },
    { managedObjectId: 'Interface:lon-r1-e1', objectType: 'Interface' },
    { managedObjectId: 'FiberSpan:lon-fra-1', objectType: 'FiberSpan' },
    { managedObjectId: 'Router:fra-r1', objectType: 'Router' },
  ],
};

const TRAILS_FOR_OBJECT: TrailsForObjectResponse = {
  managedObjectId: 'Router:lon-r1',
  domain: 'core-ip',
  trailIds: ['TR-7', 'TR-8'],
};

// --- Pattern Manager fixtures (P2) ---
const PATTERNS: PatternPage = {
  total: 2,
  limit: 50,
  offset: 0,
  items: [
    {
      patternId: 'PAT-3',
      trailId: 'TR-7',
      sequence: [
        { alarmType: 'LOS', optional: false },
        { alarmType: 'LinkDown', optional: false },
        { alarmType: 'AdjDown', optional: true },
      ],
      rootCauseAlarmType: 'LOS',
      support: 0.12,
      confidence: 0.9,
      lift: 4.2,
      timing: { medianIatMs: 1200 },
      sessionWindow: { windowMs: 30000, type: 'session-gap' },
      codebookMatchId: 'CB-2',
      structurallyValidated: true,
      structuralValidationReason: null,
      instanceCount: 18,
      supportingInstances: [{ id: 'inst-1' }],
      lifecycle: 'draft',
      domain: 'core-ip',
      createdAt: '2026-05-01T00:00:00Z',
    },
    {
      patternId: 'PAT-1',
      trailId: 'TR-8',
      sequence: [
        { alarmType: 'PortFlap', optional: false },
        { alarmType: 'LinkDown', optional: false },
      ],
      rootCauseAlarmType: 'PortFlap',
      support: 0.2,
      confidence: 0.85,
      lift: 3.1,
      timing: { medianIatMs: 800 },
      sessionWindow: { windowMs: 30000, type: 'session-gap' },
      codebookMatchId: null,
      structurallyValidated: false,
      structuralValidationReason: 'no codebook overlap',
      instanceCount: 30,
      supportingInstances: [],
      lifecycle: 'approved',
      domain: 'core-ip',
      createdAt: '2026-04-20T00:00:00Z',
    },
  ],
};

// --- Knowledge fixtures (P2) ---
const MODEL_PARAMS: ModelParamsRecord = {
  domain: 'core-ip',
  recordType: 'modelParams',
  recordId: 'noise-filter',
  version: 'v3',
  isCurrent: true,
  payload: {
    paramSet: 'noise-filter',
    params: [
      { key: 'dbscan.epsilon', type: 'number', value: 0.5, min: 0, max: 100 },
      { key: 'dbscan.minSamples', type: 'number', value: 3, min: 1, max: 1000 },
      { key: 'window.sizeSeconds', type: 'number', value: 60, min: 1, max: 86400, unit: 's' },
      { key: 'prefixspan.minSupport', type: 'number', value: 0.3, min: 0, max: 1 },
    ],
  },
};

// --- Correlation Engine fixtures (P3) ---
const STATS: StatsVM = {
  totalAlarmsProcessed: 1280,
  correlatedAlarmCount: 768,
  totalIncidentsCreated: 154,
  patternMatchCount: 42,
  codebookMatchCount: 17,
  rcaAccuracy: 0.86,
};

const INCIDENTS: IncidentVM[] = [
  {
    incidentId: 'INC-12',
    rootCauseAlarmId: 'a-3',
    rootCauseAlarmType: 'LOS',
    childAlarmIds: ['a-7', 'a-8'],
    matchedPatternId: 'PAT-3',
    matchedCodebookId: null,
    confidence: 0.91,
    trailId: 'TR-7',
    createdAt: '2026-06-01T12:00:00Z',
  },
  {
    incidentId: 'INC-11',
    rootCauseAlarmId: 'a-20',
    rootCauseAlarmType: 'LinkDown',
    childAlarmIds: ['a-21'],
    matchedPatternId: null,
    matchedCodebookId: 'CB-2',
    confidence: 0.77,
    trailId: 'TR-8',
    createdAt: '2026-06-01T11:50:00Z',
  },
];

const INCIDENT_PAGE: IncidentPage = { items: INCIDENTS, total: 2, limit: 50, offset: 0 };

// --- Alarm Manager fixtures (P3) ---
const ALARMS: AlarmPage = {
  total: 5,
  limit: 50,
  offset: 0,
  items: [
    { alarmId: 'a-3', managedObjectId: 'FiberSpan:lon-fra-1', eventType: 'LOS', perceivedSeverity: 'critical', raisedAt: '2026-06-01T12:00:00Z', lifecycleState: 'correlated', role: 'root-cause', incidentId: 'INC-12', trailIds: ['TR-7'] },
    { alarmId: 'a-7', managedObjectId: 'Interface:lon-r1-e1', eventType: 'LinkDown', lifecycleState: 'correlated', role: 'child', incidentId: 'INC-12', trailIds: ['TR-7'] },
    { alarmId: 'a-2', managedObjectId: 'Router:lon-r1', eventType: 'CpuHigh', lifecycleState: 'in-progress', role: 'none', incidentId: null, trailIds: [] },
    { alarmId: 'a-1', managedObjectId: 'Router:lon-r1', eventType: 'PortFlap', lifecycleState: 'open', role: 'none', incidentId: null, trailIds: [] },
    { alarmId: 'a-9', managedObjectId: 'Router:lon-r1', eventType: 'AdjDown', lifecycleState: 'cleared', role: 'none', incidentId: null, trailIds: [] },
  ],
};

const ALARM_DETAILS: Record<string, AlarmDetail> = {
  'a-3': { ...ALARMS.items[0], transitions: [{ toState: 'open', occurredAt: '2026-06-01T12:00:00Z' }, { toState: 'correlated', occurredAt: '2026-06-01T12:00:05Z' }] },
  'a-7': { ...ALARMS.items[1], transitions: [{ toState: 'open', occurredAt: '2026-06-01T12:00:01Z' }, { toState: 'correlated', occurredAt: '2026-06-01T12:00:06Z' }] },
  'a-8': {
    alarmId: 'a-8',
    managedObjectId: 'Router:lon-r1',
    eventType: 'AdjDown',
    lifecycleState: 'correlated',
    role: 'child',
    incidentId: 'INC-12',
    trailIds: ['TR-7'],
    transitions: [{ toState: 'open', occurredAt: '2026-06-01T12:00:02Z' }, { toState: 'correlated', occurredAt: '2026-06-01T12:00:07Z' }],
  },
};

// --- Noise Filter fixtures (P2) ---
const RUN_STATS: RunStatsPage = {
  total: 3,
  limit: 50,
  offset: 0,
  items: [
    { runId: 'RUN-9', runTimestamp: '2026-05-10T00:00:00Z', trailId: 'TR-7', snapshotId: 'current', domain: 'core-ip', windowStart: '2026-05-10T00:00:00Z', windowEnd: '2026-05-10T00:10:00Z', eps: 0.5, minSamples: 3, windowSize: 60, algorithm: 'dbscan', alarmsIn: 240, clustersFormed: 12, alarmsKept: 180, alarmsDropped: 60, noiseRatio: 0.25 },
    { runId: 'RUN-8', runTimestamp: '2026-05-09T00:00:00Z', trailId: 'TR-7', snapshotId: 'current', domain: null, windowStart: '2026-05-09T00:00:00Z', windowEnd: '2026-05-09T00:10:00Z', eps: 0.5, minSamples: 3, windowSize: 60, algorithm: 'dbscan', alarmsIn: 180, clustersFormed: 10, alarmsKept: 150, alarmsDropped: 30, noiseRatio: 0.17 },
    { runId: 'RUN-7', runTimestamp: '2026-05-08T00:00:00Z', trailId: 'TR-8', snapshotId: 'current', domain: 'core-ip', windowStart: '2026-05-08T00:00:00Z', windowEnd: '2026-05-08T00:10:00Z', eps: 0.5, minSamples: 3, windowSize: 60, algorithm: 'dbscan', alarmsIn: 90, clustersFormed: 5, alarmsKept: 70, alarmsDropped: 20, noiseRatio: 0.22 },
  ],
};

const OBSERVED_CHATTER: ObservedChatterPage = {
  total: 3,
  limit: 50,
  offset: 0,
  items: [
    { managedObjectId: 'Interface:e1-12', alarmType: 'LinkDown', eventType: 'linkDown', trailId: 'TR-7', occurrenceCount: 142, firstSeen: '2026-05-01T00:00:00Z', lastSeen: '2026-05-10T00:00:00Z' },
    { managedObjectId: null, alarmType: 'PortFlap', eventType: 'portFlap', trailId: null, occurrenceCount: 88, firstSeen: '2026-05-01T00:00:00Z', lastSeen: '2026-05-10T00:00:00Z' },
    { managedObjectId: 'Port:c1-3-7', alarmType: 'CRCError', eventType: 'crcError', trailId: 'TR-8', occurrenceCount: 51, firstSeen: '2026-05-02T00:00:00Z', lastSeen: '2026-05-10T00:00:00Z' },
  ],
};

const ENRICHMENT_CHATTER: Record<string, EnrichmentChatterList> = {
  'nms-alpha': { source: 'nms-alpha', chatterList: [{ managedObjectId: 'Interface:e1-12', eventType: 'linkDown' }] },
  default: { source: 'default', chatterList: [] },
};

const LABELS: GroundTruthLabel[] = [
  { scenarioId: 'sc-1', scenarioType: 'fiber-cut', rootCause: 'FiberSpan:lon-fra-1', rootCauseManagedObjectId: 'FiberSpan:lon-fra-1', rootCauseAlarmType: 'LOS', children: ['LinkDown', 'AdjDown'] },
  { scenarioId: 'sc-2', scenarioType: 'card-fail', rootCause: 'Router:lon-r1', rootCauseManagedObjectId: 'Router:lon-r1', rootCauseAlarmType: 'CardFail', children: ['PortFlap'] },
];

export const MOCK_FIXTURES: MockHandler[] = [
  { matches: (r) => has(r.url, '/topology/sites/') && has(r.url, '/objects'), respond: (r) => objectsForSite(siteIdFromObjectsUrl(r)) },
  { matches: (r) => has(r.url, '/topology/sites'), respond: () => SITES },
  // Neighbours MUST be matched BEFORE the generic /topology/nodes/ (resolveNode) handler.
  { matches: (r) => has(r.url, '/topology/nodes/') && has(r.url, '/neighbors'), respond: (r) => neighborsFor(neighborsNodeId(r)) },
  { matches: (r) => has(r.url, '/topology/traversal'), respond: (r) => traversalFor(r) },
  { matches: (r) => has(r.url, '/topology/nodes/'), respond: () => SITE_OBJECTS.nodes[0] },
  { matches: (r) => has(r.url, '/trails/by-object'), respond: () => TRAILS_FOR_OBJECT },
  { matches: (r) => /\/trails\/[^/?]+$/.test(r.url.split('?')[0]), respond: () => TRAIL_DETAIL },
  { matches: (r) => has(r.url, '/trails'), respond: () => TRAILS },
  { matches: (r) => has(r.url, '/patterns') && r.method === 'PATCH', respond: () => ({ ...PATTERNS.items[0], sequence: PATTERNS.items[0].sequence.map((s, i) => (i === 1 ? { ...s, optional: true } : s)) }) },
  { matches: (r) => has(r.url, '/approve'), respond: (r) => ({ ...PATTERNS.items[0], lifecycle: (r.body as { decision?: string })?.decision === 'reject' ? 'rejected' : 'approved' }) },
  { matches: (r) => has(r.url, '/patterns'), respond: (r) => filterPatterns(r) },
  { matches: (r) => has(r.url, '/model-params') || (has(r.url, '/modelParams') && r.method !== 'PUT'), respond: () => MODEL_PARAMS },
  { matches: (r) => has(r.url, '/modelParams') && r.method === 'PUT', respond: (r) => ({ ...MODEL_PARAMS, version: 'v4', payload: (r.body as { payload?: ModelParamsRecord['payload'] }).payload ?? MODEL_PARAMS.payload }) },
  { matches: (r) => /\/incidents\/[^/?]+$/.test(r.url.split('?')[0]), respond: (r) => incidentById(r) },
  { matches: (r) => has(r.url, '/incidents'), respond: () => INCIDENT_PAGE },
  { matches: (r) => has(r.url, '/stats'), respond: () => STATS },
  { matches: (r) => /\/alarms\/[^/?]+$/.test(r.url.split('?')[0]), respond: (r) => alarmById(r) },
  { matches: (r) => has(r.url, '/alarms'), respond: (r) => filterAlarms(r) },
  { matches: (r) => has(r.url, '/run-stats'), respond: (r) => filterRunStats(r) },
  { matches: (r) => has(r.url, '/observed-chatter'), respond: () => OBSERVED_CHATTER },
  { matches: (r) => has(r.url, '/chatter'), respond: (r) => enrichmentChatter(r) },
  { matches: (r) => has(r.url, '/labels'), respond: () => LABELS },
];

function paramOf(req: HttpRequest<unknown>, key: string): string | null {
  return req.params.get(key);
}

/** Parse the siteId from /topology/sites/{siteId}/objects (mirrors incidentById's id parse). */
function siteIdFromObjectsUrl(req: HttpRequest<unknown>): string {
  const m = req.url.split('?')[0].match(/\/topology\/sites\/([^/]+)\/objects/);
  return m ? decodeURIComponent(m[1]) : 'Site:LON';
}

/** Parse the node id from /topology/nodes/{id}/neighbors. */
function neighborsNodeId(req: HttpRequest<unknown>): string {
  const m = req.url.split('?')[0].match(/\/topology\/nodes\/([^/]+)\/neighbors/);
  return m ? decodeURIComponent(m[1]) : '';
}

/** TraversalDto for /topology/traversal — a small bounded reach from `start` via its neighbours. */
function traversalFor(req: HttpRequest<unknown>): TraversalDto {
  const start = paramOf(req, 'start') ?? 'Router:lon-r1';
  const relation = paramOf(req, 'relation') ?? 'ADJACENCY_OVER';
  const maxDepth = Number(paramOf(req, 'maxDepth') ?? '1');
  const crossDomain = paramOf(req, 'crossDomain') === 'true';
  const n = neighborsFor(start);
  return {
    start,
    domain: 'core-ip',
    relations: [relation],
    maxDepth,
    crossDomain,
    reached: n.neighbors.map((x) => x.node),
    edges: n.neighbors.map((x) => x.via),
  };
}

function filterPatterns(req: HttpRequest<unknown>): PatternPage {
  const lifecycle = paramOf(req, 'lifecycle');
  if (!lifecycle) {
    return PATTERNS;
  }
  const items = PATTERNS.items.filter((p) => p.lifecycle === lifecycle);
  return { ...PATTERNS, items, total: items.length };
}

function incidentById(req: HttpRequest<unknown>): IncidentVM {
  const id = decodeURIComponent(req.url.split('?')[0].split('/').pop() ?? '');
  return INCIDENTS.find((i) => i.incidentId === id) ?? INCIDENTS[0];
}

function alarmById(req: HttpRequest<unknown>): AlarmDetail {
  const id = decodeURIComponent(req.url.split('?')[0].split('/').pop() ?? '');
  return ALARM_DETAILS[id] ?? { ...ALARMS.items[0], transitions: [] };
}

function filterAlarms(req: HttpRequest<unknown>): AlarmPage {
  const state = paramOf(req, 'state');
  if (!state) {
    return ALARMS;
  }
  const items = ALARMS.items.filter((a) => a.lifecycleState === state);
  return { ...ALARMS, items, total: items.length };
}

function filterRunStats(req: HttpRequest<unknown>): RunStatsPage {
  const trailId = paramOf(req, 'trailId');
  if (!trailId) {
    return RUN_STATS;
  }
  const items = RUN_STATS.items.filter((r) => r.trailId === trailId);
  return { ...RUN_STATS, items, total: items.length };
}

function enrichmentChatter(req: HttpRequest<unknown>): EnrichmentChatterList {
  const m = req.url.match(/\/sources\/([^/]+)\/chatter/);
  const source = m ? decodeURIComponent(m[1]) : 'default';
  return ENRICHMENT_CHATTER[source] ?? { source, chatterList: [] };
}
