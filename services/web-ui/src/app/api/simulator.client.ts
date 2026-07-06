import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { SynthRunRequest, SynthRunResponse, SynthStatusResponse } from './models';

/**
 * Simulator synth-run trigger API (frozen Simulator OpenAPI: POST /synth/run, GET /synth/status).
 * Lets the operator kick off a synthetic alarm ingestion run from the dashboard and observe its
 * live progress. Base URL resolves via ApiConfigService `simulator` key (default `/api/simulator`,
 * nginx-proxied to `simulator:8080` in-compose) — never a hard-coded host (spec AC 50/51).
 *
 * NOTE on the 409: a run-already-active response is NOT a hard failure for the caller — it is a
 * legitimate "already running" outcome. `startRun` therefore lets the raw HttpErrorResponse
 * propagate so the component can branch on `err.status === 409` (and read the conflict body's
 * runId) rather than treating it as an error banner.
 */
@Injectable({ providedIn: 'root' })
export class SimulatorClient extends HttpBaseClient {
  protected readonly serviceName = 'Simulator';
  protected readonly serviceKey: ServiceKey = 'simulator';

  // 409 (a run is already active) is a friendly, expected outcome the component branches on — not
  // a global error banner. 404 stays silent as everywhere else.
  protected override readonly silentStatuses: ReadonlySet<number> = new Set([404, 409]);

  /** POST /synth/run — start a synthetic ingestion run. Send `{}` for the env-default run. */
  startRun(req: SynthRunRequest = {}): Observable<SynthRunResponse> {
    return this.post<SynthRunResponse>('/synth/run', req);
  }

  /** GET /synth/status — current run state, live progress counters, and terminal summary. */
  getStatus(): Observable<SynthStatusResponse> {
    return this.get<SynthStatusResponse>('/synth/status');
  }
}
