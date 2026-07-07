import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { NoiseStore } from './noise.store';

/**
 * Graphical Noise-filter view (Part 4). Its OWN first-level tab/route (`/noise`). Keeps the
 * prominent AGGREGATE headline (Alarms in → Kept → Dropped, storm-reduction) and REPLACES the
 * wall of per-run proportion bars with TWO heatmaps + a toggle:
 *   - Heatmap A (default) — Time × noise-vs-signal: two heat-rows (dropped-as-noise, kept-as-
 *     signal) over time buckets; cell colour = normalised count. Shows WHEN noise bursts.
 *   - Heatmap B (toggle) — Trail × time: one row per (noisiest) trail, cell colour = noise ratio.
 *     Shows WHERE + WHEN noise concentrates.
 * Pure CSS/SVG (CSS grid of intensity-shaded cells) — theme-aware via the app CSS custom
 * properties. Non-colour-only: a legend with numeric anchors + every cell carries a text value
 * and an aria-label with the REAL count/ratio so it is screen-reader legible.
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
      How effectively the noise filter collapses alarm storms into clusters and drops chatter — the
      heatmaps below show <strong>when</strong> noise bursts and <strong>where</strong> it concentrates.
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
      </section>

      <!-- HEATMAPS + toggle -->
      <section class="card" aria-labelledby="heat-h">
        <div class="heat-head">
          <h2 id="heat-h">
            @if (store.heatmapMode() === 'time') {
              Noise over time
            } @else {
              Noise by trail over time
            }
          </h2>
          <div class="toggle" role="group" aria-label="Choose heatmap" data-testid="noise-heatmap-toggle">
            <button
              type="button"
              class="toggle-btn"
              [class.active]="store.heatmapMode() === 'time'"
              [attr.aria-pressed]="store.heatmapMode() === 'time'"
              (click)="store.setHeatmapMode('time')"
            >
              Time × noise/signal
            </button>
            <button
              type="button"
              class="toggle-btn"
              [class.active]="store.heatmapMode() === 'trail'"
              [attr.aria-pressed]="store.heatmapMode() === 'trail'"
              (click)="store.setHeatmapMode('trail')"
            >
              Trail × time
            </button>
          </div>
        </div>

        @if (store.heatmapMode() === 'time') {
          <!-- HEATMAP A: two heat-rows over time buckets, colour = normalised count. -->
          <div
            class="heatmap heatmap-time"
            data-testid="noise-heatmap-time"
            [style.--cols]="store.timeHeatmap().buckets.length"
          >
            <div class="hm-legend">
              <span class="hm-legend-label">Alarms per bucket</span>
              <span class="hm-scale" aria-hidden="true">
                <span class="hm-swatch i0"></span><span class="hm-swatch i1"></span>
                <span class="hm-swatch i2"></span><span class="hm-swatch i3"></span>
                <span class="hm-swatch i4"></span>
              </span>
              <span class="hm-legend-anchors">0 – {{ store.timeHeatmap().maxCell }}</span>
            </div>

            <div class="hm-row-label">Dropped as noise</div>
            <div class="hm-cells">
              @for (c of store.timeHeatmap().droppedRow; track c.bucket) {
                <span
                  class="hm-cell dropped"
                  data-testid="heat-cell-dropped"
                  [style.--i]="c.intensity"
                  [attr.title]="cellTitle('dropped', c.bucket, c.count)"
                  [attr.aria-label]="cellTitle('dropped', c.bucket, c.count)"
                >
                  <span class="hm-cell-text">{{ c.count || '' }}</span>
                </span>
              }
            </div>

            <div class="hm-row-label">Kept as signal</div>
            <div class="hm-cells">
              @for (c of store.timeHeatmap().keptRow; track c.bucket) {
                <span
                  class="hm-cell kept"
                  data-testid="heat-cell-kept"
                  [style.--i]="c.intensity"
                  [attr.title]="cellTitle('kept', c.bucket, c.count)"
                  [attr.aria-label]="cellTitle('kept', c.bucket, c.count)"
                >
                  <span class="hm-cell-text">{{ c.count || '' }}</span>
                </span>
              }
            </div>

            <div class="hm-axis">
              @for (b of store.timeHeatmap().buckets; track b.index) {
                <span class="hm-axis-tick">{{ b.label }}</span>
              }
            </div>
          </div>
        } @else {
          <!-- HEATMAP B: trail (row) × time (col), colour = noise ratio dropped/in. -->
          <div
            class="heatmap heatmap-trail"
            data-testid="noise-heatmap-trail"
            [style.--cols]="store.trailHeatmap().buckets.length"
          >
            <div class="hm-legend">
              <span class="hm-legend-label">Noise ratio (dropped / in)</span>
              <span class="hm-scale" aria-hidden="true">
                <span class="hm-swatch i0"></span><span class="hm-swatch i1"></span>
                <span class="hm-swatch i2"></span><span class="hm-swatch i3"></span>
                <span class="hm-swatch i4"></span>
              </span>
              <span class="hm-legend-anchors">0% – 100%</span>
            </div>

            @if (store.trailHeatmap().rows.length) {
              @for (row of store.trailHeatmap().rows; track row.trailId) {
                <div class="hm-trail-row" data-testid="heat-trail-row">
                  <div class="hm-row-label trail" [attr.title]="row.trailId">{{ row.trailId }}</div>
                  <div class="hm-cells">
                    @for (c of row.cells; track c.bucket) {
                      <span
                        class="hm-cell noise"
                        data-testid="heat-cell-trail"
                        [style.--i]="c.noiseRatio ?? 0"
                        [class.empty]="c.noiseRatio === null"
                        [attr.title]="trailCellTitle(row.trailId, c.bucket, c)"
                        [attr.aria-label]="trailCellTitle(row.trailId, c.bucket, c)"
                      >
                        <span class="hm-cell-text">
                          @if (c.noiseRatio !== null) {
                            {{ c.noiseRatio | percent: '1.0-0' }}
                          } @else {
                            —
                          }
                        </span>
                      </span>
                    }
                  </div>
                </div>
              }
              <div class="hm-axis trail">
                @for (b of store.trailHeatmap().buckets; track b.index) {
                  <span class="hm-axis-tick">{{ b.label }}</span>
                }
              </div>
              @if (store.trailHeatmap().omitted > 0) {
                <p class="hm-omitted" data-testid="heat-trail-omitted">
                  +{{ store.trailHeatmap().omitted }} more trails (showing the noisiest)
                </p>
              }
            } @else {
              <p class="empty-state">No trail data.</p>
            }
          </div>
        }
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
      .prop-bar {
        display: flex;
        width: 100%;
        height: 1.9rem;
        border-radius: 8px;
        overflow: hidden;
        border: 1px solid var(--border);
        background: var(--surface-2);
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

      /* ---- Heatmaps ---- */
      .heat-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        flex-wrap: wrap;
        margin-bottom: 0.8rem;
      }
      .toggle {
        display: inline-flex;
        border: 1px solid var(--border);
        border-radius: 8px;
        overflow: hidden;
      }
      .toggle-btn {
        background: var(--surface-2);
        color: var(--text-muted);
        border: 0;
        padding: 0.4rem 0.8rem;
        font-size: 0.82rem;
        font-weight: 600;
        cursor: pointer;
      }
      .toggle-btn + .toggle-btn {
        border-left: 1px solid var(--border);
      }
      .toggle-btn.active {
        background: var(--accent);
        color: #04121f;
      }
      .toggle-btn:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: -2px;
      }

      .hm-legend {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        margin-bottom: 0.8rem;
        flex-wrap: wrap;
      }
      .hm-legend-label {
        font-size: 0.78rem;
        color: var(--text-muted);
        font-weight: 600;
      }
      .hm-scale {
        display: inline-flex;
        gap: 2px;
      }
      .hm-swatch {
        width: 1.4rem;
        height: 0.8rem;
        border-radius: 2px;
        border: 1px solid var(--border);
      }
      .hm-swatch.i0 {
        background: var(--surface-2);
      }
      .hm-swatch.i1 {
        background: color-mix(in srgb, var(--accent) 25%, var(--surface-2));
      }
      .hm-swatch.i2 {
        background: color-mix(in srgb, var(--accent) 50%, var(--surface-2));
      }
      .hm-swatch.i3 {
        background: color-mix(in srgb, var(--accent) 75%, var(--surface-2));
      }
      .hm-swatch.i4 {
        background: var(--accent);
      }
      .hm-legend-anchors {
        font-size: 0.78rem;
        color: var(--text-muted);
        font-variant-numeric: tabular-nums;
      }

      /* Heatmap A layout: label column + a cells grid row per heat-row. */
      .heatmap-time {
        display: grid;
        grid-template-columns: 9rem 1fr;
        align-items: center;
        gap: 0.35rem 0.6rem;
      }
      .heatmap-time .hm-legend {
        grid-column: 1 / -1;
      }
      .heatmap-time .hm-axis {
        grid-column: 2;
      }

      .hm-row-label {
        font-size: 0.8rem;
        font-weight: 600;
        color: var(--text-muted);
      }
      .hm-cells {
        display: grid;
        grid-template-columns: repeat(var(--cols, 1), 1fr);
        gap: 2px;
      }
      .hm-cell {
        position: relative;
        display: flex;
        align-items: center;
        justify-content: center;
        min-height: 1.9rem;
        border-radius: 3px;
        border: 1px solid var(--border);
        /* intensity --i in [0..1] drives the accent mix (colour) — text value is always present. */
        background: color-mix(in srgb, var(--accent) calc(var(--i, 0) * 100%), var(--surface-2));
      }
      .hm-cell.kept {
        background: color-mix(in srgb, var(--ok) calc(var(--i, 0) * 100%), var(--surface-2));
      }
      .hm-cell.dropped {
        background: color-mix(in srgb, var(--warn) calc(var(--i, 0) * 100%), var(--surface-2));
      }
      .hm-cell.noise {
        background: color-mix(in srgb, var(--warn) calc(var(--i, 0) * 100%), var(--surface-2));
      }
      .hm-cell.empty {
        opacity: 0.55;
      }
      .hm-cell-text {
        font-size: 0.68rem;
        font-weight: 700;
        color: var(--text);
        font-variant-numeric: tabular-nums;
        pointer-events: none;
      }
      .hm-axis {
        display: grid;
        grid-template-columns: repeat(var(--cols, 1), 1fr);
        gap: 2px;
      }
      .hm-axis-tick {
        font-size: 0.62rem;
        color: var(--text-muted);
        text-align: center;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      /* Heatmap B layout: a labelled row per trail. */
      .heatmap-trail .hm-trail-row {
        display: grid;
        grid-template-columns: 9rem 1fr;
        align-items: center;
        gap: 0.6rem;
        margin-bottom: 2px;
      }
      .hm-row-label.trail {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: var(--text);
      }
      .hm-axis.trail {
        margin-left: calc(9rem + 0.6rem);
      }
      .hm-omitted {
        color: var(--text-muted);
        font-size: 0.8rem;
        margin: 0.6rem 0 0;
      }
    `,
  ],
})
export class NoiseViewComponent implements OnInit {
  readonly store = inject(NoiseStore);

  ngOnInit(): void {
    this.store.loadRunStats();
  }

  onTrailFilter(event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim();
    this.store.loadRunStats(value || undefined);
  }

  /** Format a [0..1] fraction as a whole-percent string for aria-labels ('—' when null). */
  pct(fraction: number | null): string {
    return fraction === null ? '—' : `${Math.round(fraction * 100)}%`;
  }

  /** Screen-reader label for a Heatmap-A cell, e.g. "12:04, dropped 8 alarms". */
  cellTitle(kind: 'dropped' | 'kept', bucket: number, count: number): string {
    const label = this.store.timeHeatmap().buckets[bucket]?.label ?? `#${bucket + 1}`;
    const noun = kind === 'dropped' ? 'dropped' : 'kept';
    return `${label}, ${noun} ${count} alarm${count === 1 ? '' : 's'}`;
  }

  /** Screen-reader label for a Heatmap-B cell, e.g. "TR-7 at 12:04, 62% noise (8 of 13)". */
  trailCellTitle(
    trailId: string,
    bucket: number,
    cell: { alarmsIn: number; alarmsDropped: number; noiseRatio: number | null },
  ): string {
    const label = this.store.trailHeatmap().buckets[bucket]?.label ?? `#${bucket + 1}`;
    if (cell.noiseRatio === null) {
      return `${trailId} at ${label}, no alarms`;
    }
    return `${trailId} at ${label}, ${Math.round(cell.noiseRatio * 100)}% noise (${cell.alarmsDropped} of ${cell.alarmsIn})`;
  }
}
