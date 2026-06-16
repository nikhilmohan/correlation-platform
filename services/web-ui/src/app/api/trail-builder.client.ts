import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { ListTrailsResponse, TrailDetail, TrailsForObjectResponse } from './models';

/** Trail Builder trail-viz API (frozen Trail Builder OpenAPI, P1). */
@Injectable({ providedIn: 'root' })
export class TrailBuilderClient extends HttpBaseClient {
  protected readonly serviceName = 'Trail Builder';
  protected readonly serviceKey: ServiceKey = 'trailBuilder';

  listTrails(limit = 200, offset = 0): Observable<ListTrailsResponse> {
    return this.get<ListTrailsResponse>('/trails', {
      snapshotId: this.config.snapshotId,
      domain: this.config.domain,
      limit,
      offset,
    });
  }

  getTrail(trailId: string): Observable<TrailDetail> {
    return this.get<TrailDetail>(`/trails/${encodeURIComponent(trailId)}`);
  }

  getTrailsForObject(managedObjectId: string): Observable<TrailsForObjectResponse> {
    return this.get<TrailsForObjectResponse>('/trails/by-object', {
      managedObjectId,
      domain: this.config.domain,
    });
  }
}
