import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, Routes, convertToParamMap } from '@angular/router';
import { Component } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { NavigationService } from './navigation.service';
import { TopologyStore } from '../topology/topology.store';
import { GeoSiteMapComponent } from '../topology/geo-site-map.component';
import { testProviders, flush } from '../../test-utils';

@Component({ template: '' })
class StubComponent {}

const ROUTES: Routes = [
  { path: 'incidents/:incidentId', component: StubComponent },
  { path: 'dashboard', component: StubComponent },
];

describe('Cross-navigation & deep-linking', () => {
  it('AC 22 — activating an incident link navigates to /incidents/:incidentId with the correct id', async () => {
    TestBed.configureTestingModule({ providers: [...testProviders(ROUTES)] });
    const router = TestBed.inject(Router);
    const nav = TestBed.inject(NavigationService);
    const spy = vi.spyOn(router, 'navigate');
    const ok = await nav.toIncident('INC-12');
    expect(spy).toHaveBeenCalledWith(['/incidents', 'INC-12']);
    expect(ok).toBe(true);
    expect(router.url).toBe('/incidents/INC-12');
  });

  it('AC 21 — pattern → topology trail deep link is built as /dashboard?trailId=<id> (topology is on the dashboard now)', async () => {
    TestBed.configureTestingModule({ providers: [...testProviders(ROUTES)] });
    const router = TestBed.inject(Router);
    const nav = TestBed.inject(NavigationService);
    const spy = vi.spyOn(router, 'navigate');
    const ok = await nav.toTrail('TR-7');
    expect(spy).toHaveBeenCalledWith(['/dashboard'], { queryParams: { trailId: 'TR-7' } });
    expect(ok).toBe(true);
    expect(router.url).toBe('/dashboard?trailId=TR-7');
  });

  it('AC 24 — direct navigation to ?trailId=<id> activates that trail (query param read on init)', async () => {
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ trailId: 'TR-8' }) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(GeoSiteMapComponent);
    fixture.detectChanges();
    await flush();
    const store = TestBed.inject(TopologyStore);
    // the ?trailId= deep link activates the trail and highlights it
    expect(store.activeTrailId()).toBe('TR-8');
    expect(store.highlightedTrailIds().has('TR-8')).toBe(true);
  });
});
