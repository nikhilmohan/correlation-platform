import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { IncidentPage, IncidentVM, ResetResult, StatsVM } from './models';

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
   * ADMIN reset: clear persisted incidents + incident-alarm links and reset the in-memory
   * correlation session so the dashboard KPIs (auto-correlation, alarm-reduction, RCA accuracy, live
   * incidents) return to 0 / N/A. Idempotent server-side (a second call returns all-zeros, 200).
   * Empty POST body.
   */
  resetCorrelation(): Observable<ResetResult> {
    return this.post<ResetResult>('/admin/reset-correlation', {});
  }
}
