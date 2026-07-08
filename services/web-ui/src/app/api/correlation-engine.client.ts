import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { IncidentPage, IncidentVM, StatsVM } from './models';

/**
 * Correlation Engine incident/stats API (frozen CE OpenAPI, P3 + dashboard + streaming +
 * incident-detail). P3 backend not yet built; consumers degrade gracefully.
 */
@Injectable({ providedIn: 'root' })
export class CorrelationEngineClient extends HttpBaseClient {
  protected readonly serviceName = 'Correlation Engine';
  protected readonly serviceKey: ServiceKey = 'correlationEngine';

  listIncidents(opts: { trailId?: string; limit?: number; offset?: number } = {}): Observable<IncidentPage> {
    return this.get<IncidentPage>('/incidents', {
      trailId: opts.trailId,
      limit: opts.limit ?? 50,
      offset: opts.offset ?? 0,
    });
  }

  getIncident(incidentId: string): Observable<IncidentVM> {
    return this.get<IncidentVM>(`/incidents/${encodeURIComponent(incidentId)}`);
  }

  getStats(): Observable<StatsVM> {
    return this.get<StatsVM>('/stats');
  }

  /**
   * POST /admin/reset-correlation — reset correlation state (drop live incidents/correlation).
   * Part of the operator Purge action (paired with the Alarm Manager live-alarm purge). Returns
   * void; the caller cares only about success/failure.
   */
  resetCorrelation(): Observable<void> {
    return this.post<void>('/admin/reset-correlation', {});
  }
}
