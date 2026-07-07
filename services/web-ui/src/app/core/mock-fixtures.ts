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
  PatternLifecycle,
  PatternPage,
  PatternView,
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

/**
 * Sentinel a handler returns to make the mock interceptor emit a 404 (rather than a 200 body).
 * Mirrors the real backend so by-id resilience (an incident referencing a missing alarm id) is
 * exercised in unit/component tests.
 */
export const MOCK_NOT_FOUND = Symbol('mock-not-found');

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

// Third site (Madrid) — a DISTINCT subgraph (different device ids, counts and topology from LON/FRA,
// no London-clone). Carries the objectTypes NOT present at LON/FRA so the union across sites covers
// ALL TEN Core IP objectTypes (Node, LineCard, Port, Interface, FiberSpan, IPLink, IGPAdjacency,
// LSP, VPNService, SRLG) PLUS one UNKNOWN type (`UnknownFutureThing`) for the generic-icon fallback
// (AC 70 / AC 71). Madrid contributes: Node, Port, IPLink, IGPAdjacency, VPNService + the unknown.
const MAD_OBJECTS: SiteObjectsDto = {
  siteId: 'Site:MAD',
  domain: 'core-ip',
  snapshotId: 'current',
  nodeCount: 7,
  edgeCount: 4,
  nodes: [
    { managedObjectId: 'Node:mad-n1', objectType: 'Node', domain: 'core-ip', snapshotId: 'current', name: 'mad-n1', attributes: { vendor: 'Acme', model: 'R9000', equipmentType: 'router' } },
    { managedObjectId: 'Port:mad-n1-p1', objectType: 'Port', domain: 'core-ip', snapshotId: 'current', name: 'p1', attributes: { speed: '100G' } },
    { managedObjectId: 'IPLink:mad-bcn-1', objectType: 'IPLink', domain: 'core-ip', snapshotId: 'current', name: 'MAD-BCN link', attributes: { capacity: '100G' } },
    { managedObjectId: 'IGPAdjacency:mad-adj-1', objectType: 'IGPAdjacency', domain: 'core-ip', snapshotId: 'current', name: 'adj-1', attributes: { igpArea: '0.0.0.1' } },
    { managedObjectId: 'VPNService:mad-vpn-1', objectType: 'VPNService', domain: 'core-ip', snapshotId: 'current', name: 'vpn-1', attributes: { customer: 'acme-corp' } },
    { managedObjectId: 'SRLG:srlg-9', objectType: 'SRLG', domain: 'core-ip', snapshotId: 'current', name: 'SRLG-9', attributes: { riskGroup: 'mad-conduit' } },
    // An UNKNOWN/future objectType — must still render with the generic fallback icon (AC 71).
    { managedObjectId: 'UnknownFutureThing:mad-x1', objectType: 'UnknownFutureThing', domain: 'core-ip', snapshotId: 'current', name: 'future-x1', attributes: {} },
  ],
  edges: [
    { edgeId: 'me-1', from: 'Node:mad-n1', to: 'Port:mad-n1-p1', relation: 'HAS_PORT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    { edgeId: 'me-2', from: 'Port:mad-n1-p1', to: 'IPLink:mad-bcn-1', relation: 'CONNECTS', domain: 'core-ip', snapshotId: 'current', attributes: { linkType: 'ip', capacity: '100G' } },
    { edgeId: 'me-3', from: 'Node:mad-n1', to: 'IGPAdjacency:mad-adj-1', relation: 'ADJACENCY_OVER', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    { edgeId: 'me-4', from: 'Node:mad-n1', to: 'VPNService:mad-vpn-1', relation: 'SERVES', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    ...['Node:mad-n1', 'Port:mad-n1-p1', 'IPLink:mad-bcn-1', 'IGPAdjacency:mad-adj-1', 'VPNService:mad-vpn-1', 'SRLG:srlg-9', 'UnknownFutureThing:mad-x1'].map(
      (id, i) => ({
        edgeId: `loc-mad-${i}`,
        from: id,
        to: 'Site:MAD',
        relation: 'LOCATED_AT',
        domain: 'core-ip',
        snapshotId: 'current',
        attributes: {},
      }),
    ),
  ],
};

const SITE_OBJECTS_BY_ID: Record<string, SiteObjectsDto> = {
  'Site:LON': SITE_OBJECTS,
  'Site:FRA': FRA_OBJECTS,
  'Site:MAD': MAD_OBJECTS,
};

/**
 * A single-site fixture whose nodes include ONE OF EACH of the ten Core IP objectTypes PLUS one
 * unknown type — drives the AC 70 (icon per type, all ten distinct keys) and AC 71 (generic
 * fallback) unit tests directly without depending on a cross-site union. Exported so the type-icon
 * spec can root the store at it.
 */
export const ALL_OBJECT_TYPES_SITE: SiteObjectsDto = {
  siteId: 'Site:ALL',
  domain: 'core-ip',
  snapshotId: 'current',
  nodeCount: 11,
  edgeCount: 11,
  nodes: [
    { managedObjectId: 'Node:all-1', objectType: 'Node', domain: 'core-ip', snapshotId: 'current', name: 'node', attributes: {} },
    { managedObjectId: 'LineCard:all-1', objectType: 'LineCard', domain: 'core-ip', snapshotId: 'current', name: 'lc', attributes: {} },
    { managedObjectId: 'Port:all-1', objectType: 'Port', domain: 'core-ip', snapshotId: 'current', name: 'port', attributes: {} },
    { managedObjectId: 'Interface:all-1', objectType: 'Interface', domain: 'core-ip', snapshotId: 'current', name: 'if', attributes: {} },
    { managedObjectId: 'FiberSpan:all-1', objectType: 'FiberSpan', domain: 'core-ip', snapshotId: 'current', name: 'fiber', attributes: {} },
    { managedObjectId: 'IPLink:all-1', objectType: 'IPLink', domain: 'core-ip', snapshotId: 'current', name: 'iplink', attributes: {} },
    { managedObjectId: 'IGPAdjacency:all-1', objectType: 'IGPAdjacency', domain: 'core-ip', snapshotId: 'current', name: 'adj', attributes: {} },
    { managedObjectId: 'LSP:all-1', objectType: 'LSP', domain: 'core-ip', snapshotId: 'current', name: 'lsp', attributes: {} },
    { managedObjectId: 'VPNService:all-1', objectType: 'VPNService', domain: 'core-ip', snapshotId: 'current', name: 'vpn', attributes: {} },
    { managedObjectId: 'SRLG:all-1', objectType: 'SRLG', domain: 'core-ip', snapshotId: 'current', name: 'srlg', attributes: {} },
    { managedObjectId: 'UnknownFutureThing:all-1', objectType: 'UnknownFutureThing', domain: 'core-ip', snapshotId: 'current', name: 'future', attributes: {} },
  ],
  edges: [
    ...['Node:all-1', 'LineCard:all-1', 'Port:all-1', 'Interface:all-1', 'FiberSpan:all-1', 'IPLink:all-1', 'IGPAdjacency:all-1', 'LSP:all-1', 'VPNService:all-1', 'SRLG:all-1', 'UnknownFutureThing:all-1'].map(
      (id, i) => ({
        edgeId: `loc-all-${i}`,
        from: id,
        to: 'Site:ALL',
        relation: 'LOCATED_AT',
        domain: 'core-ip',
        snapshotId: 'current',
        attributes: {},
      }),
    ),
  ],
};

/** Distinct device managedObjectIds per known site — used by the distinct-fixtures unit test to
 *  assert LON ≠ FRA ≠ MAD (no clones). */
export const SITE_DEVICE_IDS: Record<string, string[]> = {
  'Site:LON': SITE_OBJECTS.nodes.map((n) => n.managedObjectId),
  'Site:FRA': FRA_OBJECTS.nodes.map((n) => n.managedObjectId),
  'Site:MAD': MAD_OBJECTS.nodes.map((n) => n.managedObjectId),
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

/**
 * Objects-at-site for a siteId. Each known site (LON/FRA/MAD) returns its OWN distinct subgraph (no
 * London-clone fallback — the previous `{...SITE_OBJECTS, siteId}` made every other site look
 * identical). An UNKNOWN siteId gets a small SYNTHESIZED graph keyed off the siteId so it is still
 * distinct (a single Node + Interface + its LOCATED_AT placement), never a clone.
 */
function objectsForSite(siteId: string): SiteObjectsDto {
  if (siteId === 'Site:ALL') {
    return ALL_OBJECT_TYPES_SITE;
  }
  const known = SITE_OBJECTS_BY_ID[siteId];
  if (known) {
    return known;
  }
  const slug = siteId.replace(/[^a-zA-Z0-9]/g, '-').toLowerCase();
  return {
    siteId,
    domain: 'core-ip',
    snapshotId: 'current',
    nodeCount: 2,
    edgeCount: 1,
    nodes: [
      { managedObjectId: `Node:${slug}-n1`, objectType: 'Node', domain: 'core-ip', snapshotId: 'current', name: `${slug}-n1`, attributes: {} },
      { managedObjectId: `Interface:${slug}-n1-e1`, objectType: 'Interface', domain: 'core-ip', snapshotId: 'current', name: 'e1', attributes: {} },
    ],
    edges: [
      { edgeId: `${slug}-e-1`, from: `Node:${slug}-n1`, to: `Interface:${slug}-n1-e1`, relation: 'HAS_PORT', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      ...[`Node:${slug}-n1`, `Interface:${slug}-n1-e1`].map((id, i) => ({
        edgeId: `loc-${slug}-${i}`,
        from: id,
        to: siteId,
        relation: 'LOCATED_AT',
        domain: 'core-ip',
        snapshotId: 'current',
        attributes: {},
      })),
    ],
  };
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
      patternName: 'Loss of Signal Cascade · 02007ff1',
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
      timing: { timeframeMs: 9700, medianInterArrivalMs: 1200, maxInterArrivalMs: 1500, stddevInterArrivalMs: 310 },
      sessionWindow: { windowMs: 30000, type: 'session-gap' },
      codebookMatchId: 'CB-2',
      structurallyValidated: true,
      structuralValidationReason: null,
      instanceCount: 18,
      occurrenceCount: 12,
      trailCount: 11,
      firstSeen: '2026-05-01T09:14:02Z',
      lastSeen: '2026-05-10T18:02:41Z',
      supportingInstances: [
        { sourceWindowId: 'win-3f2a', snapshotId: 'current', occurrence: { anchorScenarioId: 'Port:N0-LC1-P1' } },
        { sourceWindowId: 'win-9c17', snapshotId: 'current', occurrence: { anchorScenarioId: 'Port:N0-LC1-P1' } },
      ],
      sampleAlarms: [
        { alarmId: 'ALM-1001', alarmType: 'LOS', raisedAt: '2026-05-01T09:14:02Z', managedObjectId: 'Port:N0-LC1-P1', perceivedSeverity: 'critical' },
        { alarmId: 'ALM-1002', alarmType: 'LinkDown', raisedAt: '2026-05-01T09:14:03Z', managedObjectId: 'IPLink:N0_N1', perceivedSeverity: 'major' },
        { alarmId: 'ALM-1003', alarmType: 'AdjDown', raisedAt: '2026-05-01T09:14:05Z', managedObjectId: 'IGPAdj:N0_N1', perceivedSeverity: 'minor' },
        { alarmId: 'ALM-1004', alarmType: 'LinkDown', raisedAt: '2026-05-01T09:14:06Z', managedObjectId: 'IPLink:N0_N2', perceivedSeverity: 'major' },
        { alarmId: 'ALM-1005', alarmType: 'AdjDown', raisedAt: '2026-05-01T09:14:08Z', managedObjectId: 'IGPAdj:N0_N2', perceivedSeverity: 'warning' },
        { alarmId: 'ALM-1006', alarmType: 'LinkDown', raisedAt: '2026-05-01T09:14:11Z', managedObjectId: 'IPLink:N1_N2', perceivedSeverity: 'cleared' },
      ],
      lifecycle: 'draft',
      domain: 'core-ip',
      createdAt: '2026-05-01T00:00:00Z',
    },
    {
      patternId: 'PAT-1',
      patternName: 'Port Flap Cascade · 10b3918b',
      trailId: 'TR-8',
      sequence: [
        { alarmType: 'PortFlap', optional: false },
        { alarmType: 'LinkDown', optional: false },
      ],
      rootCauseAlarmType: 'PortFlap',
      support: 0.2,
      confidence: 0.85,
      lift: 3.1,
      timing: { timeframeMs: 6400, medianInterArrivalMs: 800, maxInterArrivalMs: 900 },
      sessionWindow: { windowMs: 30000, type: 'session-gap' },
      codebookMatchId: null,
      structurallyValidated: false,
      structuralValidationReason: 'no codebook overlap',
      instanceCount: 30,
      occurrenceCount: 1,
      trailCount: 1,
      firstSeen: '2026-04-20T02:11:00Z',
      lastSeen: '2026-04-20T02:11:00Z',
      supportingInstances: [
        { sourceWindowId: 'win-a44b', snapshotId: 'current', occurrence: {} },
      ],
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

// Deliberately stored OUT OF createdAt order (older INC-11 first) so the store's
// createdAt-descending sort (sortedIncidents) is actually exercised — a test that asserts
// most-recent-first would FAIL if the list were rendered unsorted.
const INCIDENTS: IncidentVM[] = [
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
];

const INCIDENT_PAGE: IncidentPage = { items: INCIDENTS, total: 2, limit: 50, offset: 0 };

// --- Alarm Manager fixtures (P3) ---
// The flat `GET /alarms` window mirrors the REAL backend: it returns only the freshest,
// still-UNCORRELATED tail (correlated alarms are older and fall outside the window). The Alarms
// view is INCIDENT-FIRST — it resolves each incident's correlated alarms by id (`GET /alarms/{id}`,
// served from ALARM_BY_ID below), never from this flat list.
const ALARMS: AlarmPage = {
  total: 3,
  limit: 50,
  offset: 0,
  items: [
    // Uncorrelated alarms (role='none'). Their raisedAt values are deliberately OUT OF array order
    // (a-2 oldest, a-1 middle, a-9 newest) so the store's raisedAt-descending sort is exercised.
    { alarmId: 'a-2', managedObjectId: 'Router:lon-r1', eventType: 'CpuHigh', alarmType: 'CpuHigh', perceivedSeverity: 'warning', raisedAt: '2026-06-01T11:45:00Z', lifecycleState: 'in-progress', role: 'none', incidentId: null, trailIds: [] },
    { alarmId: 'a-1', managedObjectId: 'Router:lon-r1', eventType: 'PortFlap', alarmType: 'PortFlap', perceivedSeverity: 'minor', raisedAt: '2026-06-01T11:55:00Z', lifecycleState: 'open', role: 'none', incidentId: null, trailIds: [] },
    { alarmId: 'a-9', managedObjectId: 'Router:lon-r1', eventType: 'AdjDown', alarmType: 'AdjDown', perceivedSeverity: 'cleared', raisedAt: '2026-06-01T12:10:00Z', lifecycleState: 'cleared', role: 'none', incidentId: null, trailIds: [] },
  ],
};

/**
 * By-id alarm resolution for `GET /alarms/{id}` — the incident-first view hydrates each incident's
 * RCA + child alarms from here. Covers INC-12 (a-3 RCA + a-7, a-8 children). INC-11's alarm ids
 * (a-20, a-21) are DELIBERATELY absent so a by-id lookup 404s — exercising the "a 404 child still
 * renders the group" resilience path (INC-11 resolves to zero alarms → no group, load still ok).
 */
const ALARM_BY_ID: Record<string, AlarmDetail> = {
  'a-3': { alarmId: 'a-3', managedObjectId: 'FiberSpan:lon-fra-1', eventType: 'LOS', alarmType: 'LOS', perceivedSeverity: 'critical', raisedAt: '2026-06-01T12:00:00Z', lifecycleState: 'correlated', role: 'root-cause', incidentId: 'INC-12', trailIds: ['TR-7'], transitions: [{ toState: 'open', occurredAt: '2026-06-01T12:00:00Z' }, { toState: 'correlated', occurredAt: '2026-06-01T12:00:05Z' }] },
  'a-7': { alarmId: 'a-7', managedObjectId: 'Interface:lon-r1-e1', eventType: 'LinkDown', alarmType: 'LinkDown', perceivedSeverity: 'major', raisedAt: '2026-06-01T12:00:03Z', lifecycleState: 'correlated', role: 'child', incidentId: 'INC-12', trailIds: ['TR-7'], transitions: [{ toState: 'open', occurredAt: '2026-06-01T12:00:01Z' }, { toState: 'correlated', occurredAt: '2026-06-01T12:00:06Z' }] },
  'a-8': { alarmId: 'a-8', managedObjectId: 'IGPAdj:lon-r1-r2', eventType: 'AdjDown', alarmType: 'AdjDown', perceivedSeverity: 'minor', raisedAt: '2026-06-01T12:00:05Z', lifecycleState: 'correlated', role: 'child', incidentId: 'INC-12', trailIds: ['TR-7'], transitions: [{ toState: 'open', occurredAt: '2026-06-01T12:00:02Z' }, { toState: 'correlated', occurredAt: '2026-06-01T12:00:07Z' }] },
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
  { matches: (r) => has(r.url, '/approve'), respond: (r) => approvePattern(r) },
  { matches: (r) => has(r.url, '/patterns'), respond: (r) => filterPatterns(r) },
  { matches: (r) => (has(r.url, '/model-params') || has(r.url, '/modelParams')) && r.method !== 'PUT', respond: () => MODEL_PARAMS },
  { matches: (r) => (has(r.url, '/model-params') || has(r.url, '/modelParams')) && r.method === 'PUT', respond: (r) => ({ ...MODEL_PARAMS, version: 'v4', payload: (r.body as { payload?: ModelParamsRecord['payload'] }).payload ?? MODEL_PARAMS.payload }) },
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

/**
 * In-session pattern-decision state so the in-app mock mirrors the REAL Pattern Manager's
 * persistence: an approved (or rejected) draft reads back with its new lifecycle on a subsequent
 * GET /patterns, which the data-agnostic AC 39 round-trip relies on. Keyed by patternId.
 */
const patternDecisions = new Map<string, PatternLifecycle>();

/**
 * Clear the in-session pattern-decision state. Unit tests that share this module MUST call this
 * between cases so an approve/reject in one test does not leak into the next (the state is
 * intentionally persistent within a session to mirror the real Pattern Manager for the AC 39 E2E
 * round-trip).
 */
export function resetMockPatternDecisions(): void {
  patternDecisions.clear();
}

/** Effective lifecycle for a pattern, honouring any in-session decision. */
function effectiveLifecycle(p: PatternView): PatternLifecycle {
  return patternDecisions.get(p.patternId) ?? p.lifecycle;
}

/** Parse the patternId from /patterns/{id}/approve. */
function patternIdFromApproveUrl(url: string): string {
  const m = url.split('?')[0].match(/\/patterns\/([^/]+)\/approve/);
  return m ? decodeURIComponent(m[1]) : PATTERNS.items[0].patternId;
}

function approvePattern(req: HttpRequest<unknown>): PatternView {
  const id = patternIdFromApproveUrl(req.url);
  const decision = (req.body as { decision?: string })?.decision;
  const lifecycle: PatternLifecycle = decision === 'reject' ? 'rejected' : 'approved';
  patternDecisions.set(id, lifecycle);
  const base = PATTERNS.items.find((p) => p.patternId === id) ?? PATTERNS.items[0];
  return { ...base, patternId: id, lifecycle };
}

function filterPatterns(req: HttpRequest<unknown>): PatternPage {
  const lifecycle = paramOf(req, 'lifecycle');
  const all = PATTERNS.items.map((p) => ({ ...p, lifecycle: effectiveLifecycle(p) }));
  if (!lifecycle) {
    return { ...PATTERNS, items: all, total: all.length };
  }
  const items = all.filter((p) => p.lifecycle === lifecycle);
  return { ...PATTERNS, items, total: items.length };
}

function incidentById(req: HttpRequest<unknown>): IncidentVM {
  const id = decodeURIComponent(req.url.split('?')[0].split('/').pop() ?? '');
  return INCIDENTS.find((i) => i.incidentId === id) ?? INCIDENTS[0];
}

function alarmById(req: HttpRequest<unknown>): AlarmDetail | typeof MOCK_NOT_FOUND {
  const id = decodeURIComponent(req.url.split('?')[0].split('/').pop() ?? '');
  return ALARM_BY_ID[id] ?? MOCK_NOT_FOUND;
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
