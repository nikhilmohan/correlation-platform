import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NoiseStore } from './noise.store';
import { NoiseViewComponent } from './noise-view.component';
import { testProviders, flush } from '../../test-utils';
import { RunStatsRow } from '../api/models';

function store(): NoiseStore {
  TestBed.configureTestingModule({ providers: [NoiseStore, ...testProviders()] });
  return TestBed.inject(NoiseStore);
}

async function mount() {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const cmp = TestBed.createComponent(NoiseViewComponent);
  cmp.detectChanges();
  await flush();
  cmp.detectChanges();
  return cmp;
}

describe('Noise store (Part 4)', () => {
  it('loads run-stats and derives per-run storm-reduction (alarmsIn / clustersFormed)', async () => {
    const s = store();
    s.loadRunStats();
    await flush();
    const rows = s.runStats();
    expect(rows.length).toBeGreaterThanOrEqual(2);
    const run9 = rows.find((r) => r.runId === 'RUN-9')!;
    expect(run9.alarmsIn).toBe(240);
    expect(s.stormReduction(run9)).toBeCloseTo(20); // 240 / 12
    expect(s.keptRatio(run9)).toBeCloseTo(180 / 240);
    expect(s.droppedRatio(run9)).toBeCloseTo(60 / 240);
  });

  it('storm-reduction guards divide-by-zero (clustersFormed = 0 → null)', () => {
    const s = store();
    const row = { runId: 'R', alarmsIn: 10, clustersFormed: 0, alarmsKept: 0, alarmsDropped: 0 } as RunStatsRow;
    expect(s.stormReduction(row)).toBeNull();
    expect(s.keptRatio({ ...row, alarmsIn: 0 } as RunStatsRow)).toBeNull();
  });

  it('aggregate — sums alarms in/kept/dropped across runs and derives overall reduction + noise ratio', async () => {
    const s = store();
    s.loadRunStats();
    await flush();
    const rows = s.runStats();
    const totalIn = rows.reduce((a, r) => a + r.alarmsIn, 0);
    const totalKept = rows.reduce((a, r) => a + r.alarmsKept, 0);
    const totalDropped = rows.reduce((a, r) => a + r.alarmsDropped, 0);
    const totalClusters = rows.reduce((a, r) => a + r.clustersFormed, 0);
    const agg = s.aggregate();
    expect(agg.runs).toBe(rows.length);
    expect(agg.alarmsIn).toBe(totalIn);
    expect(agg.alarmsKept).toBe(totalKept);
    expect(agg.alarmsDropped).toBe(totalDropped);
    expect(agg.noiseRatio).toBeCloseTo(totalDropped / totalIn);
    expect(agg.stormReduction).toBeCloseTo(totalIn / totalClusters);
    expect(agg.keptRatio).toBeCloseTo(totalKept / totalIn);
  });

  it('applying a trailId filter loads only matching rows', async () => {
    const s = store();
    s.loadRunStats('TR-8');
    await flush();
    const rows = s.runStats();
    expect(rows.length).toBeGreaterThanOrEqual(1);
    expect(rows.every((r) => r.trailId === 'TR-8')).toBe(true);
    expect(s.trailFilter()).toBe('TR-8');
  });
});

