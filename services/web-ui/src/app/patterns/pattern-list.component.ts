import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PatternStore, LifecycleFilter } from './pattern.store';
import { NavigationService } from '../core/navigation.service';
import { ErrorBannerService } from '../core/error-banner.service';
import { PatternView, PatternLifecycle, SequenceElement, SupportingInstance } from '../api/models';
import { alarmTypeLabel, derivePatternName } from './alarm-type-labels';

/** A cascade chip: readable label + whether it is the root cause + whether it is optional. */
interface Chip {
  readonly label: string;
  readonly isRoot: boolean;
  readonly optional: boolean;
}

/**
 * Pattern review & XAI (spec tasks 10-12, AC 34-38, 54). Lists discovered/active patterns as
 * polished, scannable cards: a logical name, the constituent-alarm cascade, humanized metrics +
 * timing, the discovery timestamp, and an evidence list of the REAL supporting-instance
 * (window/provenance) references. Expand for full XAI; approve/reject/edit a draft.
 *
 * Real-data-only: every field shown comes off the Pattern Manager wire. Per-alarm detail
 * (timestamps, node/object) is NOT yet served by the Pattern Store — evidence lists the window
 * and provenance handles that DO exist and flags the gap in-line rather than inventing rows.
 */
@Component({
  selector: 'app-pattern-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [PatternStore],
  imports: [DecimalPipe, PercentPipe, DatePipe, FormsModule],
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
          <li class="card pattern-card" data-testid="pattern-row" [attr.data-pattern-id]="p.patternId">
            <!-- ── Collapsed header ── -->
            <div class="pattern-head">
              <button
                type="button"
                class="expand"
                data-testid="pattern-expand"
                [attr.aria-expanded]="store.expandedId() === p.patternId"
                (click)="onExpand(p.patternId)"
              >
                <span class="chevron" aria-hidden="true">{{ store.expandedId() === p.patternId ? '▾' : '▸' }}</span>
                <span class="pattern-name">{{ patternName(p) }}</span>
              </button>
              <span class="badge" [class]="'tone-' + lifecycleTone(p.lifecycle)" data-testid="pattern-lifecycle">{{
                p.lifecycle
              }}</span>
            </div>

            <!-- Root cause -->
            <p class="rca-line">
              <span class="star" aria-hidden="true">★</span>
              <span class="rca-pretty">Root cause: {{ alarmLabel(p.rootCauseAlarmType) }}</span>
              <span class="visually-hidden" data-testid="rca">RCA {{ p.rootCauseAlarmType }}</span>
            </p>

            <!-- Ordered cascade chips (repeats preserved) -->
            <div class="cascade" aria-label="Alarm cascade">
              @for (c of orderedChips(p); track $index) {
                @if ($index > 0) {
                  <span class="arrow" aria-hidden="true">→</span>
                }
                <span
                  class="chip"
                  [class.chip-root]="c.isRoot"
                  [class.chip-optional]="c.optional"
                  [attr.title]="c.optional ? 'optional' : null"
                >
                  @if (c.isRoot) {
                    <span class="star" aria-hidden="true">★</span>
                  }
                  {{ c.label }}
                </span>
              }
            </div>

            <!-- Metric strip -->
            <div class="metrics metric-grid">
              <span class="chip metric" data-testid="support">Support {{ p.support | percent: '1.0-0' }}</span>
              <span class="chip metric" data-testid="confidence">Confidence {{ p.confidence | percent: '1.0-0' }}</span>
              <span class="chip metric" data-testid="lift">Lift {{ p.lift | number: '1.1-1' }}x</span>
              <span class="chip metric">{{ p.instanceCount }} occurrences</span>
              <span class="chip metric"><span class="star" aria-hidden="true">◷</span> {{ discoveredRelative(p) }}</span>
              <span class="chip metric origin" [attr.title]="faultOrigin(p)">
                <span class="star" aria-hidden="true">⚑</span> {{ faultOriginShort(p) }}
              </span>
            </div>

            <!-- ── Expanded XAI panel ── -->
            @if (store.expandedId() === p.patternId) {
              <div class="xai" data-testid="pattern-xai">
                <section class="xai-section">
                  <h2 class="xai-h">Constituent alarms — {{ distinctChips(p).length }} distinct</h2>
                  <div class="cascade">
                    @for (c of distinctChips(p); track c.label) {
                      <span class="chip" [class.chip-root]="c.isRoot" [class.chip-optional]="c.optional">
                        @if (c.isRoot) {
                          <span class="star" aria-hidden="true">★</span>
                        }
                        {{ c.label }}
                        @if (c.optional) {
                          <span class="chip-tag">optional</span>
                        }
                      </span>
                    }
                  </div>
                </section>

                @if (timingText(p); as timing) {
                  <section class="xai-section">
                    <h2 class="xai-h">Timing</h2>
                    <p class="xai-p">{{ timing }}</p>
                  </section>
                }

                <section class="xai-section">
                  <h2 class="xai-h">Session window</h2>
                  <p class="xai-p">
                    session window: {{ p.sessionWindow?.windowMs }} ms ({{ p.sessionWindow?.type }})
                    @if (windowHuman(p); as wh) {
                      <span class="xai-muted"> · ~{{ wh }}</span>
                    }
                  </p>
                </section>

                <section class="xai-section">
                  <h2 class="xai-h">Structural validation</h2>
                  <p class="xai-p">
                    @if (p.structurallyValidated) {
                      <span class="ok"><span aria-hidden="true">✓</span> Validated</span>
                    } @else {
                      <span class="err"><span aria-hidden="true">✕</span> Not validated</span>
                      @if (p.structuralValidationReason) {
                        — {{ p.structuralValidationReason }}
                      }
                    }
                  </p>
                </section>

                <section class="xai-section">
                  <h2 class="xai-h">Evidence · sample references</h2>
                  <p class="xai-p">
                    supporting instances: {{ instances(p).length }}
                    @if (evidenceSummary(p); as es) {
                      <span class="xai-muted"> · {{ es }}</span>
                    }
                  </p>
                  @if (instances(p).length) {
                    <ul class="evidence-list">
                      @for (inst of shownInstances(p); track $index) {
                        <li class="evidence-item">
                          <code class="ev-id" [attr.title]="inst.sourceWindowId ?? '—'"
                            >{{ shortId(inst.sourceWindowId) }}</code
                          >
                          @if (inst.occurrence?.anchorScenarioId) {
                            <span class="ev-sep" aria-hidden="true">·</span>
                            <span
                              class="ev-anchor"
                              [attr.title]="inst.occurrence?.anchorScenarioId"
                              >{{ readableTail(inst.occurrence?.anchorScenarioId) }}</span
                            >
                          }
                        </li>
                      }
                    </ul>
                    @if (instances(p).length > evidencePreview) {
                      <button
                        type="button"
                        class="evidence-toggle"
                        data-testid="evidence-toggle"
                        [attr.aria-expanded]="evidenceExpanded()"
                        (click)="toggleEvidence()"
                      >
                        @if (evidenceExpanded()) {
                          Show fewer references
                        } @else {
                          Show all {{ instances(p).length }} references
                        }
                      </button>
                    }
                  }
                  <p class="xai-note">
                    Per-alarm detail (timestamps, node/object) is not yet served by the Pattern Store.
                  </p>
                </section>

                <section class="xai-section meta">
                  <span>Trail <code>{{ p.trailId }}</code></span>
                  <span>Domain <code>{{ p.domain }}</code></span>
                  @if (p.createdAt) {
                    <span>Discovered {{ p.createdAt | date: 'medium' }} ({{ discoveredRelative(p) }})</span>
                  }
                  @if (p.updatedAt) {
                    <span>Updated {{ p.updatedAt | date: 'medium' }}</span>
                  }
                </section>

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
                    <button class="btn btn-secondary" type="button" data-testid="edit-btn" (click)="openEdit(p)">
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
                    <label class="opt-label">
                      <input
                        type="checkbox"
                        [attr.data-testid]="'opt-' + $index"
                        [checked]="optionalFlags()[$index]"
                        (change)="setFlag($index, $event)"
                      />
                      {{ alarmLabel(el.alarmType) }}
                    </label>
                  }
                </fieldset>
                <label class="reviewer-label">
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
        color: var(--on-accent);
      }
      .pattern-list {
        list-style: none;
        padding: 0;
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 0.8rem;
      }
      .pattern-card {
        display: flex;
        flex-direction: column;
        gap: 0.55rem;
      }
      .pattern-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 0.5rem;
      }
      .expand {
        background: none;
        border: none;
        color: var(--text);
        text-align: left;
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0;
        flex: 1 1 auto;
      }
      .chevron {
        color: var(--text-muted);
        font-size: 0.9rem;
      }
      .pattern-name {
        font-size: 1.05rem;
        font-weight: 700;
      }
      /* Badge lifecycle tones (reuse .badge shape from styles.css). */
      .badge.tone-ok {
        background: var(--ok);
        color: #06210f;
      }
      .badge.tone-warn {
        background: var(--warn);
        color: #3a2c06;
      }
      .badge.tone-error {
        background: var(--error);
        color: #3f1010;
      }
      .badge.tone-muted {
        background: var(--surface-2);
        color: var(--text-muted);
        border: 1px solid var(--border);
      }
      .rca-line {
        margin: 0;
        display: flex;
        align-items: center;
        gap: 0.4rem;
        color: var(--text);
        font-size: 0.92rem;
      }
      .star {
        color: var(--accent);
      }
      .cascade {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.35rem;
      }
      .arrow {
        color: var(--text-muted);
      }
      .chip {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
        background: var(--surface-2);
        border: 1px solid var(--border);
        border-radius: 999px;
        padding: 0.15rem 0.6rem;
        font-size: 0.82rem;
        color: var(--text);
      }
      .chip-optional {
        border-style: dashed;
        color: var(--text-muted);
        opacity: 0.9;
      }
      .chip-root {
        border-color: var(--accent);
        box-shadow: inset 0 0 0 1px var(--accent);
      }
      .chip-tag {
        font-size: 0.7rem;
        color: var(--text-muted);
        font-style: italic;
      }
      .metric-grid {
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem;
        margin-top: 0.15rem;
      }
      .chip.metric {
        color: var(--text-muted);
        font-size: 0.8rem;
      }
      .chip.origin {
        color: var(--text);
      }
      /* ── Expanded XAI ── */
      .xai {
        margin-top: 0.4rem;
        border-top: 1px solid var(--border);
        padding-top: 0.7rem;
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
      }
      .xai-section {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
      }
      .xai-h {
        margin: 0;
        font-size: 0.78rem;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--text-muted);
        font-weight: 700;
      }
      .xai-p {
        margin: 0;
        font-size: 0.9rem;
      }
      .xai-muted {
        color: var(--text-muted);
      }
      .ok {
        color: var(--ok);
      }
      .err {
        color: var(--error);
      }
      .evidence-list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
      }
      .evidence-item {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        background: var(--surface-2);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.25rem 0.55rem;
        font-size: 0.82rem;
        white-space: nowrap;
        overflow: hidden;
      }
      .ev-id {
        flex: 0 0 auto;
        max-width: 12ch;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .ev-sep {
        color: var(--text-muted);
      }
      .ev-anchor {
        flex: 1 1 auto;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: var(--text-muted);
      }
      .evidence-item code,
      .meta code {
        background: transparent;
        color: var(--accent);
        font-size: 0.82rem;
      }
      .evidence-toggle {
        align-self: flex-start;
        margin-top: 0.15rem;
        background: none;
        border: none;
        padding: 0;
        color: var(--accent);
        font-size: 0.8rem;
        cursor: pointer;
        text-decoration: underline;
      }
      .xai-note {
        margin: 0.1rem 0 0;
        font-size: 0.8rem;
        font-style: italic;
        color: var(--text-muted);
      }
      .meta {
        flex-direction: row;
        flex-wrap: wrap;
        gap: 0.9rem;
        color: var(--text-muted);
        font-size: 0.82rem;
      }
      .actions {
        display: flex;
        gap: 0.5rem;
        margin-top: 0.2rem;
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
        border: 1px solid var(--border);
        border-radius: 8px;
      }
      .opt-label,
      .reviewer-label {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
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

  /** Evidence list is progressively disclosed: preview N rows, toggle reveals the rest. */
  readonly evidencePreview = 3;
  readonly evidenceExpanded = signal(false);

  private readonly relTime = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

  ngOnInit(): void {
    this.store.load('draft');
  }

  setFilter(filter: LifecycleFilter): void {
    this.store.load(filter);
  }

  // ── Derived presentation ──

  patternName(p: PatternView): string {
    return derivePatternName(p);
  }

  alarmLabel(alarmType: string): string {
    return alarmTypeLabel(alarmType);
  }

  lifecycleTone(lifecycle: PatternLifecycle): 'ok' | 'warn' | 'error' | 'muted' {
    switch (lifecycle) {
      case 'approved':
        return 'ok';
      case 'draft':
        return 'warn';
      case 'rejected':
        return 'error';
      default:
        return 'muted';
    }
  }

  /** Ordered cascade with repeats preserved. */
  orderedChips(p: PatternView): Chip[] {
    return p.sequence.map((el) => ({
      label: this.alarmLabel(el.alarmType),
      isRoot: el.alarmType === p.rootCauseAlarmType,
      optional: el.optional,
    }));
  }

  /** Distinct constituent alarms, first-seen order; optional only if optional in ALL occurrences. */
  distinctChips(p: PatternView): Chip[] {
    const order: string[] = [];
    const groups = new Map<string, SequenceElement[]>();
    for (const el of p.sequence) {
      const group = groups.get(el.alarmType);
      if (group) {
        group.push(el);
      } else {
        groups.set(el.alarmType, [el]);
        order.push(el.alarmType);
      }
    }
    return order.map((alarmType) => {
      const occ = groups.get(alarmType) ?? [];
      return {
        label: this.alarmLabel(alarmType),
        isRoot: alarmType === p.rootCauseAlarmType,
        optional: occ.length > 0 && occ.every((e) => e.optional),
      };
    });
  }

  instances(p: PatternView): SupportingInstance[] {
    return p.supportingInstances ?? [];
  }

  /** The evidence rows currently shown: first `evidencePreview`, or all when expanded. */
  shownInstances(p: PatternView): SupportingInstance[] {
    const all = this.instances(p);
    return this.evidenceExpanded() ? all : all.slice(0, this.evidencePreview);
  }

  onExpand(patternId: string): void {
    this.evidenceExpanded.set(false);
    this.store.toggleExpand(patternId);
  }

  toggleEvidence(): void {
    this.evidenceExpanded.update((v) => !v);
  }

  /**
   * Readable tail of an anchor / fault-origin id: strips a leading `cb-<uuid>:` codebook prefix
   * so `cb-ad0970bc-…:Port:N0-LC1-P1` reads as `Port:N0-LC1-P1`. Non-prefixed values pass through.
   */
  readableTail(id: string | null | undefined): string {
    if (!id) {
      return '';
    }
    return id.replace(/^cb-[0-9a-f-]+:/i, '');
  }

  /** Short, monospace-friendly id: the last path/dash segment, capped so long UUIDs don't sprawl. */
  shortId(id: string | null | undefined): string {
    if (!id) {
      return '—';
    }
    const tail = id.includes('-') ? (id.split('-').pop() ?? id) : id;
    return tail.length > 10 ? `…${tail.slice(-8)}` : tail;
  }

  /** Fault origin / codebook status chip text (full value, used in title=). */
  faultOrigin(p: PatternView): string {
    const anchored = this.instances(p).find((i) => i.occurrence?.anchorScenarioId)?.occurrence
      ?.anchorScenarioId;
    if (anchored) {
      return anchored;
    }
    if (p.codebookMatchId) {
      return p.codebookMatchId;
    }
    return 'Unexplained (novel)';
  }

  /** Readable-tail form of the fault-origin chip (strips the cb-<uuid>: codebook prefix). */
  faultOriginShort(p: PatternView): string {
    return this.readableTail(this.faultOrigin(p)) || this.faultOrigin(p);
  }

  /**
   * Human one-liner for the evidence header: the distinct fault-origins (readable tails, deduped,
   * capped) and the count of distinct source windows. Empty when there's nothing meaningful to say.
   */
  evidenceSummary(p: PatternView): string {
    const all = this.instances(p);
    if (!all.length) {
      return '';
    }
    const origins = [
      ...new Set(
        all
          .map((i) => this.readableTail(i.occurrence?.anchorScenarioId))
          .filter((t): t is string => t.length > 0),
      ),
    ];
    const windows = new Set(all.map((i) => i.sourceWindowId).filter((w): w is string => !!w));
    const parts: string[] = [];
    if (origins.length) {
      const shown = origins.slice(0, 2).join(', ');
      const label = origins.length === 1 ? 'fault origin' : 'fault origins';
      parts.push(`${label} ${shown}${origins.length > 2 ? ` +${origins.length - 2} more` : ''}`);
    }
    if (windows.size) {
      parts.push(`across ${windows.size} source window${windows.size === 1 ? '' : 's'}`);
    }
    return parts.join(' · ');
  }

  /** Relative discovery time (dependency-free) from createdAt. */
  discoveredRelative(p: PatternView): string {
    if (!p.createdAt) {
      return 'discovered —';
    }
    const then = Date.parse(p.createdAt);
    if (Number.isNaN(then)) {
      return 'discovered —';
    }
    const diffMs = then - Date.now();
    const abs = Math.abs(diffMs);
    const units: ReadonlyArray<[Intl.RelativeTimeFormatUnit, number]> = [
      ['year', 31536000000],
      ['month', 2592000000],
      ['day', 86400000],
      ['hour', 3600000],
      ['minute', 60000],
    ];
    for (const [unit, ms] of units) {
      if (abs >= ms) {
        return `discovered ${this.relTime.format(Math.round(diffMs / ms), unit)}`;
      }
    }
    return `discovered ${this.relTime.format(Math.round(diffMs / 1000), 'second')}`;
  }

  /** Defensive numeric read — returns a number only for real numeric fields, else null. */
  private num(rec: Readonly<Record<string, unknown>> | undefined, key: string): number | null {
    if (!rec) {
      return null;
    }
    const v = rec[key];
    return typeof v === 'number' ? v : null;
  }

  humanMs(ms: number): string {
    if (ms < 1000) {
      return `${Math.round(ms)}ms`;
    }
    if (ms < 60000) {
      return `${(ms / 1000).toFixed(1)}s`;
    }
    return `${(ms / 60000).toFixed(1)}m`;
  }

  /** Humanized timing string, joining present parts with " · "; empty string if none present. */
  timingText(p: PatternView): string {
    const t = p.timing as Readonly<Record<string, unknown>> | undefined;
    const parts: string[] = [];
    const timeframe = this.num(t, 'timeframeMs');
    const median = this.num(t, 'medianInterArrivalMs');
    const max = this.num(t, 'maxInterArrivalMs');
    const stddev = this.num(t, 'stddevInterArrivalMs');
    if (timeframe !== null) {
      parts.push(`spans ~${this.humanMs(timeframe)}`);
    }
    if (median !== null) {
      parts.push(`median gap ${this.humanMs(median)}`);
    }
    if (max !== null) {
      parts.push(`max gap ${this.humanMs(max)}`);
    }
    if (stddev !== null) {
      parts.push(`jitter ±${this.humanMs(stddev)}`);
    }
    return parts.join(' · ');
  }

  windowHuman(p: PatternView): string {
    const ms = p.sessionWindow?.windowMs;
    return typeof ms === 'number' ? this.humanMs(ms) : '';
  }

  // ── Edit placeholder ──

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
    const sequenceFlags = this.optionalFlags().map((optional, index) => ({ index, optional }));
    this.store.edit(p.patternId, { sequenceFlags, reviewer: this.reviewer });
    this.editing.set(null);
  }
}
