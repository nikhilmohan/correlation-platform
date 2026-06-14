import { Injectable, computed, signal } from '@angular/core';

export interface ServiceError {
  readonly service: string;
  readonly status: number | 'network';
  readonly message: string;
}

/**
 * Per-module error surface. A 5xx/network failure in one integration point produces a
 * structured, service-named error message; other modules are unaffected (spec AC 53).
 * Errors are keyed by `service` so each module renders only its own.
 */
@Injectable({ providedIn: 'root' })
export class ErrorBannerService {
  private readonly errorsSig = signal<ReadonlyMap<string, ServiceError>>(new Map());

  readonly errors = computed(() => Array.from(this.errorsSig().values()));

  report(error: ServiceError): void {
    const next = new Map(this.errorsSig());
    next.set(error.service, error);
    this.errorsSig.set(next);
  }

  clear(service: string): void {
    if (!this.errorsSig().has(service)) {
      return;
    }
    const next = new Map(this.errorsSig());
    next.delete(service);
    this.errorsSig.set(next);
  }

  forService(service: string): ServiceError | undefined {
    return this.errorsSig().get(service);
  }
}
