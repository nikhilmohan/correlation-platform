import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { IngestionButtonComponent } from './ingestion-button.component';
import { SimulatorClient } from '../api/simulator.client';
import { ApiConfigService, ServiceKey } from '../core/api-config.service';
import { flush } from '../../test-utils';
import { SynthProgress, SynthStatusResponse, SynthSummaryModel } from '../api/models';

const SIM_BASE = 'https://sim.example/api/simulator';

/** Real-mode config so requests reach the HttpTestingController at a known base URL. */
function realConfig(): Partial<ApiConfigService> & { isMock: boolean } {
  return {
    isMock: false,
    streamingRefreshIntervalMs: 1500,
    baseUrl: (k: ServiceKey) => (k === 'simulator' ? SIM_BASE : `https://real.example/${k}`),
    isConfigured: () => true,
  } as unknown as Partial<ApiConfigService> & { isMock: boolean };
}

const P0: SynthProgress = { alarmsEmitted: 0, alarmsTotal: 0, alignedEmitted: 0, nonAlignedEmitted: 0 };

function statusBody(over: Partial<SynthStatusResponse> = {}): SynthStatusResponse {
  return { status: 'idle', runId: null, progress: P0, summary: null, ...over };
}

function summary(over: Partial<SynthSummaryModel> = {}): SynthSummaryModel {
  return {
    runId: 'r1',
    status: 'completed',
    alarmsEmitted: 120,
    alignedFraction: 0.5,
    enrichmentSafeCount: 0,
    shortfallCascades: 0,
    enrichmentConflictPatterns: [],
    failureReason: null,
    startedAt: '2026-01-01T00:00:00Z',
    completedAt: '2026-01-01T00:01:00Z',
    ...over,
  };
}

