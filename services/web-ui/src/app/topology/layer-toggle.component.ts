import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TopologyStore } from './topology.store';
import { LogicalLayer } from '../api/models';

/** Logical-layer toggles (AC 28): fiber / IP / IGP / LSP / service shown or hidden independently. */
@Component({
  selector: 'app-layer-toggle',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <fieldset class="layers">
      <legend>Logical layers</legend>
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
        padding: 0.5rem 0.8rem;
        margin-bottom: 0.8rem;
      }
      .layer-toggle {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
      }
    `,
  ],
})
export class LayerToggleComponent {
  readonly store = inject(TopologyStore);
  readonly layers: LogicalLayer[] = ['fiber', 'IP', 'IGP', 'LSP', 'service'];

  onToggle(layer: LogicalLayer, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.store.setLayerVisible(layer, checked);
  }
}
