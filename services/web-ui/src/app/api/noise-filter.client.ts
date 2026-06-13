import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { ObservedChatterPage, RunStatsPage } from './models';

/**
 * Noise Filter run-stats + observed-chatter read API (frozen Noise Filter OpenAPI, P2). P2
 * backend not yet built; consumers degrade gracefully.
 */
@Injectable({ providedIn: 'root' })
export class NoiseFilterClient extends HttpBaseClient {
  protected readonly serviceName = 'Noise Filter';
  protected readonly serviceKey: ServiceKey = 'noiseFilter';

  listRunStats(opts: { trailId?: string; from?: string; to?: string; limit?: number; offset?: number } = {}): Observable<RunStatsPage> {
    return this.get<RunStatsPage>('/api/v1/run-stats', {
      trailId: opts.trailId,
      from: opts.from,
      to: opts.to,
      limit: opts.limit ?? 50,
      offset: opts.offset ?? 0,
    });
  }

  listObservedChatter(opts: { alarmType?: string; trailId?: string; minOccurrence?: number; limit?: number; offset?: number } = {}): Observable<ObservedChatterPage> {
    return this.get<ObservedChatterPage>('/api/v1/observed-chatter', {
      alarmType: opts.alarmType,
      trailId: opts.trailId,
      minOccurrence: opts.minOccurrence,
      limit: opts.limit ?? 50,
      offset: opts.offset ?? 0,
    });
  }
}
