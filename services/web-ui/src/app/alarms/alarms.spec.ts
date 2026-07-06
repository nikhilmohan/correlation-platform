import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { AlarmsStore } from './alarms.store';
import { AlarmsComponent } from './alarms.component';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { testProviders, flush } from '../../test-utils';
import { AlarmSummary, StatsVM } from '../api/models';

function store(): AlarmsStore {
  TestBed.configureTestingModule({ providers: [AlarmsStore, RcaAccuracyService, ...testProviders()] });
  return TestBed.inject(AlarmsStore);
}

async function mount() {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const cmp = TestBed.createComponent(AlarmsComponent);
  cmp.detectChanges();
  await flush();
  cmp.detectChanges();
  return cmp;
}

describe('Unified Alarms store (Part 3)', () => {
  it('KPI header — alarm-reduction ratio = totalAlarmsProcessed / totalIncidentsCreated', async () => {
    const s = store();
    s.loadAll();
    await flush();
    expect(s.alarmReductionRatio()).toBeCloseTo(1280 / 154);
    expect(typeof s.alarmReductionRatio()).toBe('number');
  });

  it('KPI header — ratio is null (N/A) when totalIncidentsCreated is zero', () => {
    const s = store();
    s.stats.set({ totalAlarmsProcessed: 100, totalIncidentsCreated: 0 } as StatsVM);
    expect(s.alarmReductionRatio()).toBeNull();
  });

  it('loads alarms + incidents + stats from the mock backends', async () => {
    const s = store();
    s.loadAll();
    await flush();
    expect(s.alarms().length).toBe(6);
    expect(s.liveIncidentCount()).toBe(2);
    expect(s.alarmsProcessed()).toBeGreaterThan(0);
  });

  it('rows() — RCA row carries its incidentId + nested children; uncorrelated alarms are plain rows', async () => {
    const s = store();
    s.loadAll();
    await flush();
    const rows = s.rows();
    const rca = rows.find((r) => r.kind === 'rca')!;
    expect(rca).toBeTruthy();
    expect(rca.incidentId).toBe('INC-12');
    expect(rca.alarm.role).toBe('root-cause');
    expect(rca.alarm.alarmId).toBe('a-3');
    // Children grouped under the RCA row (not top-level rows), ordered by their own raisedAt asc.
    expect(rca.children.map((c) => c.alarmId)).toEqual(['a-7', 'a-8']);
    // Uncorrelated alarms are plain top-level rows.
    const plain = rows.filter((r) => r.kind === 'plain').map((r) => r.alarm.alarmId).sort();
    expect(plain).toEqual(['a-1', 'a-2', 'a-9']);
    // Child alarms are NOT emitted as top-level rows.
    const topLevelIds = rows.map((r) => r.alarm.alarmId);
    expect(topLevelIds).not.toContain('a-7');
    expect(topLevelIds).not.toContain('a-8');
  });

  it('rows() — top-level rows are sorted by timestamp DESCENDING (most recent first)', async () => {
    const s = store();
    s.loadAll();
    await flush();
    const ts = s
      .rows()
      .map((r) => (r.alarm.raisedAt ? Date.parse(r.alarm.raisedAt) : 0));
    for (let i = 1; i < ts.length; i++) {
      expect(ts[i - 1]).toBeGreaterThanOrEqual(ts[i]);
    }
    // a-9 (12:10) is the most-recent uncorrelated alarm and leads the RCA row (RCA raisedAt 12:00).
    expect(s.rows()[0].alarm.alarmId).toBe('a-9');
  });

  it('state filter — filtering to a state restricts the visible alarm set', async () => {
    const s = store();
    s.loadAll();
    await flush();
    s.setStateFilter('correlated');
    const rows = s.rows();
    // Only the correlated group survives; its plain uncorrelated rows drop out.
    expect(rows.every((r) => r.alarm.lifecycleState === 'correlated')).toBe(true);
    expect(rows.some((r) => r.kind === 'rca')).toBe(true);
  });
});

