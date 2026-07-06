import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DecimalPipe } from '@angular/common';
import { IncidentDetailStore } from './incident-detail.store';
import { AlarmDetail } from '../api/models';

/**
 * Incident-detail drill-down (spec task 3, AC 14-17). Deep-linked by `:incidentId`. Renders the
 * root-cause alarm, child alarms, matched pattern/codebook + confidence, trail, and per-member
 * links into the streaming/alarm view.
 */
@Component({
  selector: 'app-incident-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [IncidentDetailStore],
  imports: [RouterLink, DecimalPipe],
  template: `
    @if (store.loading()) {
      <p aria-busy="true">Loading incident…</p>
    } @else if (store.notFound()) {
      <div class="card empty-state" role="status">Incident {{ incidentId() }} not found.</div>
    } @else if (store.incident(); as inc) {
      <header class="card">
        <h1>Incident {{ inc.incidentId }}</h1>
        <p>
          trail
          <a [routerLink]="['/topology']" [queryParams]="{ trailId: inc.trailId }" data-testid="trail-link">{{
            inc.trailId
          }}</a>
          · confidence {{ inc.confidence | number: '1.2-2' }}
        </p>
        @if (inc.matchedPatternId) {
          <p>
            Matched pattern
            <a [routerLink]="['/patterns']" data-testid="pattern-link">{{ inc.matchedPatternId }}</a>
          </p>
        } @else if (inc.matchedCodebookId) {
          <p data-testid="codebook-match">Matched codebook {{ inc.matchedCodebookId }}</p>
        }
      </header>

      <section class="card">
        <h2>Root-cause alarm</h2>
        @if (rootCause(); as rc) {
          <div class="member" data-testid="root-cause">
            <a [routerLink]="['/alarms']" [queryParams]="{ alarmId: rc.alarmId }">{{ rc.alarmId }}</a>
            — {{ rc.eventType }} · state {{ rc.lifecycleState }} · role {{ rc.role }}
          </div>
        } @else {
          <p class="empty-state">Root-cause alarm {{ inc.rootCauseAlarmId }} (detail unavailable).</p>
        }
      </section>

      <section class="card">
        <h2>Child alarms ({{ children().length }})</h2>
        @if (children().length) {
          <ul class="members">
            @for (c of children(); track c.alarmId) {
              <li data-testid="child-alarm">
                <a [routerLink]="['/alarms']" [queryParams]="{ alarmId: c.alarmId }">{{ c.alarmId }}</a>
                — {{ c.eventType }} · state {{ c.lifecycleState }} · role {{ c.role }}
              </li>
            }
          </ul>
        } @else {
          <p class="empty-state">No child alarms.</p>
        }
      </section>
    } @else {
      <p class="empty-state">No incident loaded.</p>
    }
  `,
  styles: [
    `
      .members {
        list-style: none;
        padding: 0;
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
      }
      .card {
        margin-bottom: 1rem;
      }
    `,
  ],
})
export class IncidentDetailComponent implements OnInit {
  readonly store = inject(IncidentDetailStore);
  readonly incidentId = input<string>('');

  readonly rootCause = computed<AlarmDetail | null>(() => {
    const inc = this.store.incident();
    if (!inc) {
      return null;
    }
    return this.store.memberAlarms().find((a) => a.alarmId === inc.rootCauseAlarmId) ?? null;
  });

  readonly children = computed<AlarmDetail[]>(() => {
    const inc = this.store.incident();
    if (!inc) {
      return [];
    }
    const childSet = new Set(inc.childAlarmIds);
    return this.store.memberAlarms().filter((a) => childSet.has(a.alarmId));
  });

  ngOnInit(): void {
    const id = this.incidentId();
    if (id) {
      this.store.load(id);
    }
  }
}
