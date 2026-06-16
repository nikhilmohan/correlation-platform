import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
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
          @for (param of params(); track param.key; let i = $index) {
            <div class="param-row" [formGroupName]="i">
              <label [attr.for]="'param-' + i">{{ param.key }}</label>
              <input
                type="number"
                step="any"
                [id]="'param-' + i"
                [attr.data-testid]="'param-' + param.key"
                formControlName="value"
              />
              <span class="bounds">
                @if (param.min !== undefined) {
                  min {{ param.min }}
                }
                @if (param.max !== undefined) {
                  max {{ param.max }}
                }
                @if (param.unit) {
                  {{ param.unit }}
                }
              </span>
              @if (paramInvalid(i)) {
                <span class="field-error" role="alert" [attr.data-testid]="'error-' + param.key">
                  Value out of bounds
                </span>
              }
            </div>
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
