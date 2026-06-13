import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { GroundTruthLabel } from './models';

/** Simulator labels — RCA-accuracy ground-truth oracle (frozen Simulator OpenAPI, demo/eval only). */
@Injectable({ providedIn: 'root' })
export class SimulatorLabelsClient extends HttpBaseClient {
  protected readonly serviceName = 'Simulator';
  protected readonly serviceKey: ServiceKey = 'simulatorLabels';

  listLabels(scenarioId?: string): Observable<GroundTruthLabel[]> {
    return this.get<GroundTruthLabel[]>('/labels', { scenarioId });
  }
}
