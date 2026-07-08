import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  QueryList,
  ViewChildren,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DashboardActionsService } from './dashboard-actions.service';

/**
 * Top-nav "Actions" dropdown menu. Three operator actions — Mine patterns / Ingest alarms /
 * Purge alarms — each delegating to the shared DashboardActionsService (the single implementation
 * shared with the dashboard buttons). Mutual exclusion: while any action runs, all three items are
 * disabled and the running one shows a busy label.
 *
 * WCAG 2.1 menu-button pattern: trigger carries aria-haspopup="menu" + aria-expanded; the popup is
 * role="menu" with role="menuitem" children; roving focus via Arrow keys, Home/End, Escape closes
 * and returns focus to the trigger, and a click outside closes it.
 */
@Component({
  selector: 'app-actions-menu',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:click)': 'onDocumentClick($event)',
    '(document:keydown.escape)': 'onEscape()',
  },
  template: `
    <div class="actions-menu">
      <button
        #trigger
        type="button"
        class="actions-trigger"
        data-testid="actions-menu"
        aria-haspopup="menu"
        [attr.aria-expanded]="open()"
        [attr.aria-controls]="'actions-menu-list'"
        [attr.aria-busy]="actions.busy()"
        (click)="toggle()"
        (keydown)="onTriggerKeydown($event)"
      >
        <span>Actions</span>
        <span class="caret" aria-hidden="true">{{ open() ? '▴' : '▾' }}</span>
      </button>

      @if (open()) {
        <ul
          id="actions-menu-list"
          class="actions-list"
          role="menu"
          aria-label="Actions"
          (keydown)="onListKeydown($event)"
        >
          <li role="none">
            <button
              #item
              type="button"
              role="menuitem"
              class="actions-item"
              data-testid="action-mine"
              tabindex="-1"
              [disabled]="actions.busy()"
              [attr.aria-busy]="actions.isMining()"
              (click)="runMine()"
            >
              @if (actions.isMining()) {
                <span class="spinner" aria-hidden="true"></span>
                <span>Mining…</span>
              } @else {
                <span>Mine patterns</span>
              }
            </button>
          </li>
          <li role="none">
            <button
              #item
              type="button"
              role="menuitem"
              class="actions-item"
              data-testid="action-ingest"
              tabindex="-1"
              [disabled]="actions.busy()"
              [attr.aria-busy]="actions.isIngesting()"
              (click)="runIngest()"
            >
              @if (actions.isIngesting()) {
                <span class="spinner" aria-hidden="true"></span>
                <span>Ingesting…</span>
              } @else {
                <span>Ingest alarms</span>
              }
            </button>
          </li>
          <li role="none">
            <button
              #item
              type="button"
              role="menuitem"
              class="actions-item"
              data-testid="action-purge"
              tabindex="-1"
              [disabled]="actions.busy()"
              [attr.aria-busy]="actions.isPurging()"
              (click)="runPurge()"
            >
              @if (actions.isPurging()) {
                <span class="spinner" aria-hidden="true"></span>
                <span>Purging…</span>
              } @else {
                <span>Purge alarms</span>
              }
            </button>
          </li>

          <li role="none" class="actions-help">
            <p class="help-text">Pattern mining is resource-intensive; runs on the server.</p>
          </li>
        </ul>
      }

      @if (resultLine(); as r) {
        <div class="actions-result" data-testid="mine-result" role="status" aria-live="polite">
          {{ r }}
        </div>
      }

      <span class="visually-hidden" aria-live="polite" data-testid="actions-live">
        {{ liveMessage() }}
      </span>
    </div>
  `,
  styles: [
    `
      .actions-menu {
        position: relative;
        display: inline-flex;
      }
      .actions-trigger {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        padding: 0.35rem 0.75rem;
        border: 1px solid var(--border);
        background: var(--surface-2);
        color: var(--text);
        border-radius: var(--radius-sm);
        font-weight: 500;
        cursor: pointer;
      }
      .actions-trigger:hover {
        border-color: var(--accent);
        color: var(--accent);
      }
      .actions-list {
        position: absolute;
        top: calc(100% + 0.35rem);
        left: 0;
        min-width: 15rem;
        margin: 0;
        padding: 0.35rem;
        list-style: none;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        box-shadow: var(--shadow-md, var(--shadow-sm));
        z-index: 20;
      }
      .actions-item {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        width: 100%;
        padding: 0.45rem 0.6rem;
        border: none;
        background: transparent;
        color: var(--text);
        border-radius: var(--radius-sm);
        text-align: left;
        font: inherit;
        cursor: pointer;
      }
      .actions-item:hover:not(:disabled),
      .actions-item:focus-visible {
        background: var(--surface-2);
      }
      .actions-item:disabled {
        opacity: 0.6;
        cursor: default;
      }
      .actions-help {
        margin-top: 0.25rem;
        padding: 0.25rem 0.6rem 0.15rem;
        border-top: 1px solid var(--border);
      }
      .help-text {
        margin: 0.25rem 0 0;
        font-size: 0.78rem;
        color: var(--text-muted);
      }
      .actions-result {
        position: absolute;
        top: calc(100% + 0.35rem);
        left: 0;
        min-width: 16rem;
        max-width: 22rem;
        padding: 0.5rem 0.7rem;
        font-size: 0.82rem;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        box-shadow: var(--shadow-sm);
        color: var(--text);
        z-index: 15;
      }
      .spinner {
        width: 0.85rem;
        height: 0.85rem;
        border: 2px solid currentColor;
        border-right-color: transparent;
        border-radius: 50%;
        display: inline-block;
        animation: actions-spin 0.7s linear infinite;
      }
      @keyframes actions-spin {
        to {
          transform: rotate(360deg);
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .spinner {
          animation-duration: 2s;
        }
      }
    `,
  ],
})
export class ActionsMenuComponent {
  readonly actions = inject(DashboardActionsService);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly open = signal(false);

