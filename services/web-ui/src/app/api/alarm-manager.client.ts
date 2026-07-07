import { Injectable } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { AlarmDetail, AlarmPage, AlarmSummary, LifecycleState } from './models';

/**
 * Alarm Manager alarm-lifecycle query API (frozen AM OpenAPI, P3 + streaming + incident-detail).
 * P3 backend not yet built; consumers degrade gracefully.
 */
@Injectable({ providedIn: 'root' })
export class AlarmManagerClient extends HttpBaseClient {
  protected readonly serviceName = 'Alarm Manager';
  protected readonly serviceKey: ServiceKey = 'alarmManager';

  listAlarms(
    opts: { state?: LifecycleState; trailId?: string; incidentId?: string; limit?: number; offset?: number } = {},
  ): Observable<AlarmPage> {
    return this.get<AlarmPage>('/alarms', {
      state: opts.state,
      trailId: opts.trailId,
      incidentId: opts.incidentId,
      limit: opts.limit ?? 50,
      offset: opts.offset ?? 0,
    });
  }

  getAlarm(alarmId: string): Observable<AlarmDetail> {
    return this.get<AlarmDetail>(`/alarms/${encodeURIComponent(alarmId)}`);
  }

  /**
   * Resolve many alarms by id concurrently (fan-out of `GET /alarms/{id}`). Used by the
   * incident-first Alarms view to hydrate each incident's root-cause + child alarms (the
   * correlated rows never appear in the flat `/alarms` window). Resilient: an id that 404s (or any
   * per-id error) is skipped rather than failing the whole batch, so a group still renders minus a
   * missing member. De-duplicates the id list, preserves the resolved order by input id, and
   * resolves to `[]` for an empty id list (no HTTP).
   */
  getAlarms(ids: readonly string[]): Observable<AlarmSummary[]> {
    const unique = [...new Set(ids)].filter((id) => id.length > 0);
    if (unique.length === 0) {
      return of([]);
    }
    return forkJoin(
      unique.map((id) =>
        this.getAlarm(id).pipe(catchError(() => of(null))),
      ),
    ).pipe(map((results) => results.filter((a): a is AlarmDetail => a !== null)));
  }
}
