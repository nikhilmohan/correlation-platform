import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { ModelParamsRecord } from './models';

/**
 * Knowledge Service model-params read/edit (frozen Knowledge OpenAPI, P2). The frozen path is
 * the generic versioned-record API `GET|PUT /domains/{domain}/{recordType}/{recordId}`.
 *
 * Two Knowledge-specific quirks the real service enforces (and which we match exactly):
 *  - the URL recordType path SEGMENT is kebab-case `model-params`;
 *  - the `{recordId}` is the FULL, percent-encoded record id `{domain}/modelParams/{paramSet}`
 *    (its middle segment is camelCase `modelParams` — yes, it differs from the kebab URL
 *    segment). Callers pass the short `paramSet` (e.g. `noise-filter`); this client composes
 *    the full id. The versioned payload is `{ paramSet, params[] }` (dotted keys).
 */
@Injectable({ providedIn: 'root' })
export class KnowledgeClient extends HttpBaseClient {
  protected readonly serviceName = 'Knowledge Service';
  protected readonly serviceKey: ServiceKey = 'knowledge';

  /** Kebab URL path segment for the record type (frozen Knowledge REST path). */
  private readonly recordTypeSegment = 'model-params';

  /** Compose + encode the frozen full record id `{domain}/modelParams/{paramSet}`. */
  private recordPath(paramSet: string, domain: string): string {
    const recordId = `${domain}/modelParams/${paramSet}`;
    return `/domains/${encodeURIComponent(domain)}/${this.recordTypeSegment}/${encodeURIComponent(recordId)}`;
  }

  getModelParams(paramSet: string, domain = this.config.domain): Observable<ModelParamsRecord> {
    return this.get<ModelParamsRecord>(this.recordPath(paramSet, domain));
  }

  updateModelParams(
    paramSet: string,
    payload: ModelParamsRecord['payload'],
    author: string,
    domain = this.config.domain,
  ): Observable<ModelParamsRecord> {
    return this.put<ModelParamsRecord>(this.recordPath(paramSet, domain), { payload, author });
  }
}
