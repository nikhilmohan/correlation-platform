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

/** One time bucket spanning [start, end); label is a short local time for the axis. */
export interface TimeBucket {
  index: number;
  start: number;
  end: number;
  label: string;
}

/** A single heatmap cell for Heatmap A (time × noise/signal). */
export interface TimeHeatCell {
  bucket: number;
  /** Absolute count in the cell (dropped or kept alarms summed over the bucket). */
  count: number;
  /** count normalised to the hottest cell in the whole heatmap [0..1]. */
  intensity: number;
}

/** Heatmap A — two heat-rows (dropped-as-noise, kept-as-signal) over the same time buckets. */
export interface TimeHeatmap {
  buckets: TimeBucket[];
  droppedRow: TimeHeatCell[];
  keptRow: TimeHeatCell[];
  /** Hottest single-cell count across both rows (the colour-scale max); 0 when no data. */
  maxCell: number;
}

/** A single heatmap cell for Heatmap B (trail × time), coloured by noise ratio [0..1]. */
export interface TrailHeatCell {
  bucket: number;
  alarmsIn: number;
  alarmsDropped: number;
  /** Noise ratio dropped/in for the trail×bucket; null when no alarms came in there. */
  noiseRatio: number | null;
}

export interface TrailHeatRow {
  trailId: string;
  /** Total dropped across the row — used to rank the noisiest trails. */
  totalDropped: number;
  cells: TrailHeatCell[];
}

/** Heatmap B — top-N noisiest trails (rows) × time buckets, colour = noise ratio. */
export interface TrailHeatmap {
  buckets: TimeBucket[];
  rows: TrailHeatRow[];
  /** Trails omitted beyond the top-N cap (rendered as a "+N more" note). */
  omitted: number;
}

/** Bucket count target when the run window spans a real range. */
const TARGET_BUCKETS = 16;
/** Cap on how many trail rows Heatmap B renders (there can be ~40 trails). */
const MAX_TRAIL_ROWS = 12;

/** Millisecond epoch of a run's position on the time axis (windowStart, else runTimestamp). */
function runTime(r: RunStatsRow): number {
  const t = Date.parse(r.windowStart ?? '') || Date.parse(r.runTimestamp ?? '');
  return Number.isFinite(t) ? t : 0;
}