describe('IngestionButtonComponent', () => {
  let http: HttpTestingController;

  function setup() {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfigService, useValue: realConfig() },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  }

  /** Answer the initial ngOnInit GET /synth/status with the given body. */
  function answerInitialStatus(body: SynthStatusResponse = statusBody()): void {
    const req = http.expectOne(`${SIM_BASE}/synth/status`);
    expect(req.request.method).toBe('GET');
    req.flush(body);
  }

  beforeEach(() => setup());

  it('SimulatorClient builds the correct URLs from the configured base', async () => {
    const client = TestBed.inject(SimulatorClient);
    client.startRun({ totalAlarms: 5 }).subscribe();
    const run = http.expectOne(`${SIM_BASE}/synth/run`);
    expect(run.request.method).toBe('POST');
    expect(run.request.body).toEqual({ totalAlarms: 5 });
    run.flush({ runId: 'r1', status: 'running' });

    client.getStatus().subscribe();
    const st = http.expectOne(`${SIM_BASE}/synth/status`);
    expect(st.request.method).toBe('GET');
    st.flush(statusBody());
    await flush();
    http.verify();
  });

  it('renders "Start ingestion" idle and no spinner', async () => {
    const fixture = TestBed.createComponent(IngestionButtonComponent);
    fixture.detectChanges();
    answerInitialStatus();
    await flush();
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement;
    expect(btn.textContent).toContain('Start ingestion');
    expect(btn.disabled).toBe(false);
    expect(btn.getAttribute('aria-busy')).toBe('false');
    expect(fixture.nativeElement.querySelector('.spinner')).toBeNull();
    http.verify();
  });

  it('click → POST /synth/run, enters running (aria-busy, disabled, spinner)', async () => {
    const fixture = TestBed.createComponent(IngestionButtonComponent);
    fixture.detectChanges();
    answerInitialStatus();
    await flush();
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement;
    btn.click();
    fixture.detectChanges();

    // 202 Accepted for the run.
    const run = http.expectOne(`${SIM_BASE}/synth/run`);
    expect(run.request.method).toBe('POST');
    expect(run.request.body).toEqual({});
    run.flush({ runId: 'r1', status: 'running' }, { status: 202, statusText: 'Accepted' });
    await flush();

    // Poll fires immediately after the run is accepted — report a running status with progress.
    const poll = http.expectOne(`${SIM_BASE}/synth/status`);
    poll.flush(statusBody({ status: 'running', runId: 'r1', progress: { ...P0, alarmsEmitted: 10, alarmsTotal: 100 } }));
    await flush();
    fixture.detectChanges();

    expect(btn.disabled).toBe(true);
    expect(btn.getAttribute('aria-busy')).toBe('true');
    expect(fixture.nativeElement.querySelector('.spinner')).toBeTruthy();
    expect(btn.textContent).toContain('Ingesting');
    const progress = fixture.nativeElement.querySelector('[data-testid="ingestion-progress"]') as HTMLElement;
    expect(progress.textContent).toContain('10 / 100');
    fixture.destroy();
  });

  it('completed → returns to idle with a success summary line', async () => {
    const fixture = TestBed.createComponent(IngestionButtonComponent);
    fixture.detectChanges();
    answerInitialStatus();
    await flush();

    (fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement).click();
    http.expectOne(`${SIM_BASE}/synth/run`).flush({ runId: 'r1', status: 'running' }, { status: 202, statusText: 'Accepted' });
    await flush();
    http.expectOne(`${SIM_BASE}/synth/status`).flush(statusBody({ status: 'running', runId: 'r1' }));
    await flush();
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement).disabled).toBe(true);

    // Next scheduled poll (~1.5s) reports completion.
    await flush(1600);
    http.expectOne(`${SIM_BASE}/synth/status`).flush(
      statusBody({ status: 'completed', runId: 'r1', summary: summary({ alarmsEmitted: 120 }) }),
    );
    await flush();
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
    expect(btn.textContent).toContain('Start ingestion');
    const success = fixture.nativeElement.querySelector('[data-testid="ingestion-success"]') as HTMLElement;
    expect(success.textContent).toContain('120 alarms emitted');
    fixture.destroy();
  });

  it('failed → returns to idle and shows failureReason', async () => {
    const fixture = TestBed.createComponent(IngestionButtonComponent);
    fixture.detectChanges();
    answerInitialStatus();
    await flush();

    (fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement).click();
    http.expectOne(`${SIM_BASE}/synth/run`).flush({ runId: 'r1', status: 'running' }, { status: 202, statusText: 'Accepted' });
    await flush();
    http.expectOne(`${SIM_BASE}/synth/status`).flush(
      statusBody({ status: 'failed', runId: 'r1', summary: summary({ status: 'failed', failureReason: 'kafka down' }) }),
    );
    await flush();
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
    const err = fixture.nativeElement.querySelector('[data-testid="ingestion-error"]') as HTMLElement;
    expect(err.textContent).toContain('kafka down');
    fixture.destroy();
  });

  it('409 on start → enters running (not an error) and polls', async () => {
    const fixture = TestBed.createComponent(IngestionButtonComponent);
    fixture.detectChanges();
    answerInitialStatus();
    await flush();

    (fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectOne(`${SIM_BASE}/synth/run`).flush(
      { detail: 'a run is already active', runId: 'r-existing' },
      { status: 409, statusText: 'Conflict' },
    );
    await flush();

    // No error line; still running; polling begins.
    expect(fixture.nativeElement.querySelector('[data-testid="ingestion-error"]')).toBeNull();
    const poll = http.expectOne(`${SIM_BASE}/synth/status`);
    poll.flush(statusBody({ status: 'running', runId: 'r-existing', progress: { ...P0, alarmsEmitted: 3, alarmsTotal: 50 } }));
    await flush();
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    expect(btn.getAttribute('aria-busy')).toBe('true');
    fixture.destroy();
  });

  it('422 on start → returns to idle with a generic error line', async () => {
    const fixture = TestBed.createComponent(IngestionButtonComponent);
    fixture.detectChanges();
    answerInitialStatus();
    await flush();

    (fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement).click();
    http.expectOne(`${SIM_BASE}/synth/run`).flush({ detail: [] }, { status: 422, statusText: 'Unprocessable Entity' });
    await flush();
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
    const err = fixture.nativeElement.querySelector('[data-testid="ingestion-error"]') as HTMLElement;
    expect(err.textContent).toMatch(/invalid/i);
    fixture.destroy();
  });

  it('initial load reflects an already-running run immediately', async () => {
    const fixture = TestBed.createComponent(IngestionButtonComponent);
    fixture.detectChanges();
    // ngOnInit status says a run is already active.
    answerInitialStatus(statusBody({ status: 'running', runId: 'r-elsewhere', progress: { ...P0, alarmsEmitted: 7, alarmsTotal: 200 } }));
    await flush();

    // It begins polling — answer the first scheduled poll so the request queue stays clean.
    const poll = http.expectOne(`${SIM_BASE}/synth/status`);
    poll.flush(statusBody({ status: 'running', runId: 'r-elsewhere', progress: { ...P0, alarmsEmitted: 7, alarmsTotal: 200 } }));
    await flush();
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector('[data-testid="start-ingestion-btn"]') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    expect(btn.getAttribute('aria-busy')).toBe('true');
    expect(btn.textContent).toContain('Ingesting');
    const progress = fixture.nativeElement.querySelector('[data-testid="ingestion-progress"]') as HTMLElement;
    expect(progress.textContent).toContain('7 / 200');
    fixture.destroy();
  });
});
