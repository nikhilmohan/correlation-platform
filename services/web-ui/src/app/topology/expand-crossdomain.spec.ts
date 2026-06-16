import { TestBed } from '@angular/core/testing';
import { HttpEvent, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { Observable, of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { TopologyStore } from './topology.store';
import { NeighborsDto, SiteObjectsDto } from '../api/models';
import { flush } from '../../test-utils';

/**
 * AC 58 — the `crossDomain` opt-in. Expanding a device with the cross-domain opt-in ON must send
 * `GET /topology/nodes/{id}/neighbors?...&crossDomain=true`; with the opt-in OFF (default) the
 * `crossDomain` param must be ABSENT (or false). Driven through
 * `TopologyStore.expandNode(id,{crossDomain})` → `TopologyClient.neighbors(id,opts)`; the REAL HTTP
 * request that leaves the typed client is captured by a recording interceptor and its query param
 * asserted. Would FAIL if the client dropped the param, hard-coded it, or always sent it.
 */

const NEIGHBORS: NeighborsDto = {
  managedObjectId: 'seed-0',
  domain: 'core-ip',
  neighbors: [
    {
      node: { managedObjectId: 'nbr-1', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} },
      via: { edgeId: 'via-1', from: 'seed-0', to: 'nbr-1', relation: 'ADJACENCY_OVER', domain: 'core-ip', snapshotId: 'current', attributes: {} },
    },
  ],
};

const SEED: SiteObjectsDto = {
  siteId: 'Site:LON',
  domain: 'core-ip',
  snapshotId: 'current',
  nodeCount: 1,
  edgeCount: 0,
  nodes: [{ managedObjectId: 'seed-0', objectType: 'Router', domain: 'core-ip', snapshotId: 'current', attributes: {} }],
  edges: [],
};

/** Captures every outgoing request, then serves the canned shapes so the store still completes. */
function makeRecorder() {
  const requests: HttpRequest<unknown>[] = [];
  const interceptor = (req: HttpRequest<unknown>, _next: HttpHandlerFn): Observable<HttpEvent<unknown>> => {
    requests.push(req);
    const path = req.url.split('?')[0];
    if (path.endsWith('/objects')) {
      return of(new HttpResponse({ status: 200, body: SEED }));
    }
    if (path.endsWith('/neighbors')) {
      return of(new HttpResponse({ status: 200, body: NEIGHBORS }));
    }
    if (path.endsWith('/sites')) {
      return of(new HttpResponse({ status: 200, body: { domain: 'core-ip', snapshotId: 'current', count: 0, sites: [] } }));
    }
    if (path.includes('/trails')) {
      return of(new HttpResponse({ status: 200, body: { snapshotId: 'current', domain: 'core-ip', count: 0, trails: [] } }));
    }
    return of(new HttpResponse({ status: 200, body: {} }));
  };
  return { requests, interceptor };
}

function setup() {
  const rec = makeRecorder();
  TestBed.configureTestingModule({
    providers: [provideRouter([]), provideHttpClient(withInterceptors([rec.interceptor]))],
  });
  return { store: TestBed.inject(TopologyStore), requests: rec.requests };
}

function lastNeighborsReq(requests: HttpRequest<unknown>[]): HttpRequest<unknown> {
  const hits = requests.filter((r) => r.url.split('?')[0].endsWith('/neighbors'));
  expect(hits.length).toBeGreaterThanOrEqual(1);
  return hits[hits.length - 1];
}

describe('AC 58 — crossDomain opt-in on the neighbours expand', () => {
  it('opt-in ON → the captured neighbours request carries crossDomain=true', async () => {
    const { store, requests } = setup();
    store.selectSite('Site:LON');
    await flush();

    store.expandNode('seed-0', { crossDomain: true });
    await flush();

    const req = lastNeighborsReq(requests);
    expect(req.params.get('crossDomain')).toBe('true');
  });

  it('opt-in OFF (default) → the captured neighbours request omits crossDomain (param absent)', async () => {
    const { store, requests } = setup();
    store.selectSite('Site:LON');
    await flush();

    store.expandNode('seed-0'); // default opts — no crossDomain
    await flush();

    const req = lastNeighborsReq(requests);
    // Empty/undefined → stripped by HttpBaseClient.params(); the param must NOT be present.
    expect(req.params.has('crossDomain')).toBe(false);
    expect(req.params.get('crossDomain')).toBeNull();
  });

  it('opt-in explicitly FALSE → crossDomain is omitted-or-false (never true)', async () => {
    const { store, requests } = setup();
    store.selectSite('Site:LON');
    await flush();

    store.expandNode('seed-0', { crossDomain: false });
    await flush();

    const req = lastNeighborsReq(requests);
    // AC 58 allows "omitted (or false)": the param must never be `true` under the off/false state.
    expect(req.params.get('crossDomain')).not.toBe('true');
  });
});
