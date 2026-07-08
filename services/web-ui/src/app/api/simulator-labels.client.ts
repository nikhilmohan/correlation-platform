import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { GroundTruthLabel } from './models';

/**
 * Simulator labels — RCA-accuracy ground-truth oracle (frozen Simulator OpenAPI, demo/eval only).
 *
 * The labels are served BY THE SIMULATOR at `/labels`, so this client resolves against the SAME
 * base URL as the synth-run trigger (`simulator` key → default `/api/simulator`, nginx-proxied to
 * `simulator:8080`). `GET /labels` therefore reaches `/api/simulator/labels`. In compose the
 * dedicated `SIMULATOR_LABELS_API_BASE_URL` is intentionally left UNSET (only `SIMULATOR_API_BASE_URL`
 * is provided), so binding to the `simulator` key is what makes the oracle reachable in real mode. In
 * mock mode the interceptor matches on the `/labels` URL substring regardless of the base prefix.
 */
@Injectable({ providedIn: 'root' })
export class SimulatorLabelsClient extends HttpBaseClient {
  protected readonly serviceName = 'Simulator';
  protected readonly serviceKey: ServiceKey = 'simulator';

  // A missing oracle (no simulator/labels in prod) is a graceful "no ground truth" → N/A, not a
  // global error banner. 404 is silent by default; 502 (nginx up before simulator) is too.
  protected override readonly silentStatuses: ReadonlySet<number> = new Set([404, 502]);

  listLabels(scenarioId?: string): Observable<GroundTruthLabel[]> {
    return this.get<GroundTruthLabel[]>('/labels', { scenarioId });
  }
}