describe('Noise store (Part 4) — heatmap derivations', () => {
  /** Runs spanning a real time range across two trails, for bucketing/heatmap assertions. */
  function seedRuns(): RunStatsRow[] {
    const base = (iso: string, trailId: string, alarmsIn: number, kept: number, dropped: number): RunStatsRow =>
      ({
        runId: `R-${iso}-${trailId}`,
        runTimestamp: iso,
        trailId,
        snapshotId: 'current',
        windowStart: iso,
        windowEnd: iso,
        eps: 0.5,
        minSamples: 3,
        windowSize: 60,
        algorithm: 'dbscan',
        alarmsIn,
        clustersFormed: 2,
        alarmsKept: kept,
        alarmsDropped: dropped,
        noiseRatio: alarmsIn ? dropped / alarmsIn : 0,
      }) as RunStatsRow;
    return [
      base('2026-05-10T00:00:00Z', 'TR-7', 10, 4, 6),
      base('2026-05-10T00:30:00Z', 'TR-7', 20, 8, 12),
      base('2026-05-10T01:00:00Z', 'TR-8', 5, 4, 1),
      base('2026-05-10T02:00:00Z', 'TR-8', 8, 6, 2),
    ];
  }

  it('Heatmap A — buckets the runs across the window and sums dropped/kept per bucket', () => {
    const s = store();
    s.runStats.set(seedRuns());
    const hm = s.timeHeatmap();
    expect(hm.buckets.length).toBeGreaterThanOrEqual(2);
    // Totals across all buckets equal the raw sums (bucketing conserves counts).
    const totalDropped = hm.droppedRow.reduce((a, c) => a + c.count, 0);
    const totalKept = hm.keptRow.reduce((a, c) => a + c.count, 0);
    expect(totalDropped).toBe(6 + 12 + 1 + 2);
    expect(totalKept).toBe(4 + 8 + 4 + 6);
    // The earliest bucket holds the first run's dropped=6; hottest cell = max over both rows (12).
    expect(hm.droppedRow[0].count).toBe(6);
    expect(hm.maxCell).toBe(12);
    // Intensity normalised to the hottest cell.
    const hottest = hm.droppedRow.find((c) => c.count === 12)!;
    expect(hottest.intensity).toBeCloseTo(1);
  });

  it('Heatmap A — degenerate time span falls back to one column per run (chronological)', () => {
    const s = store();
    const same = seedRuns().map((r) => ({ ...r, windowStart: '2026-05-10T00:00:00Z', runTimestamp: '2026-05-10T00:00:00Z' }));
    s.runStats.set(same);
    const hm = s.timeHeatmap();
    expect(hm.buckets.length).toBe(same.length);
  });

  it('Heatmap B — one row per trail (noisiest first), cell = noise ratio dropped/in', () => {
    const s = store();
    s.runStats.set(seedRuns());
    const hm = s.trailHeatmap();
    expect(hm.rows.map((r) => r.trailId)).toEqual(['TR-7', 'TR-8']); // TR-7 dropped 18 > TR-8 dropped 3
    const tr7 = hm.rows.find((r) => r.trailId === 'TR-7')!;
    // The bucket holding TR-7's second run (in 20, dropped 12) → ratio 0.6.
    const ratios = tr7.cells.map((c) => c.noiseRatio).filter((v) => v !== null);
    expect(ratios).toContainEqual(0.6);
  });

  it('Heatmap B — caps to top-N noisiest trails and reports the omitted count', () => {
    const s = store();
    // 15 trails, each a single run → 12 shown, 3 omitted.
    const many: RunStatsRow[] = Array.from({ length: 15 }, (_, i) => ({
      runId: `R${i}`,
      runTimestamp: '2026-05-10T00:00:00Z',
      trailId: `TR-${i}`,
      snapshotId: 'current',
      windowStart: `2026-05-10T0${i % 3}:00:00Z`,
      windowEnd: '2026-05-10T03:00:00Z',
      eps: 0.5,
      minSamples: 3,
      windowSize: 60,
      algorithm: 'dbscan',
      alarmsIn: 10 + i,
      clustersFormed: 2,
      alarmsKept: 2,
      alarmsDropped: 8 + i,
      noiseRatio: 0.5,
    })) as RunStatsRow[];
    s.runStats.set(many);
    const hm = s.trailHeatmap();
    expect(hm.rows.length).toBe(12);
    expect(hm.omitted).toBe(3);
  });
});

describe('Noise view component (Part 4) — graphical', () => {
  it('renders a prominent aggregate headline (in → kept → dropped → reduction)', async () => {
    const cmp = await mount();
    const el: HTMLElement = cmp.nativeElement;
    expect(el.querySelector('[data-testid="noise-aggregate"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="agg-in"]')?.textContent?.trim()).toBeTruthy();
    expect(el.querySelector('[data-testid="agg-kept"]')?.textContent?.trim()).toBeTruthy();
    expect(el.querySelector('[data-testid="agg-dropped"]')?.textContent?.trim()).toBeTruthy();
    expect(el.querySelector('[data-testid="agg-reduction"]')?.textContent).toContain(': 1');
  });

  it('renders the kept-vs-dropped proportion bar (CSS/SVG, no chart lib)', async () => {
    const cmp = await mount();
    const el: HTMLElement = cmp.nativeElement;
    const bar = el.querySelector('[data-testid="agg-prop-bar"]') as HTMLElement;
    expect(bar).toBeTruthy();
    expect(bar.querySelector('.seg-kept')).toBeTruthy();
    expect(bar.querySelector('.seg-dropped')).toBeTruthy();
    expect(bar.getAttribute('aria-label')).toMatch(/kept/i);
  });

  it('renders Heatmap A by default (time × noise/signal) with dropped/kept cells carrying aria-labels', async () => {
    const cmp = await mount();
    const el: HTMLElement = cmp.nativeElement;
    expect(el.querySelector('[data-testid="noise-heatmap-time"]')).toBeTruthy();
    const droppedCells = el.querySelectorAll('[data-testid="heat-cell-dropped"]');
    const keptCells = el.querySelectorAll('[data-testid="heat-cell-kept"]');
    expect(droppedCells.length).toBeGreaterThan(0);
    expect(keptCells.length).toBeGreaterThan(0);
    // Non-colour-only: each cell has an aria-label naming the real count.
    expect((droppedCells[0] as HTMLElement).getAttribute('aria-label')).toMatch(/dropped \d+ alarm/);
    // Legend anchors present.
    expect(el.querySelector('.hm-legend-anchors')?.textContent).toContain('–');
  });

  it('the toggle switches to Heatmap B (trail × time)', async () => {
    const cmp = await mount();
    const el: HTMLElement = cmp.nativeElement;
    expect(el.querySelector('[data-testid="noise-heatmap-trail"]')).toBeFalsy();
    const toggle = el.querySelector('[data-testid="noise-heatmap-toggle"]') as HTMLElement;
    const trailBtn = Array.from(toggle.querySelectorAll('button')).find((b) => /Trail/i.test(b.textContent ?? ''))!;
    trailBtn.click();
    cmp.detectChanges();
    expect(el.querySelector('[data-testid="noise-heatmap-trail"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="noise-heatmap-time"]')).toBeFalsy();
    const trailCell = el.querySelector('[data-testid="heat-cell-trail"]') as HTMLElement | null;
    if (trailCell) {
      expect(trailCell.getAttribute('aria-label')).toMatch(/noise|no alarms/);
    }
  });
});
