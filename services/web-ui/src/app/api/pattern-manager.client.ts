import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { PatternDecision, PatternEdit, PatternLifecycle, PatternPage, PatternView } from './models';

/**
 * Pattern Manager read + approval-intent + pattern-edit (frozen Pattern Manager OpenAPI, P2/P3).
 * NOTE: pattern-manager is a P2/P3 backend not yet built; consumers handle the unavailable
 * backend with loading/empty/error states (see PatternStore).
 */
@Injectable({ providedIn: 'root' })
export class PatternManagerClient extends HttpBaseClient {
  protected readonly serviceName = 'Pattern Manager';
  protected readonly serviceKey: ServiceKey = 'patternManager';

  listPatterns(opts: { lifecycle?: PatternLifecycle; limit?: number; offset?: number } = {}): Observable<PatternPage> {
    return this.get<PatternPage>('/patterns', {
      lifecycle: opts.lifecycle,
      limit: opts.limit ?? 50,
      offset: opts.offset ?? 0,
    });
  }

  getPattern(patternId: string): Observable<PatternView> {
    return this.get<PatternView>(`/patterns/${encodeURIComponent(patternId)}`);
  }

  decide(patternId: string, decision: PatternDecision): Observable<PatternView> {
    return this.post<PatternView>(`/patterns/${encodeURIComponent(patternId)}/approve`, decision);
  }

  edit(patternId: string, edit: PatternEdit): Observable<PatternView> {
    return this.patch<PatternView>(`/patterns/${encodeURIComponent(patternId)}`, edit);
  }
}
