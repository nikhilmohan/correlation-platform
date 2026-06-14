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
    expect(keys).toContain('prefixspan.minSupport');
    // rendered into the form
    expect(fixture.nativeElement.querySelector('[data-testid="param-dbscan.epsilon"]')).toBeTruthy();
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

    // prefixspan.minSupport bound is min 0 max 1 — set out of bounds
    const ctrl = cmp.form.controls.params.controls.find((g) => g.controls.key.value === 'prefixspan.minSupport')!;
    ctrl.controls.value.setValue(5);
    ctrl.controls.value.markAsTouched();
    cmp.submit();
    await flush();
    expect(cmp.form.invalid).toBe(true);
    expect(putCalled).toBe(false);
    expect(cmp.saveStatus()).toBe('');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="error-prefixspan.minSupport"]')).toBeTruthy();
  });
});
