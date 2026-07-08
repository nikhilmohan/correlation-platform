import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ModelParamsFormComponent } from './model-params-form.component';
import { KnowledgeClient } from '../api/knowledge.client';
import { testProviders, flush } from '../../test-utils';

function create() {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(ModelParamsFormComponent);
  fixture.detectChanges();
  return fixture;
}

describe('Config module (P2) — Knowledge model-params', () => {
  it('AC 40 — loads + displays current model params (DBSCAN, session-window gap, min-support) from Knowledge', async () => {
    const fixture = create();
    await flush();
    fixture.detectChanges();
    const cmp = fixture.componentInstance;
    expect(cmp.record()?.recordId).toBe('noise-filter');
    const keys = cmp.params().map((p) => p.key);
    expect(keys).toContain('dbscan.epsilon');
    expect(keys).toContain('dbscan.minSamples');
    expect(keys).toContain('window.sizeSeconds');
    // rendered into the form
    expect(fixture.nativeElement.querySelector('[data-testid="param-dbscan.epsilon"]')).toBeTruthy();
  });

  it('Change 1 — params render grouped by ML feature (dbscan.* under Noise filtering (DBSCAN))', async () => {
    const fixture = create();
    await flush();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    // The DBSCAN group exists and contains the dbscan.* params.
    const noiseGroup = el.querySelector('[data-testid="param-group-noise"]') as HTMLElement;
    expect(noiseGroup).toBeTruthy();
    expect(noiseGroup.querySelector('legend')?.textContent).toMatch(/DBSCAN/i);
    expect(noiseGroup.querySelector('[data-testid="param-dbscan.epsilon"]')).toBeTruthy();
    expect(noiseGroup.querySelector('[data-testid="param-dbscan.minSamples"]')).toBeTruthy();
    // The session-window param sits under Correlation / session window.
    const corrGroup = el.querySelector('[data-testid="param-group-correlation"]') as HTMLElement;
    expect(corrGroup).toBeTruthy();
    expect(corrGroup.querySelector('[data-testid="param-window.sizeSeconds"]')).toBeTruthy();
    // Every param renders exactly once across the groups (no dupes, none lost).
    const cmp = fixture.componentInstance;
    for (const p of cmp.params()) {
      expect(el.querySelectorAll(`[data-testid="param-${p.key}"]`).length).toBe(1);
    }
    // The groups computed covers every param.
    const grouped = cmp.groups().reduce((n, g) => n + g.members.length, 0);
    expect(grouped).toBe(cmp.params().length);
  });

  it('AC 41 — a valid edit submits to Knowledge and confirms the new version to the operator', async () => {
    const fixture = create();
    await flush();
    fixture.detectChanges();
    const cmp = fixture.componentInstance;
    // edit dbscan.epsilon (min 0, max 100) to a valid value
    const ctrl = cmp.form.controls.params.controls.find((g) => g.controls.key.value === 'dbscan.epsilon')!;
    ctrl.controls.value.setValue(0.8);
    cmp.submit();
    await flush();
    fixture.detectChanges();
    // mock PUT returns version v4
    expect(cmp.record()?.version).toBe('v4');
    expect(cmp.saveStatus()).toContain('v4');
    const status = fixture.nativeElement.querySelector('[data-testid="save-status"]');
    expect(status).toBeTruthy();
    expect(status?.textContent).toContain('v4');
  });

  it('AC 42 — an invalid (out-of-bounds) value blocks submit, shows a validation error, and makes NO API call', async () => {
    const fixture = create();
    await flush();
    fixture.detectChanges();
    const cmp = fixture.componentInstance;
    let putCalled = false;
    const knowledge = TestBed.inject(KnowledgeClient);
    const original = knowledge.updateModelParams.bind(knowledge);
    knowledge.updateModelParams = ((...args: Parameters<typeof original>) => {
      putCalled = true;
      return original(...args);
    }) as typeof knowledge.updateModelParams;

    // dbscan.minSamples bound is min 1 max 1000 — set out of bounds
    const ctrl = cmp.form.controls.params.controls.find((g) => g.controls.key.value === 'dbscan.minSamples')!;
    ctrl.controls.value.setValue(9999);
    ctrl.controls.value.markAsTouched();
    cmp.submit();
    await flush();
    expect(cmp.form.invalid).toBe(true);
    expect(putCalled).toBe(false);
    expect(cmp.saveStatus()).toBe('');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="error-dbscan.minSamples"]')).toBeTruthy();
  });
});
