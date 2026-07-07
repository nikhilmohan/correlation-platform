import { ChangeDetectionStrategy, Component } from '@angular/core';
import { NoiseViewComponent } from '../noise/noise-view.component';
import { ChatterManagementComponent } from '../chatter/chatter-management.component';

/**
 * "Noise filtering" sub-tab of the ML page (Change 1, sub-tab 2). Both noise-suppression
 * concerns live here as one clean two-section layout: the Noise heatmaps (Noise stats) on top,
 * Chatter management below. Composes the EXISTING feature components unchanged — no rewrites.
 */
@Component({
  selector: 'app-noise-filtering',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NoiseViewComponent, ChatterManagementComponent],
  template: `
    <div class="noise-filtering" data-testid="ml-noise-filtering">
      <section class="nf-section" aria-label="Noise statistics">
        <app-noise-view />
      </section>
      <hr class="nf-divider" aria-hidden="true" />
      <section class="nf-section" aria-label="Chatter management">
        <app-chatter-management />
      </section>
    </div>
  `,
  styles: [
    `
      .noise-filtering {
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
      }
      .nf-divider {
        border: 0;
        border-top: 1px solid var(--border);
        margin: 0;
      }
    `,
  ],
})
export class NoiseFilteringComponent {}
