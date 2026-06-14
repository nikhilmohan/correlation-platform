import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of } from 'rxjs';
import { NoiseFilterClient } from '../api/noise-filter.client';
import { EnrichmentChatterClient } from '../api/enrichment-chatter.client';
import { EnrichmentChatterEntry, ObservedChatterSignature } from '../api/models';

export interface ChatterJoinRow {
  observed: ObservedChatterSignature;
  alreadyPromoted: boolean;
  status: 'promoted' | 'candidate';
}

function keyOf(managedObjectId: string | null, eventType: string): string {
  return `${managedObjectId ?? '__null__'}::${eventType}`;
}

/**
 * Chatter management store (FIX F-UI1, AC 55-56). Reads NF observed-chatter (ranked) +
 * Enrichment chatter list, computes the promoted-vs-candidate join on `(managedObjectId,
 * eventType)`, and writes promotions/removals via Enrichment. Closed loop: NF learned noise →
 * operator review/promote → Enrichment applies live.
 */
@Injectable()
export class ChatterStore {
  private readonly nf = inject(NoiseFilterClient);
  private readonly ecc = inject(EnrichmentChatterClient);

  readonly observed = signal<ObservedChatterSignature[]>([]);
  readonly enrichmentChatter = signal<EnrichmentChatterEntry[]>([]);
  readonly selectedSource = signal<string>('nms-alpha');
  readonly pendingPromotion = signal<string | null>(null);
  readonly loading = signal<boolean>(false);

  readonly joinView = computed<ChatterJoinRow[]>(() => {
    const promoted = new Set(this.enrichmentChatter().map((e) => keyOf(e.managedObjectId, e.eventType)));
    return this.observed().map((o) => {
      const alreadyPromoted = promoted.has(keyOf(o.managedObjectId, o.eventType));
      return { observed: o, alreadyPromoted, status: alreadyPromoted ? 'promoted' : 'candidate' };
    });
  });

  load(): void {
    this.loading.set(true);
    this.nf
      .listObservedChatter()
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => {
        this.observed.set(p.items);
        this.loading.set(false);
      });
    this.loadEnrichment();
  }

  loadEnrichment(): void {
    this.ecc
      .listChatter(this.selectedSource())
      .pipe(catchError(() => of({ source: this.selectedSource(), chatterList: [] })))
      .subscribe((list) => this.enrichmentChatter.set(list.chatterList));
  }

  selectSource(source: string): void {
    this.selectedSource.set(source);
    this.loadEnrichment();
  }

  promote(sig: ObservedChatterSignature): void {
    const key = keyOf(sig.managedObjectId, sig.eventType);
    if (this.pendingPromotion()) {
      return;
    }
    this.pendingPromotion.set(key);
    this.ecc
      .addChatter(this.selectedSource(), { managedObjectId: sig.managedObjectId, eventType: sig.eventType })
      .pipe(catchError(() => of(null)))
      .subscribe((list) => {
        this.pendingPromotion.set(null);
        if (list) {
          this.enrichmentChatter.set(list.chatterList);
        }
        this.loadEnrichment();
      });
  }

  remove(entry: EnrichmentChatterEntry): void {
    const key = keyOf(entry.managedObjectId, entry.eventType);
    if (this.pendingPromotion()) {
      return;
    }
    this.pendingPromotion.set(key);
    this.ecc
      .removeChatter(this.selectedSource(), entry)
      .pipe(catchError(() => of(null)))
      .subscribe((list) => {
        this.pendingPromotion.set(null);
        if (list) {
          this.enrichmentChatter.set(list.chatterList);
        }
        this.loadEnrichment();
      });
  }

  isPending(managedObjectId: string | null, eventType: string): boolean {
    return this.pendingPromotion() === keyOf(managedObjectId, eventType);
  }
}
