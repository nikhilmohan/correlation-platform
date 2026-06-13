import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { TopologyStore } from './topology.store';
import { AttributeMap } from '../api/models';

interface AttrRow {
  key: string;
  label: string;
  value: string;
  wellKnown: boolean;
}

const NODE_LABELS: Record<string, string> = {
  vendor: 'Vendor',
  model: 'Model',
  equipmentType: 'Equipment type',
  role: 'Role',
  capacity: 'Capacity',
};
const EDGE_LABELS: Record<string, string> = {
  linkType: 'Link type',
  capacity: 'Capacity',
  protectionRole: 'Protection role',
};

/**
 * Detail panel for the selected node/edge (spec task 8, AC 29/30). Well-known keys get friendly
 * labels; unknown keys render as generic key/value rows. The UI never validates the attribute
 * schema (Knowledge owns the catalogue). `managedObjectId`/`relation` always shown.
 */
@Component({
  selector: 'app-attribute-detail-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <aside class="card" aria-labelledby="detail-h" data-testid="detail-panel">
      <h2 id="detail-h">Detail</h2>
      @if (node(); as n) {
        <p class="moid"><strong>managedObjectId:</strong> {{ n.managedObjectId }}</p>
        <p class="muted">type {{ n.objectType }} · layer {{ n.derivedLayer }}</p>
        <dl class="attrs">
          @for (row of nodeRows(); track row.key) {
            <div class="attr-row" [class.well-known]="row.wellKnown" [attr.data-testid]="'attr-' + row.key">
              <dt>{{ row.label }}</dt>
              <dd>{{ row.value }}</dd>
            </div>
          }
        </dl>
      } @else if (edge(); as e) {
        <p class="moid"><strong>relation:</strong> {{ e.relation }}</p>
        <p class="muted">{{ e.from }} → {{ e.to }} · layer {{ e.derivedLayer }}</p>
        <dl class="attrs">
          @for (row of edgeRows(); track row.key) {
            <div class="attr-row" [class.well-known]="row.wellKnown" [attr.data-testid]="'attr-' + row.key">
              <dt>{{ row.label }}</dt>
              <dd>{{ row.value }}</dd>
            </div>
          }
        </dl>
      } @else {
        <p class="empty-state">Select a device or connection to see its attributes.</p>
      }
    </aside>
  `,
  styles: [
    `
      .moid {
        word-break: break-all;
      }
      .muted {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
      .attrs {
        margin: 0.5rem 0 0;
      }
      .attr-row {
        display: flex;
        justify-content: space-between;
        gap: 1rem;
        padding: 0.25rem 0;
        border-bottom: 1px dashed var(--border);
      }
      .attr-row dt {
        color: var(--text-muted);
      }
      .attr-row.well-known dt {
        color: var(--text);
        font-weight: 600;
      }
      dt,
      dd {
        margin: 0;
      }
    `,
  ],
})
export class AttributeDetailPanelComponent {
  private readonly store = inject(TopologyStore);
  readonly node = computed(() => this.store.selectedNode());
  readonly edge = computed(() => this.store.selectedEdge());

  readonly nodeRows = computed<AttrRow[]>(() => this.rows(this.node()?.attributes ?? null, NODE_LABELS));
  readonly edgeRows = computed<AttrRow[]>(() => this.rows(this.edge()?.attributes ?? null, EDGE_LABELS));

  private rows(attrs: AttributeMap | null, labels: Record<string, string>): AttrRow[] {
    if (!attrs) {
      return [];
    }
    return Object.entries(attrs).map(([key, value]) => ({
      key,
      label: labels[key] ?? key,
      value: this.stringify(value),
      // eslint-disable-next-line no-prototype-builtins
      wellKnown: Object.prototype.hasOwnProperty.call(labels, key),
    }));
  }

  private stringify(value: unknown): string {
    if (value === null || value === undefined) {
      return '';
    }
    return typeof value === 'object' ? JSON.stringify(value) : String(value);
  }
}
