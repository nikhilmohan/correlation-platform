import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { AlarmDetail, AlarmPage, LifecycleState } from './models';

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
}
