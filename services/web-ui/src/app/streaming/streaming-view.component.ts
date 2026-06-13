import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { LivePollingService } from './live-polling.service';

/**
 * Real-time streaming view (spec task 2, AC 6-13). Polls Alarm Manager + Correlation Engine on a
 * configurable interval; new/changed rows get a transient highlight (respects
 * prefers-reduced-motion via CSS). Pause/resume + interval control. Rows are tracked by id so
 * only changed rows re-render (no full-list re-render per poll).
 */
@Component({
  selector: 'app-streaming-view',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [LivePollingService],
  imports: [RouterLink, DatePipe],
  template: `
    <div class="head">
      <h1>Streaming (live)</h1>
      <div class="controls">
        <span class="live" [class.stale]="poll.pollError()" aria-live="polite">
          @if (poll.pollError()) {
            ⚠ stale data
          } @else if (poll.autoRefresh()) {
            ● LIVE
          } @else {
            ⏸ paused
          }
        </span>
        @if (poll.lastUpdated()) {
          <span class="muted">last updated {{ poll.lastUpdated() | date: 'HH:mm:ss' }}</span>
        }
        <label class="interval">
          interval (ms)
          <input
            type="number"
            min="100"
            data-testid="interval-input"
            [value]="poll.intervalMs()"
            (change)="onInterval($event)"
          />
        </label>
        @if (poll.autoRefresh()) {
          <button class="btn btn-secondary" type="button" data-testid="pause-btn" (click)="poll.pause()">
            Pause
          </button>
        } @else {
          <button class="btn" type="button" data-testid="resume-btn" (click)="poll.resume()">Resume</button>
        }
      </div>
    </div>

    <div class="grid">
      <section class="card" aria-labelledby="alarms-h">
        <h2 id="alarms-h">Alarms</h2>
        <table>
          <caption class="visually-hidden">Live alarms with lifecycle state</caption>
          <thead>
            <tr>
              <th scope="col">Alarm</th>
              <th scope="col">State</th>
              <th scope="col">Role</th>
              <th scope="col">Incident</th>
            </tr>
          </thead>
          <tbody>
            @for (d of poll.alarmDeltas(); track d.alarmId) {
              <tr
                data-testid="alarm-row"
                [attr.data-alarm-id]="d.alarmId"
                [attr.data-kind]="d.kind"
                [class.row-new]="d.kind === 'NEW'"
                [class.row-changed]="d.kind === 'CHANGED'"
              >
                <td>
                  <a [routerLink]="['/streaming']" [queryParams]="{ alarmId: d.alarmId }">{{ d.alarmId }}</a>
                  @if (d.kind === 'NEW') {
                    <span class="badge badge-new" data-testid="new-indicator">NEW</span>
                  } @else if (d.kind === 'CHANGED') {
                    <span class="badge badge-changed" data-testid="changed-indicator">CHG</span>
                  }
                </td>
                <td data-testid="alarm-state">{{ d.currentState }}</td>
                <td>{{ d.current.role }}</td>
                <td>
                  @if (d.current.incidentId) {
                    <a [routerLink]="['/incidents', d.current.incidentId]">{{ d.current.incidentId }}</a>
                  } @else {
                    —
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
        @if (!poll.alarmDeltas().length) {
          <p class="empty-state">No alarms.</p>
        }
      </section>

      <section class="card" aria-labelledby="inc-h">
        <h2 id="inc-h">Incidents (forming)</h2>
        <ul class="inc-list">
          @for (d of poll.incidentDeltas(); track d.incidentId) {
            <li
              data-testid="incident-row"
              [class.row-new]="d.kind === 'NEW'"
              [class.row-changed]="d.kind === 'GREW'"
            >
              <a [routerLink]="['/incidents', d.incidentId]">{{ d.incidentId }}</a>
              — root {{ d.current.rootCauseAlarmType ?? d.current.rootCauseAlarmId }}
              ({{ d.currentChildCount }} children)
              @if (d.kind === 'NEW') {
                <span class="badge badge-new">NEW</span>
              } @else if (d.kind === 'GREW') {
                <span class="badge badge-changed">GREW</span>
              }
            </li>
          }
        </ul>
        @if (!poll.incidentDeltas().length) {
          <p class="empty-state">No incidents.</p>
        }
      </section>
    </div>
  `,
  styles: [
    `
      .head {
        display: flex;
        justify-content: space-between;
        align-items: flex-end;
        flex-wrap: wrap;
        gap: 1rem;
      }
      .controls {
        display: flex;
        align-items: center;
        gap: 0.8rem;
        flex-wrap: wrap;
      }
      .live {
        color: var(--ok);
        font-weight: 700;
      }
      .live.stale {
        color: var(--warn);
      }
      .muted {
        color: var(--text-muted);
      }
      .interval input {
        width: 6rem;
        margin-left: 0.3rem;
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.25rem;
      }
      .grid {
        display: grid;
        grid-template-columns: 3fr 2fr;
        gap: 1rem;
        margin-top: 1rem;
      }
      .inc-list {
        list-style: none;
        padding: 0;
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
      }
      @media (max-width: 800px) {
        .grid {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class StreamingViewComponent implements OnInit {
  readonly poll = inject(LivePollingService);

  ngOnInit(): void {
    this.poll.start();
  }

  onInterval(event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    if (Number.isFinite(value) && value > 0) {
      this.poll.setInterval(value);
    }
  }
}
