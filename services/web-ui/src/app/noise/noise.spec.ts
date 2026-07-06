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

  it('renders the kept-vs-dropped proportion bar + noise/storm gauges (CSS/SVG, no chart lib)', async () => {
    const cmp = await mount();
    const el: HTMLElement = cmp.nativeElement;
    const bar = el.querySelector('[data-testid="agg-prop-bar"]') as HTMLElement;
    expect(bar).toBeTruthy();
    // Two segments summing to the full width; kept + dropped both present.
    expect(bar.querySelector('.seg-kept')).toBeTruthy();
    expect(bar.querySelector('.seg-dropped')).toBeTruthy();
    // aria-label carries the values (not colour-only).
    expect(bar.getAttribute('aria-label')).toMatch(/kept/i);
    expect(el.querySelector('[data-testid="gauge-noise"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="gauge-storm"]')).toBeTruthy();
  });

  it('renders a per-run breakdown bar per run row (with non-zero alarmsIn)', async () => {
    const cmp = await mount();
    const rows = cmp.nativeElement.querySelectorAll('[data-testid="run-row"]');
    expect(rows.length).toBeGreaterThanOrEqual(2);
    const alarmsIn = (cmp.nativeElement.querySelector('[data-testid="run-alarmsIn"]') as HTMLElement).textContent ?? '';
    expect(Number(alarmsIn.replace(/\D/g, ''))).toBeGreaterThan(0);
    expect(cmp.nativeElement.querySelector('[data-testid="run-storm"]')?.textContent).toContain(': 1');
  });
});
