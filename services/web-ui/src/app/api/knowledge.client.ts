import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { ModelParamsRecord } from './models';

/**
 * Knowledge Service model-params read/edit (frozen Knowledge OpenAPI, P2). The frozen path is
 * the generic versioned-record API `GET|PUT /domains/{domain}/{recordType}/{recordId}` with
 * `recordType = modelParams` and the versioned payload `{ paramSet, params[] }` (dotted keys).
 */
@Injectable({ providedIn: 'root' })
export class KnowledgeClient extends HttpBaseClient {
  protected readonly serviceName = 'Knowledge Service';
  protected readonly serviceKey: ServiceKey = 'knowledge';

  private readonly recordType = 'modelParams';

  getModelParams(recordId: string, domain = this.config.domain): Observable<ModelParamsRecord> {
    return this.get<ModelParamsRecord>(
      `/domains/${encodeURIComponent(domain)}/${this.recordType}/${encodeURIComponent(recordId)}`,
    );
  }

  updateModelParams(
    recordId: string,
    payload: ModelParamsRecord['payload'],
    author: string,
    domain = this.config.domain,
  ): Observable<ModelParamsRecord> {
    return this.put<ModelParamsRecord>(
      `/domains/${encodeURIComponent(domain)}/${this.recordType}/${encodeURIComponent(recordId)}`,
      { payload, author },
    );
  }
}
