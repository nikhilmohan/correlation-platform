import { Injectable, inject, signal } from '@angular/core';
import { catchError, forkJoin, of } from 'rxjs';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { AlarmDetail, IncidentVM } from '../api/models';

@Injectable()
export class IncidentDetailStore {
  private readonly ce = inject(CorrelationEngineClient);
  private readonly am = inject(AlarmManagerClient);

  readonly incident = signal<IncidentVM | null>(null);
  readonly memberAlarms = signal<AlarmDetail[]>([]);
  readonly loading = signal<boolean>(false);
  readonly notFound = signal<boolean>(false);

  load(incidentId: string): void {
    this.loading.set(true);
    this.notFound.set(false);
    this.ce
      .getIncident(incidentId)
      .pipe(catchError(() => of(null)))
      .subscribe((inc) => {
        if (!inc) {
          this.notFound.set(true);
          this.loading.set(false);
          return;
        }
        this.incident.set(inc);
        const memberIds = [inc.rootCauseAlarmId, ...inc.childAlarmIds];
        if (memberIds.length === 0) {
          this.memberAlarms.set([]);
          this.loading.set(false);
          return;
        }
        // Parallel fan-out over GET /alarms/{id} (no batch endpoint).
        forkJoin(memberIds.map((id) => this.am.getAlarm(id).pipe(catchError(() => of(null))))).subscribe(
          (alarms) => {
            this.memberAlarms.set(alarms.filter((a): a is AlarmDetail => a !== null));
            this.loading.set(false);
          },
        );
      });
  }
}