  @ViewChildren('item') private items!: QueryList<ElementRef<HTMLButtonElement>>;

  /** Show the mine result/notice (done or error) once a mine run settles, cleared on menu re-open. */
  readonly resultLine = computed(() => {
    const st = this.actions.mine();
    if (st.status === 'done' || st.status === 'error') {
      return st.message;
    }
    return null;
  });

  readonly liveMessage = computed(() => {
    const active = this.actions.activeAction();
    if (active === 'mine') {
      const p = this.actions.mineProgress();
      const counts = p ? ` — ${p.alarmsEmitted} alarms generated so far` : '';
      return `Pattern mining running on the server${counts}.`;
    }
    if (active === 'ingest') {
      return 'Ingestion running.';
    }
    if (active === 'purge') {
      return 'Purging live alarms and correlation state.';
    }
    const mine = this.actions.mine();
    if (mine.status === 'done' || mine.status === 'error') {
      return mine.message ?? '';
    }
    return '';
  });

  toggle(): void {
    this.open.update((o) => !o);
  }

  private close(returnFocus = true): void {
    if (!this.open()) {
      return;
    }
    this.open.set(false);
    if (returnFocus) {
      this.triggerEl()?.focus();
    }
  }

  private triggerEl(): HTMLButtonElement | null {
    return this.host.nativeElement.querySelector('[data-testid="actions-menu"]');
  }

  private itemButtons(): HTMLButtonElement[] {
    return this.items ? this.items.map((r) => r.nativeElement) : [];
  }

  private focusItem(index: number): void {
    const buttons = this.itemButtons();
    if (buttons.length === 0) {
      return;
    }
    const wrapped = (index + buttons.length) % buttons.length;
    buttons[wrapped].focus();
  }

  private currentIndex(): number {
    const buttons = this.itemButtons();
    return buttons.findIndex((b) => b === document.activeElement);
  }

  onTriggerKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowDown' || event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      if (!this.open()) {
        this.open.set(true);
      }
      // Focus the first item after the menu renders.
      queueMicrotask(() => this.focusItem(0));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (!this.open()) {
        this.open.set(true);
      }
      queueMicrotask(() => this.focusItem(-1));
    }
  }

  onListKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.focusItem(this.currentIndex() + 1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.focusItem(this.currentIndex() - 1);
        break;
      case 'Home':
        event.preventDefault();
        this.focusItem(0);
        break;
      case 'End':
        event.preventDefault();
        this.focusItem(-1);
        break;
      case 'Tab':
        // Tabbing out closes the menu (without stealing focus back).
        this.close(false);
        break;
      default:
        break;
    }
  }

  onEscape(): void {
    this.close();
  }

  onDocumentClick(event: MouseEvent): void {
    if (!this.open()) {
      return;
    }
    const target = event.target as Node | null;
    if (target && !this.host.nativeElement.contains(target)) {
      this.close(false);
    }
  }

  runMine(): void {
    this.actions.startMine();
    this.close(false);
  }

  runIngest(): void {
    this.actions.startIngest();
    this.close(false);
  }

  runPurge(): void {
    this.actions.startPurge();
    this.close(false);
  }
}