describe('Alarms component (Part 3)', () => {
  it('renders a KPI header strip', async () => {
    const cmp = await mount();
    const el: HTMLElement = cmp.nativeElement;
    expect(el.querySelector('[data-testid="kpi-autocorr"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="kpi-reduction"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="kpi-rca"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="kpi-incidents"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="kpi-processed"]')).toBeTruthy();
  });

  it('table columns are Timestamp-first in the required order', async () => {
    const cmp = await mount();
    const heads = [...cmp.nativeElement.querySelectorAll('thead th')].map((h: Element) => h.textContent?.trim());
    expect(heads).toEqual(['Timestamp', 'Severity', 'Alarm type', 'Managed object', 'State', 'Correlation']);
  });

  it('every alarm row leads with a full absolute timestamp cell (dd MMM yy HH:mm:ss.SSS)', async () => {
    const cmp = await mount();
    const ts = cmp.nativeElement.querySelectorAll('[data-testid="alarm-raised-at"]');
    expect(ts.length).toBeGreaterThanOrEqual(1);
    expect((ts[0] as HTMLElement).textContent?.trim()).toMatch(/\d{2} \w{3} \d{2} \d{2}:\d{2}:\d{2}\.\d{3}/);
    // Timestamp is the FIRST cell of the first row.
    const firstRow = cmp.nativeElement.querySelector('[data-testid="alarm-row"]') as HTMLElement;
    const firstCell = firstRow.querySelector('td');
    expect(firstCell?.getAttribute('data-testid')).toBe('alarm-raised-at');
  });

  it('rows are severity colour-coded via a severity pill + a severity border class', async () => {
    const cmp = await mount();
    const pills = cmp.nativeElement.querySelectorAll('[data-testid="alarm-severity"]');
    expect(pills.length).toBeGreaterThanOrEqual(1);
    // The RCA row (a-3) is a critical alarm → sev-critical pill + sev-border-critical row.
    const rcaRow = cmp.nativeElement.querySelector('[data-role="root-cause"]') as HTMLElement;
    expect(rcaRow.className).toContain('sev-border-critical');
    expect(rcaRow.querySelector('.sev-critical')).toBeTruthy();
  });

  it('the root-cause row is highlighted with an RCA badge and a clickable incident link', async () => {
    const cmp = await mount();
    const rcaRow = cmp.nativeElement.querySelector('[data-role="root-cause"]') as HTMLElement;
    expect(rcaRow.classList.contains('rca-row')).toBe(true);
    expect(rcaRow.querySelector('[data-testid="rca-badge"]')).toBeTruthy();
    const link = rcaRow.querySelector('[data-testid="alarm-incident-link"]') as HTMLAnchorElement;
    expect(link).toBeTruthy();
    expect(link.getAttribute('href')).toContain('/incidents/INC-12');
  });

  it('child alarms are collapsed by default and expand via the RCA-row toggle', async () => {
    const cmp = await mount();
    // Default collapsed: only top-level rows (RCA + 3 uncorrelated = 4).
    let rows = cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]');
    expect([...rows].filter((r: Element) => r.getAttribute('data-role') === 'child').length).toBe(0);

    const toggle = cmp.nativeElement.querySelector('[data-testid="alarm-expand"]') as HTMLButtonElement;
    expect(toggle).toBeTruthy();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    cmp.detectChanges();

    rows = cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]');
    const children = [...rows].filter((r: Element) => r.getAttribute('data-role') === 'child');
    expect(children.length).toBe(2);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
  });

  it('the lifecycle-state filter restricts the rendered rows', async () => {
    const cmp = await mount();
    const select = cmp.nativeElement.querySelector('[data-testid="alarm-filter"]') as HTMLSelectElement;
    select.value = 'correlated';
    select.dispatchEvent(new Event('change'));
    cmp.detectChanges();
    const states = [...cmp.nativeElement.querySelectorAll('[data-testid="lifecycle-state"]')].map(
      (s: Element) => s.textContent?.trim(),
    );
    expect(states.length).toBeGreaterThanOrEqual(1);
    expect(states.every((s) => s === 'correlated')).toBe(true);
  });
});

describe('AlarmsStore — graceful RCA promotion', () => {
  it('promotes the newest surviving member when the root-cause alarm is filtered out', () => {
    const s = store();
    const alarms: AlarmSummary[] = [
      { alarmId: 'rc', managedObjectId: 'm', eventType: 'LOS', perceivedSeverity: 'critical', raisedAt: '2026-01-01T00:00:00Z', lifecycleState: 'correlated', role: 'root-cause', incidentId: 'INC-1' },
      { alarmId: 'c1', managedObjectId: 'm', eventType: 'LinkDown', perceivedSeverity: 'major', raisedAt: '2026-01-01T00:00:05Z', lifecycleState: 'open', role: 'child', incidentId: 'INC-1' },
    ];
    s.alarms.set(alarms);
    s.setStateFilter('open');
    const rows = s.rows();
    const rca = rows.find((r) => r.kind === 'rca')!;
    expect(rca).toBeTruthy();
    expect(rca.incidentId).toBe('INC-1');
    // rc was filtered out (correlated); the surviving open child is promoted to the row alarm.
    expect(rca.alarm.alarmId).toBe('c1');
  });
});
