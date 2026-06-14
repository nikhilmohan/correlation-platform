import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of } from 'rxjs';
import { PatternManagerClient } from '../api/pattern-manager.client';
import { PatternEdit, PatternLifecycle, PatternView } from '../api/models';

export type LifecycleFilter = 'draft' | 'approved';

@Injectable()
export class PatternStore {
  private readonly pm = inject(PatternManagerClient);

  readonly patterns = signal<PatternView[]>([]);
  readonly lifecycleFilter = signal<LifecycleFilter>('draft');
  readonly expandedId = signal<string | null>(null);
  readonly pendingDecision = signal<string | null>(null);
  readonly loading = signal<boolean>(false);

  readonly visiblePatterns = computed<PatternView[]>(() => {
    const filter = this.lifecycleFilter();
    return this.patterns().filter((p) => p.lifecycle === (filter as PatternLifecycle));
  });

  load(filter: LifecycleFilter = this.lifecycleFilter()): void {
    this.lifecycleFilter.set(filter);
    this.loading.set(true);
    this.pm
      .listPatterns({ lifecycle: filter })
      .pipe(catchError(() => of({ items: [], total: 0, limit: 50, offset: 0 })))
      .subscribe((page) => {
        this.patterns.set(page.items);
        this.loading.set(false);
      });
  }

  toggleExpand(patternId: string): void {
    this.expandedId.set(this.expandedId() === patternId ? null : patternId);
  }

  decide(patternId: string, decision: 'approve' | 'reject', reviewer = 'operator', notes?: string): void {
    if (this.pendingDecision()) {
      return; // double-submit guard
    }
    this.pendingDecision.set(patternId);
    this.pm
      .decide(patternId, { decision, reviewer, notes })
      .pipe(catchError(() => of(null)))
      .subscribe((updated) => {
        this.pendingDecision.set(null);
        if (!updated) {
          return;
        }
        if (decision === 'reject') {
          // Remove from the discovered list / mark rejected.
          this.patterns.set(this.patterns().filter((p) => p.patternId !== patternId));
        } else {
          this.patterns.set(this.patterns().map((p) => (p.patternId === patternId ? updated : p)));
        }
      });
  }

  edit(patternId: string, edit: PatternEdit): void {
    this.pm
      .edit(patternId, edit)
      .pipe(catchError(() => of(null)))
      .subscribe((updated) => {
        if (updated) {
          this.patterns.set(this.patterns().map((p) => (p.patternId === patternId ? updated : p)));
        }
      });
  }
}
