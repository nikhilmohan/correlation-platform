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

  toTrail(trailId: string): Promise<boolean> {
    return this.router.navigate(['/topology'], { queryParams: { trailId } });
  }

  toSiteGraph(siteId: string): Promise<boolean> {
    return this.router.navigate(['/topology', siteId]);
  }

  toAlarmInStreaming(alarmId: string): Promise<boolean> {
    return this.router.navigate(['/streaming'], { queryParams: { alarmId } });
  }

  toStreamingForSite(siteId: string): Promise<boolean> {
    return this.router.navigate(['/streaming'], { queryParams: { siteId } });
  }

  toStats(): Promise<boolean> {
    return this.router.navigate(['/stats']);
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
    return this.router.navigate(['/streaming']);
  }

  toTopology(): Promise<boolean> {
    return this.router.navigate(['/topology']);
  }
}
