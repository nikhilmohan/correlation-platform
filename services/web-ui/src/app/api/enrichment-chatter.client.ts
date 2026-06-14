import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HttpBaseClient } from './http-base';
import { ServiceKey } from '../core/api-config.service';
import { EnrichmentChatterEntry, EnrichmentChatterList } from './models';

/**
 * Enrichment chatter edit API (FIX F-UI1 — promotion target).
 *
 * FLAGGED DEPENDENCY: at build time the Enrichment chatter REST API (`design/enrichment-chatter-api`)
 * was NOT yet published. This client is built against the EXPECTED shape pinned to Enrichment's
 * existing per-source `chatterList` entry `{ managedObjectId, eventType }` (the same key Noise
 * Filter emits). No web-ui-side contract is invented; when Enrichment's chatter `openapi.json`
 * lands, this client is regenerated against it (contract change = architecture.md + human approval).
 */
@Injectable({ providedIn: 'root' })
export class EnrichmentChatterClient extends HttpBaseClient {
  protected readonly serviceName = 'Enrichment';
  protected readonly serviceKey: ServiceKey = 'enrichmentChatter';

  listChatter(source: string): Observable<EnrichmentChatterList> {
    return this.get<EnrichmentChatterList>(`/api/v1/sources/${encodeURIComponent(source)}/chatter`);
  }

  addChatter(source: string, entry: EnrichmentChatterEntry): Observable<EnrichmentChatterList> {
    return this.post<EnrichmentChatterList>(`/api/v1/sources/${encodeURIComponent(source)}/chatter`, entry);
  }

  removeChatter(source: string, entry: EnrichmentChatterEntry): Observable<EnrichmentChatterList> {
    return this.delete<EnrichmentChatterList>(`/api/v1/sources/${encodeURIComponent(source)}/chatter`, entry);
  }
}
