import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PatternStore, LifecycleFilter } from './pattern.store';
import { NavigationService } from '../core/navigation.service';
import { ErrorBannerService } from '../core/error-banner.service';
import { PatternView, SequenceFlag } from '../api/models';

/**
 * Pattern review & XAI (spec tasks 10-12, AC 34-38, 54). Lists discovered/active patterns with
 * full XAI; expand to view evidence; approve/reject; edit a draft pattern (optional-alarm
 * placeholder). pattern-manager is a P2/P3 backend not yet built — empty/error states handled.
 */
@Component({
  selector: 'app-pattern-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [PatternStore],
  imports: [DecimalPipe, FormsModule],
  template: `
    <h1>Patterns</h1>
    @if (errors.forService('Pattern Manager'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }

    <div class="tabs" role="tablist" aria-label="Pattern lifecycle">
      <button
        type="button"
        role="tab"
        data-testid="tab-draft"
        [attr.aria-selected]="store.lifecycleFilter() === 'draft'"
        [class.active]="store.lifecycleFilter() === 'draft'"
        (click)="setFilter('draft')"
      >
        Discovered (draft)
      </button>
      <button
        type="button"
        role="tab"
        data-testid="tab-approved"
        [attr.aria-selected]="store.lifecycleFilter() === 'approved'"
        [class.active]="store.lifecycleFilter() === 'approved'"
        (click)="setFilter('approved')"
      >
        Active (approved)
      </button>
    </div>

    @if (store.loading()) {
      <p aria-busy="true">Loading patterns…</p>
    } @else if (store.visiblePatterns().length) {
      <ul class="pattern-list">
        @for (p of store.visiblePatterns(); track p.patternId) {
          <li class="card" data-testid="pattern-row" [attr.data-pattern-id]="p.patternId">
            <div class="pattern-head">
              <button
                type="button"
                class="expand"
                data-testid="pattern-expand"
                [attr.aria-expanded]="store.expandedId() === p.patternId"
                (click)="store.toggleExpand(p.patternId)"
              >
                <strong>{{ sequenceText(p) }}</strong>
              </button>
              <span class="lifecycle" data-testid="pattern-lifecycle">{{ p.lifecycle }}</span>
            </div>
            <div class="metrics">
              <span>support {{ p.support | number: '1.2-2' }}</span>
              <span>confidence {{ p.confidence | number: '1.2-2' }}</span>
              <span>lift {{ p.lift | number: '1.1-1' }}</span>
              <span data-testid="rca">RCA {{ p.rootCauseAlarmType }}</span>
              <span>codebook {{ p.codebookMatchId ?? 'none' }}</span>
            </div>

            @if (store.expandedId() === p.patternId) {
              <div class="xai" data-testid="pattern-xai">
                <p>session window: {{ p.sessionWindow?.windowMs }} ms ({{ p.sessionWindow?.type }})</p>
                <p>structurally validated: {{ p.structurallyValidated ? 'yes' : 'no' }}
                  @if (p.structuralValidationReason) {
                    — {{ p.structuralValidationReason }}
                  }
                </p>
                <p>supporting instances: {{ p.instanceCount }}</p>
                <p>timing: {{ timingText(p) }}</p>
                <div class="actions">
                  @if (p.lifecycle === 'draft') {
                    <button
                      class="btn"
                      type="button"
                      data-testid="approve-btn"
                      [disabled]="store.pendingDecision() === p.patternId"
                      (click)="store.decide(p.patternId, 'approve')"
                    >
                      Approve
                    </button>
                    <button
                      class="btn btn-secondary"
                      type="button"
                      data-testid="reject-btn"
                      [disabled]="store.pendingDecision() === p.patternId"
                      (click)="store.decide(p.patternId, 'reject')"
                    >
                      Reject
                    </button>
                    <button
                      class="btn btn-secondary"
                      type="button"
                      data-testid="edit-btn"
                      (click)="openEdit(p)"
                    >
                      Edit
                    </button>
                  }
                  <button
                    class="btn btn-secondary"
                    type="button"
                    data-testid="view-trail-btn"
                    (click)="nav.toTrail(p.trailId)"
                  >
                    View trail
                  </button>
                </div>
              </div>
            }

            @if (editing() === p.patternId) {
              <form class="edit-dialog" data-testid="edit-dialog" (ngSubmit)="submitEdit(p)">
                <fieldset>
                  <legend>Mark sequence alarms optional</legend>
                  @for (el of p.sequence; track $index) {
                    <label>
                      <input
                        type="checkbox"
                        [attr.data-testid]="'opt-' + $index"
                        [checked]="optionalFlags()[$index]"
                        (change)="setFlag($index, $event)"
                      />
                      {{ el.alarmType }}
                    </label>
                  }
                </fieldset>
                <label>
                  reviewer
                  <input type="text" name="reviewer" data-testid="edit-reviewer" [(ngModel)]="reviewer" />
                </label>
                <div class="actions">
                  <button class="btn" type="submit" data-testid="edit-submit">Submit</button>
                  <button class="btn btn-secondary" type="button" (click)="editing.set(null)">Cancel</button>
                </div>
              </form>
            }
          </li>
        }
      </ul>
    } @else {
      <p class="empty-state">No {{ store.lifecycleFilter() }} patterns.</p>
    }
  `,
  styles: [
    `
      .tabs {
        display: flex;
        gap: 0.4rem;
        margin: 0.6rem 0;
      }
      .tabs button {
        background: var(--surface-2);
        color: var(--text-muted);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.4rem 0.8rem;
      }
      .tabs button.active {
        background: var(--accent-strong);
        color: #fff;
      }
      .pattern-list {
        list-style: none;
        padding: 0;
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
      }
      .pattern-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
      .expand {
        background: none;
        border: none;
        color: var(--text);
        text-align: left;
      }
      .metrics {
        display: flex;
        gap: 1rem;
        flex-wrap: wrap;
        color: var(--text-muted);
        font-size: 0.85rem;
        margin-top: 0.3rem;
      }
      .actions {
        display: flex;
        gap: 0.5rem;
        margin-top: 0.6rem;
        flex-wrap: wrap;
      }
      .edit-dialog {
        margin-top: 0.6rem;
        border-top: 1px solid var(--border);
        padding-top: 0.6rem;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
      }
      .edit-dialog fieldset {
        display: flex;
        gap: 0.8rem;
        flex-wrap: wrap;
      }
    `,
  ],
})
export class PatternListComponent implements OnInit {
  readonly store = inject(PatternStore);
  readonly nav = inject(NavigationService);
  readonly errors = inject(ErrorBannerService);

  readonly editing = signal<string | null>(null);
  readonly optionalFlags = signal<boolean[]>([]);
  reviewer = 'operator';

  ngOnInit(): void {
    this.store.load('draft');
  }

  setFilter(filter: LifecycleFilter): void {
    this.store.load(filter);
  }

  sequenceText(p: PatternView): string {
    return p.sequence.map((s) => (s.optional ? `${s.alarmType}?` : s.alarmType)).join(' → ');
  }

  timingText(p: PatternView): string {
    return p.timing ? JSON.stringify(p.timing) : 'n/a';
  }

  openEdit(p: PatternView): void {
    this.editing.set(p.patternId);
    this.optionalFlags.set(p.sequence.map((s) => s.optional));
  }

  setFlag(index: number, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const next = [...this.optionalFlags()];
    next[index] = checked;
    this.optionalFlags.set(next);
  }

  submitEdit(p: PatternView): void {
    const sequenceFlags: SequenceFlag[] = this.optionalFlags().map((optional, index) => ({ index, optional }));
    this.store.edit(p.patternId, { sequenceFlags, reviewer: this.reviewer });
    this.editing.set(null);
  }
}
