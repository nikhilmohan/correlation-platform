import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, forkJoin, of } from 'rxjs';
import { NoiseFilterClient } from '../api/noise-filter.client';
import { EnrichmentChatterClient } from '../api/enrichment-chatter.client';
import { EnrichmentChatterEntry, EnrichmentChatterList, ObservedChatterSignature } from '../api/models';

export interface ChatterJoinRow {
  observed: ObservedChatterSignature;
  alreadyPromoted: boolean;
  status: 'promoted' | 'candidate';
}

/** How the observed-chatter chart groups its bars. */
export type GroupBy = 'alarmType' | 'deviceType';

/**
 * One aggregated class bar (an alarmType or a device-type). Length = total occurrenceCount over
 * all member observed-chatter entries of the class; the members drive per-object drill-down and
 * the class-level fan-out suppress.
 */
export interface ChatterClassBar {
  /** The class key — the alarmType, or the device-type prefix before ':' in managedObjectId. */
  key: string;
  /** Sum of occurrenceCount across all member entries (bar length; sorted desc). */
  totalOccurrences: number;
  /** Member observed-chatter entries (sorted by occurrenceCount desc), for drill-down + fan-out. */
  members: ChatterJoinRow[];
  /** Distinct suppressable (managedObjectId,eventType) member keys — the fan-out cardinality. */
  suppressableCount: number;
  /** True when EVERY suppressable member is already promoted (→ show a badge, not a button). */
  fullySuppressed: boolean;
}

function keyOf(managedObjectId: string | null, eventType: string): string {
  return `${managedObjectId ?? '__null__'}::${eventType}`;
}

/** Device-type = the token before the first ':' in a managedObjectId (Port, IPLink, LSP…). */
function deviceTypeOf(managedObjectId: string | null): string {
  if (!managedObjectId) {
    return 'source-level';
  }
  const idx = managedObjectId.indexOf(':');
  return idx > 0 ? managedObjectId.slice(0, idx) : managedObjectId;
}

/**
 * Chatter management store (learn → promote → suppress loop). Reads NF observed-chatter + the
 * Enrichment per-source suppression list, computes the promoted-vs-candidate join on
 * `(managedObjectId, eventType)`, aggregates observed chatter into CLASS bars two ways (by
 * alarmType / by device-type) for the chart-driven view, and writes promotions/removals via
 * Enrichment. Class-level "Suppress" FANS OUT to individual per-object addChatter calls (no
 * contract change — a native class rule is a future Enrichment enhancement).
 */
@Injectable()
export class ChatterStore {
  private readonly nf = inject(NoiseFilterClient);
  private readonly ecc = inject(EnrichmentChatterClient);

  readonly observed = signal<ObservedChatterSignature[]>([]);
  readonly enrichmentChatter = signal<EnrichmentChatterEntry[]>([]);
  readonly selectedSource = signal<string>('nms-alpha');
  readonly groupBy = signal<GroupBy>('alarmType');
  /** Class keys the operator has expanded to reveal per-object drill-down. */
  readonly expanded = signal<ReadonlySet<string>>(new Set());
  readonly pendingPromotion = signal<string | null>(null);
  /** Class key with an in-flight fan-out suppress (button disabled + labelled). */
  readonly pendingClass = signal<string | null>(null);
  readonly loading = signal<boolean>(false);

  readonly joinView = computed<ChatterJoinRow[]>(() => {
    const promoted = new Set(this.enrichmentChatter().map((e) => keyOf(e.managedObjectId, e.eventType)));
    return this.observed().map((o) => {
      const alreadyPromoted = promoted.has(keyOf(o.managedObjectId, o.eventType));
      return { observed: o, alreadyPromoted, status: alreadyPromoted ? 'promoted' : 'candidate' } as ChatterJoinRow;
    });
  });

  /**
   * Observed chatter aggregated into class bars per the active grouping, sorted DESCENDING by
   * total occurrenceCount. Pure client-side derivation from the real rows (no invented data).
   */
  readonly classBars = computed<ChatterClassBar[]>(() => {
    const grouping = this.groupBy();
    const rows = this.joinView();
    const byKey = new Map<string, ChatterJoinRow[]>();
    for (const row of rows) {
      const key = grouping === 'alarmType' ? row.observed.alarmType : deviceTypeOf(row.observed.managedObjectId);
      const bucket = byKey.get(key);
      if (bucket) {
        bucket.push(row);
      } else {
        byKey.set(key, [row]);
      }
    }
    const bars: ChatterClassBar[] = [...byKey.entries()].map(([key, members]) => {
      const sorted = [...members].sort((a, b) => b.observed.occurrenceCount - a.observed.occurrenceCount);
      const suppressable = new Set(sorted.map((m) => keyOf(m.observed.managedObjectId, m.observed.eventType)));
      return {
        key,
        totalOccurrences: sorted.reduce((s, m) => s + m.observed.occurrenceCount, 0),
        members: sorted,
        suppressableCount: suppressable.size,
        fullySuppressed: sorted.every((m) => m.alreadyPromoted),
      };
    });
    return bars.sort((a, b) => b.totalOccurrences - a.totalOccurrences);
  });

  /** Largest class total — the 100% reference for bar lengths (guarded against empty/0). */
  readonly maxClassTotal = computed<number>(() => Math.max(0, ...this.classBars().map((b) => b.totalOccurrences)));

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

  setGroupBy(grouping: GroupBy): void {
    this.groupBy.set(grouping);
  }

  toggleExpanded(key: string): void {
    const next = new Set(this.expanded());
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    this.expanded.set(next);
  }

  isExpanded(key: string): boolean {
    return this.expanded().has(key);
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

  /**
   * Class-level suppress = FAN-OUT: promote EVERY not-yet-suppressed member object of the class as
   * an INDIVIDUAL per-object `{managedObjectId, eventType}` add, concurrently, in one action.
   * Resilient to partial failure (each add's error is swallowed to null so one failure doesn't
   * abort the batch), then the Enrichment list is re-read to reflect what actually landed.
   */
  suppressClass(bar: ChatterClassBar): void {
    if (this.pendingClass()) {
      return;
    }
    const targets = bar.members.filter((m) => !m.alreadyPromoted).map((m) => m.observed);
    if (!targets.length) {
      return;
    }
    this.pendingClass.set(bar.key);
    const source = this.selectedSource();
    const adds = targets.map((sig) =>
      this.ecc
        .addChatter(source, { managedObjectId: sig.managedObjectId, eventType: sig.eventType })
        .pipe(catchError(() => of(null))),
    );
    forkJoin(adds).subscribe((results: (EnrichmentChatterList | null)[]) => {
      this.pendingClass.set(null);
      // Prefer the last non-null list echoed back; the re-read is authoritative regardless.
      const last = [...results].reverse().find((r): r is EnrichmentChatterList => r !== null);
      if (last) {
        this.enrichmentChatter.set(last.chatterList);
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

  isClassPending(key: string): boolean {
    return this.pendingClass() === key;
  }
}
