import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { NoiseStore } from './noise.store';

/**
 * Graphical Noise-filter view (Part 4). Its OWN first-level tab/route (`/noise`) — replaces the dense
 * Stats "Noise run-stats" table with an intuitive, dependency-light visualisation:
 *   - a prominent AGGREGATE headline (total alarms in → kept, overall reduction) with a kept-vs-
 *     dropped proportion bar + a Noise-ratio and a Storm-reduction gauge, then
 *   - PER-RUN kept/dropped proportion bars.
 * Pure CSS/SVG (no chart lib) — theme-aware via the app CSS custom properties. Non-colour-only:
 * every gauge/bar carries a text value + an aria-label so it is screen-reader legible.
 */
@Component({
  selector: 'app-noise-view',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [NoiseStore],
  imports: [DecimalPipe, PercentPipe, RouterLink],
  template: `
    <h1>Noise filter</h1>
    <p class="hint">
      How effectively the noise filter collapses alarm storms into clusters and drops chatter.
      <a routerLink="/chatter">Review &amp; promote observed chatter →</a>
    </p>

    <label class="trail-filter">
      Filter trailId
      <input type="text" data-testid="trail-filter" (change)="onTrailFilter($event)" />
    </label>

    @if (store.aggregate().runs) {
      <!-- AGGREGATE HEADLINE -->
      <section class="card headline" aria-labelledby="agg-h" data-testid="noise-aggregate">
        <h2 id="agg-h" class="visually-hidden">Aggregate noise-filter effectiveness</h2>
        <div class="agg-stats">
          <div class="agg-stat">
            <span class="agg-label">Alarms in</span>
            <span class="agg-value" data-testid="agg-in">{{ store.aggregate().alarmsIn }}</span>
          </div>
          <div class="agg-stat">
            <span class="agg-label">Kept</span>
            <span class="agg-value kept" data-testid="agg-kept">{{ store.aggregate().alarmsKept }}</span>
          </div>
          <div class="agg-stat">
            <span class="agg-label">Dropped as noise</span>
            <span class="agg-value dropped" data-testid="agg-dropped">{{ store.aggregate().alarmsDropped }}</span>
          </div>
          <div class="agg-stat">
            <span class="agg-label">Overall reduction</span>
            <span class="agg-value" data-testid="agg-reduction">
              @if (store.aggregate().stormReduction !== null) {
                {{ store.aggregate().stormReduction! | number: '1.1-1' }} : 1
              } @else {
                N/A
              }
            </span>
          </div>
        </div>

        <!-- Kept-vs-Dropped proportion bar (aggregate). -->
        @if (store.aggregate().keptRatio !== null) {
          <div
            class="prop-bar"
            data-testid="agg-prop-bar"
            role="img"
            [attr.aria-label]="
              'Aggregate: ' +
              pct(store.aggregate().keptRatio) + ' of alarms kept, ' +
              pct(store.aggregate().noiseRatio) + ' dropped as noise'
            "
          >
            <span class="seg seg-kept" [style.width.%]="store.aggregate().keptRatio! * 100">
              <span class="seg-text">kept {{ store.aggregate().keptRatio! | percent: '1.0-0' }}</span>
            </span>
            <span class="seg seg-dropped" [style.width.%]="store.aggregate().noiseRatio! * 100">
              <span class="seg-text">noise {{ store.aggregate().noiseRatio! | percent: '1.0-0' }}</span>
            </span>
          </div>
        }

        <!-- Noise-ratio + Storm-reduction gauges. -->
        <div class="gauges">
          <div class="gauge" data-testid="gauge-noise">
            <span class="gauge-label">Noise ratio</span>
            <div class="gauge-track" role="img" [attr.aria-label]="'Noise ratio ' + pct(store.aggregate().noiseRatio)">
              <span class="gauge-fill noise" [style.width.%]="(store.aggregate().noiseRatio ?? 0) * 100"></span>
            </div>
            <span class="gauge-value">{{ (store.aggregate().noiseRatio ?? 0) | percent: '1.0-1' }}</span>
          </div>
          <div class="gauge" data-testid="gauge-storm">
            <span class="gauge-label">Storm reduction</span>
            <div
              class="gauge-track"
              role="img"
              [attr.aria-label]="
                'Storm reduction ' +
                (store.aggregate().stormReduction !== null
                  ? (store.aggregate().stormReduction! | number: '1.1-1') + ' to 1'
                  : 'not available')
              "
            >
              <span class="gauge-fill storm" [style.width.%]="stormPct(store.aggregate().stormReduction)"></span>
            </div>
            <span class="gauge-value">
              @if (store.aggregate().stormReduction !== null) {
                {{ store.aggregate().stormReduction! | number: '1.1-1' }} : 1
              } @else {
                N/A
              }
            </span>
          </div>
        </div>
      </section>

      <!-- PER-RUN proportion bars -->
      <section class="card" aria-labelledby="runs-h">
        <h2 id="runs-h">Per-run breakdown</h2>
        <ul class="run-list">
          @for (r of store.runStats(); track r.runId) {
            <li class="run" data-testid="run-row">
              <div class="run-head">
                <span class="run-id">{{ r.runId }}</span>
                <span class="run-trail">{{ r.trailId }}</span>
                <span class="run-in" data-testid="run-alarmsIn">{{ r.alarmsIn }} in</span>
                <span class="run-storm" data-testid="run-storm">
                  @if (store.stormReduction(r) !== null) {
                    {{ store.stormReduction(r)! | number: '1.1-1' }} : 1
                  } @else {
                    N/A
                  }
                </span>
              </div>
              @if (store.keptRatio(r) !== null) {
                <div
                  class="prop-bar sm"
                  role="img"
                  [attr.aria-label]="
                    r.runId + ': ' + r.alarmsKept + ' kept, ' + r.alarmsDropped + ' dropped of ' + r.alarmsIn
                  "
                >
                  <span class="seg seg-kept" [style.width.%]="store.keptRatio(r)! * 100">
                    <span class="seg-text">{{ r.alarmsKept }}</span>
                  </span>
                  <span class="seg seg-dropped" [style.width.%]="store.droppedRatio(r)! * 100">
                    <span class="seg-text">{{ r.alarmsDropped }}</span>
                  </span>
                </div>
              }
            </li>
          }
        </ul>
      </section>
    } @else {
      <p class="empty-state">No run-stats yet.</p>
    }
  `,
  styles: [
    `
      .hint {
        color: var(--text-muted);
        margin: 0 0 0.9rem;
      }
      .trail-filter {
        display: inline-flex;
        gap: 0.4rem;
        align-items: center;
        margin-bottom: 1rem;
      }
      input[type='text'] {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.25rem;
      }
      .headline {
        margin-bottom: 1rem;
      }
      .agg-stats {
        display: flex;
        gap: 2rem;
        flex-wrap: wrap;
        margin-bottom: 1rem;
      }
      .agg-stat {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
      }
      .agg-label {
        color: var(--text-muted);
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        font-weight: 600;
      }
      .agg-value {
        font-size: 1.6rem;
        font-weight: 700;
        line-height: 1.1;
      }
      .agg-value.kept {
        color: var(--ok);
      }
      .agg-value.dropped {
        color: var(--warn);
      }
      /* Kept-vs-dropped proportion bar (flex segments summing to 100%). */
      .prop-bar {
        display: flex;
        width: 100%;
        height: 1.9rem;
        border-radius: 8px;
        overflow: hidden;
        border: 1px solid var(--border);
        background: var(--surface-2);
      }
      .prop-bar.sm {
        height: 1.4rem;
        margin-top: 0.3rem;
      }
      .seg {
        display: flex;
        align-items: center;
        justify-content: center;
        min-width: 0;
        overflow: hidden;
        white-space: nowrap;
      }
      .seg-kept {
        background: var(--ok);
        color: #06280f;
      }
      .seg-dropped {
        background: var(--warn);
        color: #3a2a00;
      }
      .seg-text {
        font-size: 0.72rem;
        font-weight: 700;
        padding: 0 0.4rem;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .gauges {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
        gap: 1.25rem;
        margin-top: 1.1rem;
      }
      .gauge {
        display: grid;
        grid-template-columns: 9rem 1fr auto;
        align-items: center;
        gap: 0.6rem;
      }
      .gauge-label {
        color: var(--text-muted);
        font-size: 0.82rem;
        font-weight: 600;
      }
      .gauge-track {
        height: 0.7rem;
        border-radius: 999px;
        background: var(--surface-2);
        border: 1px solid var(--border);
        overflow: hidden;
      }
      .gauge-fill {
        display: block;
        height: 100%;
      }
      .gauge-fill.noise {
        background: var(--warn);
      }
      .gauge-fill.storm {
        background: var(--accent);
      }
      .gauge-value {
        font-weight: 700;
        font-size: 0.85rem;
        white-space: nowrap;
      }
      .run-list {
        list-style: none;
        padding: 0;
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 0.9rem;
      }
      .run-head {
        display: flex;
        align-items: baseline;
        gap: 0.75rem;
        flex-wrap: wrap;
      }
      .run-id {
        font-weight: 700;
      }
      .run-trail {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
      .run-in {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
      .run-storm {
        margin-left: auto;
        font-weight: 600;
        font-size: 0.85rem;
      }
    `,
  ],
})
export class NoiseViewComponent implements OnInit {
  readonly store = inject(NoiseStore);

  /** Cap the storm-reduction gauge fill at a sensible max (30:1) so a huge ratio doesn't overflow. */
  private static readonly STORM_GAUGE_MAX = 30;

  ngOnInit(): void {
    this.store.loadRunStats();
  }

  onTrailFilter(event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim();
    this.store.loadRunStats(value || undefined);
  }

  /** Storm-reduction ratio → a 0-100 gauge width, clamped to the gauge max. */
  stormPct(ratio: number | null): number {
    if (ratio === null) {
      return 0;
    }
    return Math.min(100, (ratio / NoiseViewComponent.STORM_GAUGE_MAX) * 100);
  }

  /** Format a [0..1] fraction as a whole-percent string for aria-labels ('—' when null). */
  pct(fraction: number | null): string {
    return fraction === null ? '—' : `${Math.round(fraction * 100)}%`;
  }
}
