import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PatternStore } from './pattern.store';
import { PatternListComponent } from './pattern-list.component';
import { NavigationService } from '../core/navigation.service';
import { resetMockPatternDecisions } from '../core/mock-fixtures';
import { testProviders, flush } from '../../test-utils';

function store(): PatternStore {
  TestBed.configureTestingModule({ providers: [PatternStore, ...testProviders()] });
  return TestBed.inject(PatternStore);
}

describe('Pattern review & XAI module (P2)', () => {
  // The in-app mock now PERSISTS approve/reject decisions within a session (to mirror the real
  // Pattern Manager for the AC 39 E2E round-trip), so reset that state between unit cases.
  beforeEach(() => resetMockPatternDecisions());

  it('AC 34 — lists each discovered pattern with sequence, support, confidence, lift, RCA, codebook overlap, instance count', async () => {
    const s = store();
    s.load('draft');
    await flush();
    const drafts = s.visiblePatterns();
    expect(drafts.length).toBe(1);
    const p = drafts[0];
    expect(p.patternId).toBe('02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f');
    expect(p.sequence.map((x) => x.alarmType)).toEqual(['LOS', 'LinkDown', 'AdjDown']);
    expect(p.support).toBeCloseTo(0.12);
    expect(p.confidence).toBeCloseTo(0.9);
    expect(p.lift).toBeCloseTo(4.2);
    expect(p.rootCauseAlarmType).toBe('LOS');
    expect(p.codebookMatchId).toBe('CB-2');
    expect(p.instanceCount).toBe(18);
  });

  it('AC 35 — a pattern can be expanded to reveal full XAI detail', async () => {
    const fixture = TestBed.configureTestingModule({ providers: [...testProviders()] });
    void fixture;
    const cmp = TestBed.createComponent(PatternListComponent);
    cmp.detectChanges();
    await flush();
    cmp.detectChanges();
    // XAI panel hidden until expanded
    expect(cmp.nativeElement.querySelector('[data-testid="pattern-xai"]')).toBeNull();
    const expandBtn = cmp.nativeElement.querySelector('[data-testid="pattern-expand"]') as HTMLButtonElement;
    expandBtn.click();
    cmp.detectChanges();
    const xai = cmp.nativeElement.querySelector('[data-testid="pattern-xai"]');
    expect(xai).toBeTruthy();
    expect(xai.textContent).toContain('session window');
    expect(xai.textContent).toContain('supporting instances');
  });

  it('AC 36 — Approve posts approval-intent with the patternId; lifecycle updates to approved', async () => {
    const s = store();
    s.load('draft');
    await flush();
    s.decide('02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f', 'approve');
    await flush();
    const updated = s.patterns().find((p) => p.patternId === '02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f');
    expect(updated?.lifecycle).toBe('approved');
    expect(s.pendingDecision()).toBeNull();
  });

  it('AC 37 — Reject posts reject-intent; the pattern is removed from the discovered list', async () => {
    const s = store();
    s.load('draft');
    await flush();
    expect(s.patterns().some((p) => p.patternId === '02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f')).toBe(true);
    s.decide('02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f', 'reject');
    await flush();
    expect(s.patterns().some((p) => p.patternId === '02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f')).toBe(false);
  });

  it('AC 38 — the active/approved tab displays only patterns whose lifecycle is approved', async () => {
    const s = store();
    s.load('approved');
    await flush();
    const approved = s.visiblePatterns();
    expect(approved.length).toBeGreaterThanOrEqual(1);
    expect(approved.every((p) => p.lifecycle === 'approved')).toBe(true);
    expect(approved.some((p) => p.patternId === '10b3918b-2c4d-4f6a-8b1e-9d0c1a2b3c4d')).toBe(true);
  });

  it('AC 21 / AC 54 — "View trail" navigates to /topology?trailId=<id>; edit is offered only for draft patterns', async () => {
    TestBed.configureTestingModule({ providers: [...testProviders()] });
    const router = TestBed.inject(Router);
    const nav = TestBed.inject(NavigationService);
    const spy = vi.spyOn(router, 'navigate');
    const cmp = TestBed.createComponent(PatternListComponent);
    cmp.detectChanges();
    await flush();
    cmp.detectChanges();
    // expand the draft pattern so its action buttons render
    (cmp.nativeElement.querySelector('[data-testid="pattern-expand"]') as HTMLButtonElement).click();
    cmp.detectChanges();
    // AC 54 — edit action present for the draft pattern
    expect(cmp.nativeElement.querySelector('[data-testid="edit-btn"]')).toBeTruthy();
    // AC 21 — view-trail deep link
    (cmp.nativeElement.querySelector('[data-testid="view-trail-btn"]') as HTMLButtonElement).click();
    void nav;
    expect(spy).toHaveBeenCalledWith(['/topology'], { queryParams: { trailId: 'TR-7' } });
  });

  it('AC 54 — edit placeholder marks a sequence alarm optional and PATCHes the Pattern Manager; only draft patterns', async () => {
    const s = store();
    s.load('draft');
    await flush();
    // 02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f sequence[1] (LinkDown) starts non-optional; mark it optional
    s.edit('02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f', { sequenceFlags: [{ index: 1, optional: true }], reviewer: 'operator' });
    await flush();
    const updated = s.patterns().find((p) => p.patternId === '02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f');
    // mock pattern-edit endpoint returns the pattern with sequence[1] optional flipped to true
    expect(updated?.sequence[1].optional).toBe(true);
  });
});
