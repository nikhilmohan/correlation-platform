import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { TopologyStore } from './topology.store';
import { LogicalLayer } from '../api/models';

/** Logical-layer toggles (AC 28): fiber / IP / IGP / LSP / service shown or hidden independently. */
@Component({
  selector: 'app-layer-toggle',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <fieldset class="layers">
      <legend>
        Network planes
        <!-- CHANGE 8 (v3): clarify what "logical layers" mean. An info button toggles a helper line
             explaining each plane + why some toggles look inert until a trail is exploded. -->
        <button
          type="button"
          class="layers-help-toggle"
          data-testid="layers-help-toggle"
          [attr.aria-expanded]="helpOpen()"
          aria-controls="layers-help"
          aria-label="What are network planes?"
          (click)="toggleHelp()"
        >
          <span aria-hidden="true">ⓘ</span>
        </button>
      </legend>
      @for (layer of layers; track layer) {
        <label class="layer-toggle">
          <input
            type="checkbox"
            [attr.data-testid]="'layer-' + layer"
            [checked]="store.visibleLayers().has(layer)"
            (change)="onToggle(layer, $event)"
          />
          {{ layer }}
        </label>
      }
      <p id="layers-help" class="layers-help" data-testid="layers-help" [hidden]="!helpOpen()">
        Toggle which connection types are shown:
        <strong>fiber</strong> (optical transport), <strong>IP</strong> (interface/links),
        <strong>IGP</strong> (routing adjacency/chassis), <strong>LSP</strong> (MPLS paths),
        <strong>service</strong> (VPN).
        <br />
        <span class="layers-help-note">
          Some planes (fiber, LSP, service) only appear after you explode a trail or reveal a
          device's external links — until then their toggles have nothing to show.
        </span>
      </p>
    </fieldset>
  `,
  styles: [
    `
      .layers {
        border: 1px solid var(--border);
        border-radius: 8px;
        display: flex;
        gap: 0.9rem;
        flex-wrap: wrap;
        align-items: center;
        padding: 0.5rem 0.8rem;
        margin-bottom: 0.8rem;
      }
      legend {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
      }
      .layers-help-toggle {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 1.25rem;
        height: 1.25rem;
        border: 1px solid var(--border);
        border-radius: 50%;
        background: var(--surface);
        color: var(--accent);
        cursor: pointer;
        font-size: 0.78rem;
        line-height: 1;
        padding: 0;
      }
      .layers-help-toggle:hover,
      .layers-help-toggle:focus-visible {
        border-color: var(--accent);
      }
      .layer-toggle {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
      }
      .layers-help {
        flex-basis: 100%;
        margin: 0.2rem 0 0;
        font-size: 0.8rem;
        color: var(--text-muted);
        line-height: 1.35;
      }
      .layers-help[hidden] {
        display: none;
      }
      .layers-help .layers-help-note {
        color: var(--text-muted);
        font-style: italic;
      }
    `,
  ],
})
export class LayerToggleComponent {
  readonly store = inject(TopologyStore);
  readonly layers: LogicalLayer[] = ['fiber', 'IP', 'IGP', 'LSP', 'service'];

  /** CHANGE 8 (v3): disclosure state of the "what are network planes?" helper line. */
  readonly helpOpen = signal(false);
  toggleHelp(): void {
    this.helpOpen.update((v) => !v);
  }

  onToggle(layer: LogicalLayer, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.store.setLayerVisible(layer, checked);
  }
}
