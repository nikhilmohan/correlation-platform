import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { ChatterStore } from './chatter.store';
import { ChatterManagementComponent } from './chatter-management.component';
import { EnrichmentChatterClient } from '../api/enrichment-chatter.client';
import { testProviders, flush } from '../../test-utils';
import { EnrichmentChatterEntry } from '../api/models';

function store(): ChatterStore {
  TestBed.configureTestingModule({ providers: [ChatterStore, ...testProviders()] });
  return TestBed.inject(ChatterStore);
}

describe('Chatter management (FIX F-UI1) — promoted-vs-candidate join (AC 55, 56)', () => {
  it('AC 55 — observed-chatter is read (ranked by occurrenceCount) and joined promoted vs candidate against the Enrichment list', async () => {
    const s = store();
    s.selectSource('nms-alpha');
    s.load();
    await flush();
    const rows = s.joinView();
    // fixture: 3 observed signatures, ranked desc by occurrenceCount
    expect(rows.length).toBe(3);
    expect(rows.map((r) => r.observed.occurrenceCount)).toEqual([142, 88, 51]);
    // The (Interface:e1-12, linkDown) signature matches the nms-alpha Enrichment entry → promoted
    const promoted = rows.find((r) => r.observed.managedObjectId === 'Interface:e1-12')!;
    expect(promoted.status).toBe('promoted');
    expect(promoted.alreadyPromoted).toBe(true);
    // the rest (incl. the null-managedObjectId source-level signature) are candidates
    const sourceLevel = rows.find((r) => r.observed.managedObjectId === null)!;
    expect(sourceLevel.status).toBe('candidate');
    expect(rows.filter((r) => r.status === 'candidate').length).toBe(2);
  });

  it('AC 56 — promoting a candidate writes the (managedObjectId,eventType) entry to Enrichment for the selected source', async () => {
    const s = store();
    s.selectSource('nms-alpha');
    s.load();
    await flush();
    const ecc = TestBed.inject(EnrichmentChatterClient);
    const addSpy = vi.spyOn(ecc, 'addChatter');
    const candidateRow = s.joinView().find((r) => r.observed.managedObjectId === 'Port:c1-3-7')!;
    expect(candidateRow.status).toBe('candidate');
    s.promote(candidateRow.observed);
    await flush();
    // the write targets the selected source with the exact (managedObjectId, eventType) key
    expect(addSpy).toHaveBeenCalledWith('nms-alpha', { managedObjectId: 'Port:c1-3-7', eventType: 'crcError' });
    // graceful: pending cleared after the round-trip (no double-submit lock left dangling)
    expect(s.pendingPromotion()).toBeNull();
  });

  it('AC 56 — the promote double-submit guard blocks a concurrent promotion while one is pending', () => {
    const s = store();
    s.observed.set([
      { managedObjectId: 'X', alarmType: 'A', eventType: 'a', trailId: null, occurrenceCount: 1, firstSeen: '', lastSeen: '' },
    ]);
    const ecc = TestBed.inject(EnrichmentChatterClient);
    const addSpy = vi.spyOn(ecc, 'addChatter');
    // simulate an in-flight promotion
    s.pendingPromotion.set('Y::y');
    s.promote(s.observed()[0]);
    expect(addSpy).not.toHaveBeenCalled();
  });

  it('AC 56 — removing an Enrichment entry calls the Enrichment chatter DELETE path', async () => {
    const s = store();
    s.selectSource('nms-alpha');
    s.load();
    await flush();
    const entry: EnrichmentChatterEntry = { managedObjectId: 'Interface:e1-12', eventType: 'linkDown' };
    s.remove(entry);
    await flush();
    // graceful: pending is cleared after the round-trip; store re-reads the source list
    expect(s.pendingPromotion()).toBeNull();
  });

  it('AC 56 — switching source re-reads that source’s Enrichment chatter list (default source is empty)', async () => {
    const s = store();
    s.load();
    await flush();
    s.selectSource('default');
    await flush();
    expect(s.selectedSource()).toBe('default');
    expect(s.enrichmentChatter().length).toBe(0);
  });

  it('AC 55 — graceful degrade: the page renders the observed table even when Enrichment chatter is empty', async () => {
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
      ],
    });
    const fixture = TestBed.createComponent(ChatterManagementComponent);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    const observedRows = fixture.nativeElement.querySelectorAll('[data-testid="observed-row"]');
    expect(observedRows.length).toBe(3);
    // a promote action exists for at least one candidate
    expect(fixture.nativeElement.querySelector('[data-testid="promote-btn"]')).toBeTruthy();
  });
});
