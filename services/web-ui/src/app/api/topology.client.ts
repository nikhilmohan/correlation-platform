import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { NodeDto, SiteListDto, SiteObjectsDto } from './models';

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
}
