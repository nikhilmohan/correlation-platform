import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import {
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { KnowledgeClient } from '../api/knowledge.client';
import { ErrorBannerService } from '../core/error-banner.service';
import { ModelParam, ModelParamsRecord } from '../api/models';
import { catchError, of } from 'rxjs';

interface ParamForm {
  key: FormControl<string>;
  value: FormControl<number>;
}

/** A labelled group of params (one ML feature) with the form-array index of each member. */
interface ParamGroup {
  id: string;
  heading: string;
  hint: string;
  /** Members carry their ORIGINAL params()/form-array index so the reactive form stays intact. */
  members: { param: ModelParam; index: number }[];
}

/**
 * Group definitions (Change 1, sub-tab 3): organise the flat model-params by ML feature. Each
 * entry matches params whose dotted key starts with one of its prefixes; the first matching group
 * (in order) wins, and anything unmatched falls through to "Other". Purely presentational — the
 * underlying reactive form and its per-key testids are unchanged.
 */
const PARAM_GROUP_DEFS: { id: string; heading: string; hint: string; prefixes: string[] }[] = [
  {
    id: 'noise',
    heading: 'Noise filtering (DBSCAN)',
    hint: 'Clustering parameters that decide which alarms are dropped as noise.',
    prefixes: ['dbscan.'],
  },
  {
    id: 'pattern',
    heading: 'Pattern mining',
    hint: 'Thresholds governing which frequent sequences become candidate patterns.',
    prefixes: ['pattern.', 'mining.', 'support', 'confidence', 'lift', 'minSupport', 'minConfidence'],
  },
  {
    id: 'correlation',
    heading: 'Correlation / session window',
    hint: 'Session-window and correlation grouping parameters.',
    prefixes: ['window.', 'session.', 'correlation.', 'gap'],
  },
];

/**
 * Config module (spec task 13, AC 40-42). Reads the versioned Knowledge model-params record and
 * presents a typed reactive form; each numeric param is validated against its declared min/max
 * CLIENT-SIDE; an out-of-bounds value blocks submit and makes NO API call; a valid submit PUTs
 * the versioned record and confirms the new version.
 */
@Component({
  selector: 'app-model-params-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  template: `
    <h1>Model parameters (Knowledge Service)</h1>
    @if (errors.forService('Knowledge Service'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }

    @if (record(); as rec) {
      <p class="muted">
        record {{ rec.domain }}/{{ rec.recordType }}/{{ rec.recordId }} · version {{ rec.version }} · paramSet
        {{ rec.payload.paramSet }}
      </p>

      <form class="card" [formGroup]="form" (ngSubmit)="submit()">
        <div formArrayName="params">
          @for (group of groups(); track group.id) {
            <fieldset class="param-group" [attr.data-testid]="'param-group-' + group.id">
              <legend>{{ group.heading }}</legend>
              <p class="group-hint">{{ group.hint }}</p>
              @for (member of group.members; track member.param.key) {
                <div class="param-row" [formGroupName]="member.index">
                  <label [attr.for]="'param-' + member.index">{{ member.param.key }}</label>
                  <input
                    type="number"
                    step="any"
                    [id]="'param-' + member.index"
                    [attr.data-testid]="'param-' + member.param.key"
                    formControlName="value"
                  />
                  <span class="bounds">
                    @if (member.param.min !== undefined) {
                      min {{ member.param.min }}
                    }
                    @if (member.param.max !== undefined) {
                      max {{ member.param.max }}
                    }
                    @if (member.param.unit) {
                      {{ member.param.unit }}
                    }
                  </span>
                  @if (paramInvalid(member.index)) {
                    <span class="field-error" role="alert" [attr.data-testid]="'error-' + member.param.key">
                      Value out of bounds
                    </span>
                  }
                </div>
              }
            </fieldset>
          }
        </div>

        <div class="actions">
          <button class="btn" type="submit" data-testid="save-btn" [disabled]="form.invalid || saving()">
            Save
          </button>
          @if (saveStatus()) {
            <span class="status" role="status" data-testid="save-status">{{ saveStatus() }}</span>
          }
        </div>
      </form>
    } @else {
      <p aria-busy="true">Loading parameters…</p>
    }
  `,
  styles: [
    `
      .muted {
        color: var(--text-muted);
      }
      .param-group {
        border: 1px solid var(--border);
        border-radius: var(--radius-sm, 8px);
        padding: 0.4rem 1rem 0.8rem;
        margin: 0 0 1.1rem;
      }
      .param-group legend {
        font-weight: 700;
        padding: 0 0.4rem;
        color: var(--text);
      }
      .group-hint {
        color: var(--text-muted);
        font-size: 0.82rem;
        margin: 0.2rem 0 0.6rem;
      }
      .param-row {
        display: grid;
        grid-template-columns: 1fr 8rem 1fr;
        align-items: center;
        gap: 0.8rem;
        padding: 0.4rem 0;
        border-bottom: 1px dashed var(--border);
      }
      .param-row input {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.3rem;
      }
      .bounds {
        color: var(--text-muted);
        font-size: 0.82rem;
      }
      .field-error {
        color: var(--error);
        grid-column: 1 / -1;
      }
      .actions {
        display: flex;
        gap: 1rem;
        align-items: center;
        margin-top: 0.8rem;
      }
      .status {
        color: var(--ok);
      }
    `,
  ],
})
export class ModelParamsFormComponent implements OnInit {
  private readonly knowledge = inject(KnowledgeClient);
  readonly errors = inject(ErrorBannerService);

  private readonly recordId = 'noise-filter';
  readonly record = signal<ModelParamsRecord | null>(null);
  readonly params = signal<ModelParam[]>([]);
  readonly saveStatus = signal<string>('');
  readonly saving = signal<boolean>(false);

  readonly form = new FormGroup({
    params: new FormArray<FormGroup<ParamForm>>([]),
  });

  /**
   * The numeric params partitioned into feature groups by dotted-key prefix (Change 1). Each
   * member keeps its original form-array index so the template still binds `[formGroupName]="i"`
   * and the per-key testids (`param-<key>`) are preserved. Empty groups are omitted.
   */
  readonly groups = computed<ParamGroup[]>(() => {
    const params = this.params();
    const groups: ParamGroup[] = PARAM_GROUP_DEFS.map((d) => ({
      id: d.id,
      heading: d.heading,
      hint: d.hint,
      members: [],
    }));
    const other: ParamGroup = { id: 'other', heading: 'Other', hint: 'Uncategorised parameters.', members: [] };
    params.forEach((param, index) => {
      const def = PARAM_GROUP_DEFS.find((d) =>
        d.prefixes.some((p) => param.key.toLowerCase().startsWith(p.toLowerCase()) || param.key.toLowerCase().includes(p.toLowerCase())),
      );
      const target = def ? groups.find((g) => g.id === def.id)! : other;
      target.members.push({ param, index });
    });
    return [...groups, other].filter((g) => g.members.length > 0);
  });

  private get paramsArray(): FormArray<FormGroup<ParamForm>> {
    return this.form.controls.params;
  }

  ngOnInit(): void {
    this.knowledge
      .getModelParams(this.recordId)
      .pipe(catchError(() => of(null)))
      .subscribe((rec) => {
        if (rec) {
          this.record.set(rec);
          this.buildForm(rec.payload.params);
        }
      });
  }

  private buildForm(params: ModelParam[]): void {
    const numeric = params.filter((p) => typeof p.value === 'number');
    this.params.set(numeric);
    this.paramsArray.clear();
    for (const p of numeric) {
      this.paramsArray.push(
        new FormGroup<ParamForm>({
          key: new FormControl(p.key, { nonNullable: true }),
          value: new FormControl(p.value as number, {
            nonNullable: true,
            validators: this.boundsValidator(p),
          }),
        }),
      );
    }
  }

  private boundsValidator(p: ModelParam): ValidatorFn[] {
    const v: ValidatorFn[] = [Validators.required];
    if (p.min !== undefined) {
      v.push(Validators.min(p.min));
    }
    if (p.max !== undefined) {
      v.push(Validators.max(p.max));
    }
    return v;
  }

  paramInvalid(i: number): boolean {
    const ctrl = this.paramsArray.at(i)?.controls.value;
    return !!ctrl && ctrl.invalid && (ctrl.dirty || ctrl.touched);
  }

  submit(): void {
    if (this.form.invalid) {
      this.paramsArray.markAllAsTouched();
      return; // invalid: no API call (AC 42)
    }
    const rec = this.record();
    if (!rec) {
      return;
    }
    this.saving.set(true);
    const updatedParams: ModelParam[] = rec.payload.params.map((orig) => {
      const group = this.paramsArray.controls.find((g) => g.controls.key.value === orig.key);
      return group ? { ...orig, value: group.controls.value.value } : orig;
    });
    this.knowledge
      .updateModelParams(this.recordId, { paramSet: rec.payload.paramSet, params: updatedParams }, 'operator')
      .pipe(catchError(() => of(null)))
      .subscribe((res) => {
        this.saving.set(false);
        if (res) {
          this.record.set(res);
          this.saveStatus.set(`Saved — version ${res.version}`);
        }
      });
  }
}
