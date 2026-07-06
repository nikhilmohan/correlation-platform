import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of } from 'rxjs';
import { NoiseFilterClient } from '../api/noise-filter.client';
import { RunStatsRow } from '../api/models';

/** Aggregate noise-filter headline across all loaded runs (Part 4 graphical view). */
export interface NoiseAggregate {
  runs: number;
  alarmsIn: number;
  alarmsKept: number;
  alarmsDropped: number;
  clustersFormed: number;
  /** Fraction of alarms dropped as noise across all runs [0..1]; null when nothing came in. */
  noiseRatio: number | null;
  /** Overall reduction ratio alarmsIn / clustersFormed; null when no clusters formed. */
  stormReduction: number | null;
  /** Kept fraction [0..1]; null when nothing came in. */
  keptRatio: number | null;
}

/**
 * Store backing the graphical Noise view (Part 4 — replaces the dense Stats "Noise run-stats" table
 * with its own top-level tab/route). Loads the Noise Filter run-stats and derives an aggregate
 * headline (total in → kept, overall reduction) plus per-run proportions for the CSS/SVG bars.
 */
@Injectable()
export class NoiseStore {
  private readonly nf = inject(NoiseFilterClient);

  readonly runStats = signal<RunStatsRow[]>([]);
  readonly trailFilter = signal<string>('');

  /** Per-run storm-reduction ratio (alarmsIn / clustersFormed), guarded. */
  stormReduction(row: RunStatsRow): number | null {
    if (row.stormReductionRatio !== undefined) {
      return row.stormReductionRatio;
    }
    return row.clustersFormed > 0 ? row.alarmsIn / row.clustersFormed : null;
  }

  /** Kept fraction of a run [0..1]; null when nothing came in. */
  keptRatio(row: RunStatsRow): number | null {
    return row.alarmsIn > 0 ? row.alarmsKept / row.alarmsIn : null;
  }

  /** Dropped fraction of a run [0..1]; null when nothing came in. */
  droppedRatio(row: RunStatsRow): number | null {
    return row.alarmsIn > 0 ? row.alarmsDropped / row.alarmsIn : null;
  }

  readonly aggregate = computed<NoiseAggregate>(() => {
    const rows = this.runStats();
    const alarmsIn = rows.reduce((s, r) => s + r.alarmsIn, 0);
    const alarmsKept = rows.reduce((s, r) => s + r.alarmsKept, 0);
    const alarmsDropped = rows.reduce((s, r) => s + r.alarmsDropped, 0);
    const clustersFormed = rows.reduce((s, r) => s + r.clustersFormed, 0);
    return {
      runs: rows.length,
      alarmsIn,
      alarmsKept,
      alarmsDropped,
      clustersFormed,
      noiseRatio: alarmsIn > 0 ? alarmsDropped / alarmsIn : null,
      stormReduction: clustersFormed > 0 ? alarmsIn / clustersFormed : null,
      keptRatio: alarmsIn > 0 ? alarmsKept / alarmsIn : null,
    };
  });

  loadRunStats(trailId?: string): void {
    this.trailFilter.set(trailId ?? '');
    this.nf
      .listRunStats({ trailId: trailId || undefined })
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => this.runStats.set(p.items));
  }
}
