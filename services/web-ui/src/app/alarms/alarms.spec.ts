import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { AlarmsStore } from './alarms.store';
import { AlarmsComponent } from './alarms.component';
import { LivePollingService } from '../streaming/live-polling.service';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { testProviders, flush } from '../../test-utils';
import { AlarmSummary, IncidentVM, StatsVM, SynthSummaryModel } from '../api/models';

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
    expect(el.querySelector('[data-testid="kpi-dedup"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="kpi-rca"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="kpi-incidents"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="kpi-processed"]')).toBeTruthy();
  });

  it('table columns are Timestamp-first in the required order', async () => {
    const cmp = await mount();
    // The State header carries an info affordance (ⓘ) for the lifecycle-state legend; normalise it.
    const heads = [...cmp.nativeElement.querySelectorAll('thead th')].map((h: Element) =>
      h.textContent?.replace(/\s*ⓘ\s*/g, '').trim(),
    );
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

  it('child alarms are EXPANDED by default and collapse via the RCA-row toggle', async () => {
    const cmp = await mount();
    // Default EXPANDED: children visible without any user interaction.
    let rows = cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]');
    expect([...rows].filter((r: Element) => r.getAttribute('data-role') === 'child').length).toBe(2);

    const toggle = cmp.nativeElement.querySelector('[data-testid="alarm-expand"]') as HTMLButtonElement;
    expect(toggle).toBeTruthy();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    // Clicking collapses the group (hides its children).
    toggle.click();
    cmp.detectChanges();

    rows = cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]');
    expect([...rows].filter((r: Element) => r.getAttribute('data-role') === 'child').length).toBe(0);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    // Clicking again re-expands.
    toggle.click();
    cmp.detectChanges();
    rows = cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]');
    expect([...rows].filter((r: Element) => r.getAttribute('data-role') === 'child').length).toBe(2);
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

describe('Alarms — collapsible incident GROUPS (Feature 1)', () => {
  it('each incident renders as one collapsible group header (data-group="true"), EXPANDED by default', async () => {
    const cmp = await mount();
    // Group headers keep the alarm-row testid AND carry data-group + a distinct alarm-group marker.
    const groups = [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"][data-group="true"]')];
    // The mock has one correlated incident (INC-12) → exactly one group header.
    expect(groups.length).toBe(1);
    const group = groups[0] as HTMLElement;
    expect(group.getAttribute('data-incident-id')).toBe('INC-12');
    expect(group.querySelector('[data-testid="alarm-group"]')).toBeTruthy();
    // EXPANDED by default: child rows are visible on load (no user interaction).
    const children = [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]')].filter(
      (r: Element) => r.getAttribute('data-role') === 'child',
    );
    expect(children.length).toBe(2);
    // The per-row collapse/expand toggle is present and reports expanded.
    const toggle = group.querySelector('[data-testid="alarm-expand"]') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
  });

  it("the group HEADER status pill reads 'correlated' for the whole group (not per-child state)", async () => {
    const cmp = await mount();
    const group = cmp.nativeElement.querySelector('[data-testid="alarm-row"][data-group="true"]') as HTMLElement;
    const statePill = group.querySelector('[data-testid="lifecycle-state"]') as HTMLElement;
    expect(statePill.textContent?.trim()).toBe('correlated');
    expect(statePill.classList.contains('state-correlated')).toBe(true);
  });

  it('the group header shows a "root cause + N correlated alarms" child count', async () => {
    const cmp = await mount();
    const count = cmp.nativeElement.querySelector('[data-testid="alarm-group"]') as HTMLElement;
    expect(count.textContent?.replace(/\s+/g, ' ').trim()).toBe('root cause + 2 correlated alarms');
  });

  it('a group shows its child alarm rows by default (each with its OWN lifecycle state)', async () => {
    const cmp = await mount();
    // Children are visible on load (expanded by default) — no toggle click needed.
    const children = [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]')].filter(
      (r: Element) => r.getAttribute('data-role') === 'child',
    );
    expect(children.length).toBe(2);
    // A child row shows its own state (correlated in the mock), independent of the group pill.
    const childState = children[0].querySelector('[data-testid="lifecycle-state"]') as HTMLElement;
    expect(childState.textContent?.trim()).toBe('correlated');
  });

  it('uncorrelated alarms are PLAIN ungrouped rows (no group wrapper)', async () => {
    const cmp = await mount();
    const plain = [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]')].filter(
      (r: Element) => r.getAttribute('data-role') === 'none',
    );
    // a-1, a-2, a-9 are uncorrelated → 3 plain rows, none inside a group.
    expect(plain.length).toBe(3);
    plain.forEach((r: Element) => expect(r.getAttribute('data-testid')).toBe('alarm-row'));
  });

  it('the collapse-all / expand-all bulk toggle closes then re-opens every group (starts expanded)', async () => {
    const cmp = await mount();
    const expandAll = cmp.nativeElement.querySelector('[data-testid="alarm-expand-all"]') as HTMLButtonElement;
    // Groups start EXPANDED, so the bulk control reads "Collapse all" and children are visible.
    expect(expandAll.textContent?.trim()).toBe('Collapse all');
    let children = [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]')].filter(
      (r: Element) => r.getAttribute('data-role') === 'child',
    );
    expect(children.length).toBe(2);
    // Collapse all → children hidden, label flips to "Expand all".
    expandAll.click();
    cmp.detectChanges();
    children = [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]')].filter(
      (r: Element) => r.getAttribute('data-role') === 'child',
    );
    expect(children.length).toBe(0);
    expect(expandAll.textContent?.trim()).toBe('Expand all');
    // Expand all → children back, label flips to "Collapse all".
    expandAll.click();
    cmp.detectChanges();
    children = [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]')].filter(
      (r: Element) => r.getAttribute('data-role') === 'child',
    );
    expect(children.length).toBe(2);
    expect(expandAll.textContent?.trim()).toBe('Collapse all');
  });

  it('a lifecycle-state legend is present as an info affordance', async () => {
    const cmp = await mount();
    const legend = cmp.nativeElement.querySelector('[data-testid="lifecycle-legend"]') as HTMLElement;
    expect(legend).toBeTruthy();
    expect(legend.textContent).toContain('placed in a fired incident');
  });
});

