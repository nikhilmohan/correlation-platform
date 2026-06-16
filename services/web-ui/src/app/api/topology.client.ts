import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { NeighborsDto, NodeDto, SiteListDto, SiteObjectsDto, TraversalDto } from './models';

/** Topology enforces maxDepth ≤ 32 on the traversal endpoint; clamp client-side too. */
const MAX_TRAVERSAL_DEPTH = 32;

/** Topology Service site query + objects-at-site (frozen Topology OpenAPI, P1). */
@Injectable({ providedIn: 'root' })
export class TopologyClient extends HttpBaseClient {
  protected readonly serviceName = 'Topology Service';
  protected readonly serviceKey: ServiceKey = 'topology';

  listSites(): Observable<SiteListDto> {
    return this.get<SiteListDto>('/topology/sites', {
      domain: this.config.domain,
      snapshotId: this.config.snapshotId,
    });
  }

  objectsAtSite(siteId: string): Observable<SiteObjectsDto> {
    return this.get<SiteObjectsDto>(`/topology/sites/${encodeURIComponent(siteId)}/objects`, {
      domain: this.config.domain,
      snapshotId: this.config.snapshotId,
    });
  }

  resolveNode(managedObjectId: string): Observable<NodeDto> {
    return this.get<NodeDto>(`/topology/nodes/${encodeURIComponent(managedObjectId)}`, {
      domain: this.config.domain,
      snapshotId: this.config.snapshotId,
    });
  }

  /** Immediate neighbours of a node (optionally filtered by relation / crossing the domain). The
   *  operator-driven EXPAND control pulls these into the accumulating graph. */
  neighbors(
    managedObjectId: string,
    opts: { relation?: string; crossDomain?: boolean } = {},
  ): Observable<NeighborsDto> {
    return this.get<NeighborsDto>(
      `/topology/nodes/${encodeURIComponent(managedObjectId)}/neighbors`,
      {
        domain: this.config.domain,
        snapshotId: this.config.snapshotId,
        // Pass optional params only when set (mirrors objectsAtSite — undefined is stripped by params()).
        relation: opts.relation,
        crossDomain: opts.crossDomain,
      },
    );
  }

  /** Bounded BFS traversal from a start node. `relation` and `maxDepth` are required by Topology;
   *  maxDepth is clamped to ≤ 32 (server limit). */
  traversal(opts: {
    start: string;
    relation?: string;
    maxDepth?: number;
    crossDomain?: boolean;
  }): Observable<TraversalDto> {
    const maxDepth = Math.min(opts.maxDepth ?? 1, MAX_TRAVERSAL_DEPTH);
    return this.get<TraversalDto>('/topology/traversal', {
      start: opts.start,
      relation: opts.relation,
      maxDepth,
      crossDomain: opts.crossDomain,
      domain: this.config.domain,
      snapshotId: this.config.snapshotId,
    });
  }
}
