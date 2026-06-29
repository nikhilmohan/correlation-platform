import { TestBed, ComponentFixture } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { SiteGraphComponent } from './site-graph.component';
import { TopologyStore } from './topology.store';

/**
 * Component-level (DOM) tests for the "first-glance" topology redesign:
 *  - CHANGE 2: the floating on-canvas TRAIL SELECTOR (toggle → dropdown listbox; the
 *    data-testid="trail-cluster" buttons live inside it and still drive selectTrail; a Clear item
 *    drives clearTrail).
 *  - CHANGE 3: the device DETAIL DRAWER slides in only when a node/edge is selected, the ✕/Esc
 *    affordances clear the selection.
 */

async function mountSiteGraph(): Promise<ComponentFixture<SiteGraphComponent>> {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(SiteGraphComponent);
  fixture.componentRef.setInput('siteId', 'Site:LON');
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('CHANGE 2 — floating trail selector', () => {
  it('renders a keyboard-accessible trail-selector toggle (aria-expanded, aria-haspopup) collapsed by default', async () => {
    const fixture = await mountSiteGraph();
    const el: HTMLElement = fixture.nativeElement;
    const toggle = el.querySelector('[data-testid="trail-selector"] .trail-toggle') as HTMLButtonElement;
    expect(toggle).not.toBeNull();
    expect(toggle.tagName).toBe('BUTTON');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.getAttribute('aria-haspopup')).toBe('listbox');
    // The menu (a listbox) exists in the DOM and is hidden while collapsed.
    const menu = el.querySelector('[data-testid="trail-menu"]') as HTMLElement;
    expect(menu).not.toBeNull();
    expect(menu.getAttribute('role')).toBe('listbox');
    expect(menu.hasAttribute('hidden')).toBe(true);
  });

  it('the trail-cluster buttons stay in the DOM (inside the menu) even while the menu is collapsed', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const clusters = fixture.nativeElement.querySelectorAll('[data-testid="trail-cluster"]');
    expect(clusters.length).toBe(store.trails().length);
    expect(store.trails().length).toBeGreaterThanOrEqual(1);
  });

  it('opening the menu flips aria-expanded and reveals the listbox', async () => {
    const fixture = await mountSiteGraph();
    const el: HTMLElement = fixture.nativeElement;
    const toggle = el.querySelector('.trail-toggle') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect((el.querySelector('[data-testid="trail-menu"]') as HTMLElement).hasAttribute('hidden')).toBe(false);
  });

  it('selecting a trail in the menu calls selectTrail and closes the menu', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const spy = vi.spyOn(store, 'selectTrail');
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('.trail-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    const firstTrail = el.querySelector('[data-testid="trail-cluster"]') as HTMLButtonElement;
    firstTrail.click();
    fixture.detectChanges();
    expect(spy).toHaveBeenCalledWith(store.trails()[0].trailId);
    // Menu auto-closes after a selection.
    expect((el.querySelector('.trail-toggle') as HTMLElement).getAttribute('aria-expanded')).toBe('false');
  });

  it('the Clear item appears once a trail is selected and calls clearTrail', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.trail-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    const clearBtn = fixture.nativeElement.querySelector('[data-testid="clear-trail"]') as HTMLButtonElement;
    expect(clearBtn).not.toBeNull();
    const spy = vi.spyOn(store, 'clearTrail');
    clearBtn.click();
    expect(spy).toHaveBeenCalled();
  });
});

describe('topology-v2 CHANGE 2c — explode-trail button', () => {
  it('renders an explode-trail TOGGLE in the trail detail that calls store.toggleFullPath', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const explode = fixture.nativeElement.querySelector('[data-testid="explode-trail"]') as HTMLButtonElement;
    expect(explode).not.toBeNull();
    expect(explode.tagName).toBe('BUTTON');
    // View 3 (not yet exploded): the toggle offers to SHOW the full path.
    expect(explode.getAttribute('aria-label')).toBe('Show full path — reveal the cross-site trail members');
    expect(explode.getAttribute('aria-pressed')).toBe('false');

    const spy = vi.spyOn(store, 'toggleFullPath');
    explode.click();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('selecting a trail from the menu does NOT grow the graph (highlight-only — no auto-explode)', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const before = store.derivedNodes().length;
    store.selectTrail('TR-7'); // mock TR-7 spans LON+FRA
    await flush();
    fixture.detectChanges();
    // No off-site member pulled in by a plain select — the node count is unchanged.
    expect(store.derivedNodes().length).toBe(before);
    expect(store.trailMemberIds().size).toBeGreaterThan(1); // but the full member set IS highlighted
  });
});

describe('topology-v2 CHANGE 1 — external-link cue hint', () => {
  it('renders the self-explanatory "extends to other sites" hint near the graph', async () => {
    const fixture = await mountSiteGraph();
    const hint = fixture.nativeElement.querySelector('[data-testid="external-link-hint"]') as HTMLElement;
    expect(hint).not.toBeNull();
    expect(hint.textContent).toMatch(/extends to other sites/i);
  });
});

describe('CHANGE 3 — device detail slide-in drawer', () => {
  it('the drawer is closed (not .open) when nothing is selected', async () => {
    const fixture = await mountSiteGraph();
    const drawer = fixture.nativeElement.querySelector('[data-testid="detail-drawer"]') as HTMLElement;
    expect(drawer).not.toBeNull();
    expect(drawer.classList.contains('open')).toBe(false);
    expect(drawer.getAttribute('aria-hidden')).toBe('true');
    // The reused detail panel stays in the DOM as the drawer body.
    expect(drawer.querySelector('[data-testid="detail-panel"]')).not.toBeNull();
  });

  it('selecting a device opens the drawer (.open) and revealing the close affordance', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectNode(store.derivedNodes()[0].managedObjectId);
    await flush();
    fixture.detectChanges();
    const drawer = fixture.nativeElement.querySelector('[data-testid="detail-drawer"]') as HTMLElement;
    expect(drawer.classList.contains('open')).toBe(true);
    expect(drawer.getAttribute('aria-hidden')).toBe('false');
    const close = fixture.nativeElement.querySelector('[data-testid="close-detail"]') as HTMLButtonElement;
    expect(close).not.toBeNull();
    expect(close.getAttribute('aria-label')).toBeTruthy();
  });

  it('the ✕ button clears the selection and closes the drawer', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectNode(store.derivedNodes()[0].managedObjectId);
    await flush();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[data-testid="close-detail"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(store.selectedObjectId()).toBeNull();
    const drawer = fixture.nativeElement.querySelector('[data-testid="detail-drawer"]') as HTMLElement;
    expect(drawer.classList.contains('open')).toBe(false);
  });

  it('Esc closes the drawer when a device is selected', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectNode(store.derivedNodes()[0].managedObjectId);
    await flush();
    fixture.detectChanges();
    fixture.componentInstance.onEscape();
    fixture.detectChanges();
    expect(store.selectedObjectId()).toBeNull();
    expect(
      (fixture.nativeElement.querySelector('[data-testid="detail-drawer"]') as HTMLElement).classList.contains('open'),
    ).toBe(false);
  });
});
