import { Injectable } from '@angular/core';
import { environment } from '../../environments/environment';
import { AppEnvironment, IntegrationMode, ServiceBaseUrls } from '../../environments/environment.model';

export type ServiceKey = keyof ServiceBaseUrls;

/**
 * Single resolver for every backend base URL and the mock/real toggle. No application source
 * hard-codes a backend URL — clients call `baseUrl(key)` (spec AC 50, 51, task 16).
 */
@Injectable({ providedIn: 'root' })
export class ApiConfigService {
  private readonly env: AppEnvironment = environment;

  baseUrl(key: ServiceKey): string {
    return this.env.serviceBaseUrls[key];
  }

  get mode(): IntegrationMode {
    return this.env.integrationMode;
  }

  get isMock(): boolean {
    return this.env.integrationMode === 'mock';
  }

  get streamingRefreshIntervalMs(): number {
    return this.env.streamingRefreshIntervalMs;
  }

  get rcaLabelsEnabled(): boolean {
    return this.env.rcaLabelsEnabled;
  }

  get domain(): string {
    return this.env.domain;
  }

  get snapshotId(): string {
    return this.env.snapshotId;
  }

  /** True only when the integration point has a usable base URL (e.g. Simulator labels in prod may be unset). */
  isConfigured(key: ServiceKey): boolean {
    const url = this.env.serviceBaseUrls[key];
    return typeof url === 'string' && url.length > 0;
  }
}
