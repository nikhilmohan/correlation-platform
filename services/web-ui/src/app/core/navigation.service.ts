import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';

/**
 * Central builder for every cross-navigation deep link (spec task 5). All entity pages carry
 * the ID in the URL (route param or query param) so links are shareable/bookmarkable.
 */
@Injectable({ providedIn: 'root' })
export class NavigationService {
  private readonly router = inject(Router);

  toIncident(incidentId: string): Promise<boolean> {
    return this.router.navigate(['/incidents', incidentId]);
  }

  /**
   * Topology now lives ON the dashboard (no separate `/topology` page). A trail deep link therefore
   * targets `/dashboard?trailId=<id>` — the dashboard-embedded geo-site-map reads the query param and
   * highlights that trail (AC 21/24 preserved on the dashboard route).
   */
  toTrail(trailId: string): Promise<boolean> {
    return this.router.navigate(['/dashboard'], { queryParams: { trailId } });
  }

  /**
   * Legacy site-graph fallback (used only when the geo-site-map is rendered standalone with no host
   * listener). The site drill-in is normally handled IN-PLACE on the dashboard via the component
   * output; there is no `/topology/:siteId` page anymore, so this points at the dashboard.
   */
  toSiteGraph(_siteId: string): Promise<boolean> {
    return this.router.navigate(['/dashboard']);
  }

  toAlarmInStreaming(alarmId: string): Promise<boolean> {
    return this.router.navigate(['/alarms'], { queryParams: { alarmId } });
  }

  toStreamingForSite(siteId: string): Promise<boolean> {
    return this.router.navigate(['/alarms'], { queryParams: { siteId } });
  }

  /** Live alarm state now lives on the unified /alarms view (Streaming + Stats merged, Part 3). */
  toStats(): Promise<boolean> {
    return this.router.navigate(['/alarms']);
  }

  toAlarms(): Promise<boolean> {
    return this.router.navigate(['/alarms']);
  }

  toNoise(): Promise<boolean> {
    return this.router.navigate(['/noise']);
  }

  toPatterns(): Promise<boolean> {
    return this.router.navigate(['/patterns']);
  }

  toChatter(source?: string): Promise<boolean> {
    return this.router.navigate(['/chatter'], source ? { queryParams: { source } } : {});
  }

  toConfig(): Promise<boolean> {
    return this.router.navigate(['/config']);
  }

  toStreaming(): Promise<boolean> {
    return this.router.navigate(['/alarms']);
  }

  /** Topology home is the dashboard now (no separate `/topology` route). */
  toTopology(): Promise<boolean> {
    return this.router.navigate(['/dashboard']);
  }
}