describe('Alarms — REAL-TIME live updates (Feature 2)', () => {
  it('mounting starts the LivePollingService poll loop and shows a live indicator', async () => {
    const cmp = await mount();
    const live = cmp.debugElement.injector.get(LivePollingService);
    // The poll loop fired at least one immediate tick (lastUpdated set) and autoRefresh is on.
    expect(live.lastUpdated()).not.toBeNull();
    expect(live.autoRefresh()).toBe(true);
    expect(cmp.nativeElement.querySelector('[data-testid="live-indicator"]')).toBeTruthy();
    expect(cmp.nativeElement.querySelector('[data-testid="live-indicator"]')?.textContent?.trim()).toBe('live');
  });

  it('pause/resume toggles autoRefresh via the live-toggle control', async () => {
    const cmp = await mount();
    const live = cmp.debugElement.injector.get(LivePollingService);
    const toggle = cmp.nativeElement.querySelector('[data-testid="live-toggle"]') as HTMLButtonElement;
    toggle.click();
    cmp.detectChanges();
    expect(live.autoRefresh()).toBe(false);
    expect(cmp.nativeElement.querySelector('[data-testid="live-indicator"]')?.textContent?.trim()).toBe('paused');
    toggle.click();
    cmp.detectChanges();
    expect(live.autoRefresh()).toBe(true);
  });

  it('a poll tick refreshes the store rows (new incident group appears live)', async () => {
    const cmp = await mount();
    const live = cmp.debugElement.injector.get(LivePollingService);
    const store = cmp.debugElement.injector.get(AlarmsStore);
    const before = store.rows().length;
    // Simulate a poll tick delivering a NEW correlated incident + its RCA alarm.
    const newAlarms: AlarmSummary[] = [
      ...store.alarms(),
      { alarmId: 'live-rc', managedObjectId: 'mo-x', eventType: 'LOS', alarmType: 'LOS', perceivedSeverity: 'critical', raisedAt: '2026-06-01T13:00:00Z', lifecycleState: 'correlated', role: 'root-cause', incidentId: 'INC-99', trailIds: [] },
    ];
    live.alarmsSnapshot.set(newAlarms);
    live.lastUpdated.set(Date.now());
    await flush();
    cmp.detectChanges();
    expect(store.rows().length).toBe(before + 1);
    const groups = [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"][data-group="true"]')].map(
      (g: Element) => g.getAttribute('data-incident-id'),
    );
    expect(groups).toContain('INC-99');
    // A newly-arriving group is EXPANDED by default (its id was never collapsed).
    const newGroup = [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"][data-group="true"]')].find(
      (g: Element) => g.getAttribute('data-incident-id') === 'INC-99',
    ) as HTMLElement;
    const newToggle = newGroup.querySelector('[data-testid="alarm-expand"]') as HTMLButtonElement;
    expect(newToggle.getAttribute('aria-expanded')).toBe('true');
  });

  it('collapse state is PRESERVED across a poll tick (a collapsed group stays collapsed)', async () => {
    const cmp = await mount();
    const live = cmp.debugElement.injector.get(LivePollingService);
    const store = cmp.debugElement.injector.get(AlarmsStore);
    // Groups start expanded; collapse INC-12.
    (cmp.nativeElement.querySelector('[data-testid="alarm-expand"]') as HTMLButtonElement).click();
    cmp.detectChanges();
    expect(
      [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]')].filter(
        (r: Element) => r.getAttribute('data-role') === 'child',
      ).length,
    ).toBe(0);
    // A poll tick re-delivers the same data (a new object identity).
    live.alarmsSnapshot.set([...store.alarms()]);
    live.lastUpdated.set(Date.now());
    await flush();
    cmp.detectChanges();
    // The group is STILL collapsed after the tick (the collapsed set survives the refresh).
    const toggle = cmp.nativeElement.querySelector('[data-testid="alarm-expand"]') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(
      [...cmp.nativeElement.querySelectorAll('[data-testid="alarm-row"]')].filter(
        (r: Element) => r.getAttribute('data-role') === 'child',
      ).length,
    ).toBe(0);
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

describe('AlarmsStore — RCA accuracy wired to the ground-truth oracle (Change 1)', () => {
  function incident(rootCauseAlarmType: string, id: string): IncidentVM {
    return { incidentId: id, rootCauseAlarmId: `${id}-rc`, rootCauseAlarmType, childAlarmIds: [], confidence: 0.9, trailId: 'TR-1' };
  }
  // The RCA alarm resolved (by id) into the store's alarms() — carries the exact failed DEVICE the
  // per-incident join keys on. managedObjectId == the label's rootCauseManagedObjectId when they match.
  function rcaAlarm(id: string, managedObjectId: string, type: string): AlarmSummary {
    return { alarmId: `${id}-rc`, managedObjectId, eventType: type, alarmType: type, lifecycleState: 'correlated', role: 'root-cause', incidentId: id };
  }

  it('loadAll() FETCHES the simulator labels into the labels signal', async () => {
    const s = store();
    s.loadAll();
    await flush();
    // The mock /labels fixture returns two ground-truth labels (LOS, CardFail).
    expect(s.labels()).not.toBeNull();
    expect(s.labels()!.length).toBeGreaterThan(0);
  });

  it('computes the REAL fraction via the PER-INCIDENT EXACT device+type join when stats.rcaAccuracy is null', () => {
    const s = store();
    // No eval-mode value → the exact-device label join drives the metric.
    s.stats.set({ totalAlarmsProcessed: 100, totalIncidentsCreated: 3, rcaAccuracy: null } as StatsVM);
    s.labels.set([
      { scenarioId: 'sc-1', scenarioType: 'fiber-cut', rootCause: 'FiberSpan:1', rootCauseManagedObjectId: 'FiberSpan:1', rootCauseAlarmType: 'LOS', children: [] },
      { scenarioId: 'sc-2', scenarioType: 'card-fail', rootCause: 'Card:2', rootCauseManagedObjectId: 'Card:2', rootCauseAlarmType: 'CardFail', children: [] },
    ]);
    s.incidents.set([incident('LOS', 'INC-1'), incident('CardFail', 'INC-2')]);
    // Both incidents' RCA alarms resolve to a device+type that EXACTLY matches a label → 2/2 = 1.0.
    s.alarms.set([rcaAlarm('INC-1', 'FiberSpan:1', 'LOS'), rcaAlarm('INC-2', 'Card:2', 'CardFail')]);
    expect(s.rcaAccuracy().value).toBe(1);
    expect(s.rcaAccuracy().source).toBe('client-side-join');
  });

  it('a matching device counts; a non-matching-device incident is excluded from the denominator (exact join)', () => {
    const s = store();
    s.stats.set({ totalAlarmsProcessed: 100, totalIncidentsCreated: 3, rcaAccuracy: null } as StatsVM);
    s.labels.set([
      { scenarioId: 'sc-1', scenarioType: 'fiber-cut', rootCause: 'FiberSpan:1', rootCauseManagedObjectId: 'FiberSpan:1', rootCauseAlarmType: 'LOS', children: [] },
    ]);
    // INC-1's RCA device matches the label → counts. INC-2's device is NOT labelled → excluded from
    // BOTH numerator and denominator (denominator = incidents a label covers = 1). → 1/1 = 1.0.
    s.incidents.set([incident('LOS', 'INC-1'), incident('LOS', 'INC-2')]);
    s.alarms.set([rcaAlarm('INC-1', 'FiberSpan:1', 'LOS'), rcaAlarm('INC-2', 'FiberSpan:999', 'LOS')]);
    expect(s.rcaAccuracy().value).toBe(1);
    expect(s.rcaAccuracy().source).toBe('client-side-join');
  });

  it('is N/A when labels are empty (graceful fallback — no oracle)', () => {
    const s = store();
    s.stats.set({ totalAlarmsProcessed: 100, totalIncidentsCreated: 3, rcaAccuracy: null } as StatsVM);
    s.labels.set([]);
    s.incidents.set([incident('LOS', 'INC-1')]);
    s.alarms.set([rcaAlarm('INC-1', 'FiberSpan:1', 'LOS')]);
    expect(s.rcaAccuracy().value).toBeNull();
    expect(s.rcaAccuracy().source).toBe('na');
  });

  it('the kpi-rca card renders a real percent (not N/A) when labels resolve', async () => {
    const cmp = await mount();
    const card = cmp.nativeElement.querySelector('[data-testid="kpi-rca"]') as HTMLElement;
    // The mock stats carry rcaAccuracy=0.86 (eval path) → the card shows a percent, never N/A.
    expect(card.querySelector('.kpi-value')?.textContent?.trim()).not.toBe('N/A');
    expect(card.querySelector('.kpi-value')?.textContent?.trim()).toMatch(/%$/);
    // The aria/tooltip honestly describes the metric: an exact match to the simulator ground-truth label.
    expect(card.getAttribute('aria-label')).toContain('ground-truth label');
  });
});

describe('AlarmsStore — Dedup-reduction card (Change 2)', () => {
  function summary(alarmsEmitted: number): SynthSummaryModel {
    return {
      runId: 'r1', status: 'completed', alarmsEmitted, alignedFraction: 0.7, enrichmentSafeCount: 122,
      shortfallCascades: 0, enrichmentConflictPatterns: [], failureReason: null,
      startedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:01:00Z',
    };
  }

  it('resolves emitted → kept + the deduped fraction (200 emitted, 187 kept)', () => {
    const s = store();
    s.synthSummary.set(summary(200));
    s.alarmManagerTotal.set(187);
    const d = s.dedupReduction();
    expect(d.emitted).toBe(200);
    expect(d.kept).toBe(187);
    expect(d.deduped).toBe(13);
    expect(d.fraction).toBeCloseTo(13 / 200); // 6.5%
  });

  it('emitted is null (graceful "—") when there is no completed run this session', () => {
    const s = store();
    s.synthSummary.set(null);
    s.alarmManagerTotal.set(187);
    const d = s.dedupReduction();
    expect(d.emitted).toBeNull();
    expect(d.kept).toBe(187);
    expect(d.deduped).toBeNull();
    expect(d.fraction).toBeNull();
  });

  it('guards divide-by-zero when emitted is 0 (no bogus ratio)', () => {
    const s = store();
    s.synthSummary.set(summary(0));
    s.alarmManagerTotal.set(187);
    const d = s.dedupReduction();
    expect(d.fraction).toBeNull();
    expect(d.deduped).toBeNull();
  });

  it('guards kept > emitted → NO negative % / deduped (kept spans prior runs; not a single-run basis)', () => {
    const s = store();
    // Latest run emitted 50, but the Alarm Manager total (300) includes PRIOR runs → kept > emitted.
    s.synthSummary.set(summary(50));
    s.alarmManagerTotal.set(300);
    const d = s.dedupReduction();
    expect(d.fraction).toBeNull(); // no false/negative %
    expect(d.deduped).toBeNull();
    expect(d.kept).toBe(300); // kept still surfaced so the card shows "300 kept", not a bogus ratio
    expect(d.emitted).toBe(50);
  });

  it('the kpi-dedup card renders "200 → 187" + the % deduped', async () => {
    const cmp = await mount();
    const s = cmp.debugElement.injector.get(AlarmsStore);
    s.synthSummary.set(summary(200));
    s.alarmManagerTotal.set(187);
    cmp.detectChanges();
    const card = cmp.nativeElement.querySelector('[data-testid="kpi-dedup"]') as HTMLElement;
    expect(card.querySelector('[data-testid="kpi-dedup-flow"]')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('200 → 187');
    expect(card.querySelector('[data-testid="kpi-dedup-pct"]')?.textContent?.trim()).toContain('deduped');
    expect(card.getAttribute('aria-label')).toContain('emitted');
  });

  it('the kpi-dedup card shows the kept count alone when no run summary exists', async () => {
    const cmp = await mount();
    const s = cmp.debugElement.injector.get(AlarmsStore);
    s.synthSummary.set(null);
    s.alarmManagerTotal.set(187);
    cmp.detectChanges();
    const card = cmp.nativeElement.querySelector('[data-testid="kpi-dedup"]') as HTMLElement;
    expect(card.querySelector('[data-testid="kpi-dedup-flow"]')?.textContent?.trim()).toBe('187 kept');
    expect(card.querySelector('[data-testid="kpi-dedup-pct"]')).toBeNull();
  });
});