/** Short local HH:MM label for a bucket start (falls back to an index when time is absent). */
function bucketLabel(ms: number, index: number): string {
  if (!ms) {
    return `#${index + 1}`;
  }
  const d = new Date(ms);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

/**
 * Store backing the graphical Noise view (Part 4 — its own top-level tab/route). Loads the Noise
 * Filter run-stats, derives the aggregate headline, and derives TWO client-side heatmaps from the
 * real rows (no invented data, pure bucketing): Heatmap A (time × noise-vs-signal) and Heatmap B
 * (trail × time noise-ratio). Bucketing degrades gracefully — a tiny time span falls back to
 * per-run columns; sparse data still renders legibly.
 */
@Injectable()
export class NoiseStore {
  private readonly nf = inject(NoiseFilterClient);

  readonly runStats = signal<RunStatsRow[]>([]);
  readonly trailFilter = signal<string>('');
  /** Active heatmap: 'time' = Heatmap A (default), 'trail' = Heatmap B. */
  readonly heatmapMode = signal<'time' | 'trail'>('time');

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

  /**
   * Time buckets spanning the loaded runs' [min..max] window. When every run sits in the same
   * instant (a tiny span, common with sparse live data), we fall back to ONE column per run so the
   * heatmap still reads left-to-right rather than collapsing to a single cell.
   */
  readonly timeBuckets = computed<TimeBucket[]>(() => {
    const rows = this.runStats();
    if (!rows.length) {
      return [];
    }
    const times = rows.map(runTime);
    const min = Math.min(...times);
    const max = Math.max(...times);
    // Degenerate span → per-run columns (chronological).
    if (max - min <= 0) {
      const ordered = [...rows].sort((a, b) => runTime(a) - runTime(b));
      return ordered.map((r, i) => ({ index: i, start: min, end: min, label: bucketLabel(runTime(r), i) }));
    }
    const n = Math.min(TARGET_BUCKETS, Math.max(2, rows.length));
    const step = (max - min) / n;
    const buckets: TimeBucket[] = [];
    for (let i = 0; i < n; i++) {
      const start = min + i * step;
      buckets.push({ index: i, start, end: i === n - 1 ? max + 1 : start + step, label: bucketLabel(start, i) });
    }
    return buckets;
  });

  /** Which bucket a run falls into. Per-run-column fallback assigns by chronological order. */
  private bucketIndexFor(row: RunStatsRow, buckets: TimeBucket[]): number {
    if (!buckets.length) {
      return 0;
    }
    // Per-run-column fallback: every bucket has start === end.
    const degenerate = buckets.every((b) => b.end === b.start);
    if (degenerate) {
      const ordered = [...this.runStats()].sort((a, b) => runTime(a) - runTime(b));
      const idx = ordered.indexOf(row);
      return idx < 0 ? 0 : Math.min(idx, buckets.length - 1);
    }
    const t = runTime(row);
    for (const b of buckets) {
      if (t >= b.start && t < b.end) {
        return b.index;
      }
    }
    return buckets.length - 1;
  }

  /** Heatmap A — time × {dropped-as-noise, kept-as-signal}. Cell colour = normalised count. */
  readonly timeHeatmap = computed<TimeHeatmap>(() => {
    const rows = this.runStats();
    const buckets = this.timeBuckets();
    const dropped = buckets.map(() => 0);
    const kept = buckets.map(() => 0);
    for (const r of rows) {
      const i = this.bucketIndexFor(r, buckets);
      dropped[i] += r.alarmsDropped;
      kept[i] += r.alarmsKept;
    }
    const maxCell = Math.max(0, ...dropped, ...kept);
    const norm = (c: number): number => (maxCell > 0 ? c / maxCell : 0);
    return {
      buckets,
      droppedRow: dropped.map((count, bucket) => ({ bucket, count, intensity: norm(count) })),
      keptRow: kept.map((count, bucket) => ({ bucket, count, intensity: norm(count) })),
      maxCell,
    };
  });

  /** Heatmap B — top-N noisiest trails × time, colour = noise ratio (dropped/in) per cell. */
  readonly trailHeatmap = computed<TrailHeatmap>(() => {
    const rows = this.runStats();
    const buckets = this.timeBuckets();
    // Group runs by trailId → per-bucket in/dropped accumulators.
    const byTrail = new Map<string, { in: number[]; dropped: number[] }>();
    for (const r of rows) {
      const key = r.trailId ?? '—';
      let acc = byTrail.get(key);
      if (!acc) {
        acc = { in: buckets.map(() => 0), dropped: buckets.map(() => 0) };
        byTrail.set(key, acc);
      }
      const i = this.bucketIndexFor(r, buckets);
      acc.in[i] += r.alarmsIn;
      acc.dropped[i] += r.alarmsDropped;
    }
    const allRows: TrailHeatRow[] = [...byTrail.entries()].map(([trailId, acc]) => ({
      trailId,
      totalDropped: acc.dropped.reduce((s, v) => s + v, 0),
      cells: buckets.map((b, i) => ({
        bucket: b.index,
        alarmsIn: acc.in[i],
        alarmsDropped: acc.dropped[i],
        noiseRatio: acc.in[i] > 0 ? acc.dropped[i] / acc.in[i] : null,
      })),
    }));
    // Rank by noisiest (most dropped) and cap the row count.
    allRows.sort((a, b) => b.totalDropped - a.totalDropped);
    const shown = allRows.slice(0, MAX_TRAIL_ROWS);
    return { buckets, rows: shown, omitted: Math.max(0, allRows.length - shown.length) };
  });

  loadRunStats(trailId?: string): void {
    this.trailFilter.set(trailId ?? '');
    this.nf
      .listRunStats({ trailId: trailId || undefined })
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((p) => this.runStats.set(p.items));
  }

  setHeatmapMode(mode: 'time' | 'trail'): void {
    this.heatmapMode.set(mode);
  }
}
