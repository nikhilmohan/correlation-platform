import { Injectable, computed, signal } from '@angular/core';

/**
 * Tiny shared coordinator for the two long-running dashboard actions that must NEVER run
 * concurrently: "Start ingestion" (drives a Simulator run) and "Reset" (purges live alarms +
 * correlation state and spins until the topology is all-green again).
 *
 * Each button flips its own busy signal here on start/finish; both buttons disable themselves while
 * EITHER action is busy (`otherBusy`). Kept as a root singleton (not per-dashboard state) so the two
 * standalone sibling components can share it without a parent input wiring. Signals only — no HTTP,
 * no business logic; the buttons own their own flows.
 */
@Injectable({ providedIn: 'root' })
export class DashboardActionsService {
  /** True while a Simulator ingestion run is active (owned by the ingestion button). */
  readonly ingesting = signal<boolean>(false);
  /** True while a live-alarm + correlation reset is in flight (owned by the reset button). */
  readonly resetting = signal<boolean>(false);

  /** True when EITHER action is busy — used to mutually disable the buttons. */
  readonly anyBusy = computed(() => this.ingesting() || this.resetting());
}
