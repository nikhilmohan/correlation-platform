import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { ApiConfigService, ServiceKey } from '../core/api-config.service';
import { ErrorBannerService } from '../core/error-banner.service';
import { LoggerService } from '../core/logger.service';

export type QueryParams = Record<string, string | number | boolean | undefined | null>;

/**
 * Thin Angular HttpClient wrapper shared by every typed client. Resolves the base URL via
 * ApiConfigService (never a hard-coded host), reports structured service-named errors to the
 * ErrorBannerService on failure (spec AC 53), and logs them (LoggerService). Each typed client
 * extends this and exposes contract-true methods returning the frozen view-models.
 */
export abstract class HttpBaseClient {
  protected readonly http = inject(HttpClient);
  protected readonly config = inject(ApiConfigService);
  protected readonly errors = inject(ErrorBannerService);
  protected readonly logger = inject(LoggerService);

  /** Logical service name used for error attribution + the config base-URL key. */
  protected abstract readonly serviceName: string;
  protected abstract readonly serviceKey: ServiceKey;

  protected url(path: string): string {
    const base = this.config.baseUrl(this.serviceKey).replace(/\/+$/, '');
    const suffix = path.startsWith('/') ? path : `/${path}`;
    return `${base}${suffix}`;
  }

  protected params(q: QueryParams): HttpParams {
    let p = new HttpParams();
    for (const [k, v] of Object.entries(q)) {
      if (v !== undefined && v !== null && v !== '') {
        p = p.set(k, String(v));
      }
    }
    return p;
  }

  protected get<T>(path: string, q: QueryParams = {}): Observable<T> {
    this.errors.clear(this.serviceName);
    return this.http
      .get<T>(this.url(path), { params: this.params(q) })
      .pipe(catchError((e) => this.handle<T>(e, 'GET', path)));
  }

  protected post<T>(path: string, body: unknown, q: QueryParams = {}): Observable<T> {
    this.errors.clear(this.serviceName);
    return this.http
      .post<T>(this.url(path), body, { params: this.params(q) })
      .pipe(catchError((e) => this.handle<T>(e, 'POST', path)));
  }

  protected put<T>(path: string, body: unknown, q: QueryParams = {}): Observable<T> {
    this.errors.clear(this.serviceName);
    return this.http
      .put<T>(this.url(path), body, { params: this.params(q) })
      .pipe(catchError((e) => this.handle<T>(e, 'PUT', path)));
  }

  protected patch<T>(path: string, body: unknown, q: QueryParams = {}): Observable<T> {
    this.errors.clear(this.serviceName);
    return this.http
      .patch<T>(this.url(path), body, { params: this.params(q) })
      .pipe(catchError((e) => this.handle<T>(e, 'PATCH', path)));
  }

  protected delete<T>(path: string, body?: unknown, q: QueryParams = {}): Observable<T> {
    this.errors.clear(this.serviceName);
    return this.http
      .request<T>('DELETE', this.url(path), { body, params: this.params(q) })
      .pipe(catchError((e) => this.handle<T>(e, 'DELETE', path)));
  }

  private handle<T>(err: HttpErrorResponse, method: string, path: string): Observable<T> {
    const status: number | 'network' = err.status > 0 ? err.status : 'network';
    const message =
      status === 'network'
        ? `${this.serviceName} is unreachable`
        : `${this.serviceName} returned HTTP ${status}`;
    // 404 is a legitimate "not found" the caller renders as empty/not-found state — not a banner.
    if (status !== 404) {
      this.errors.report({ service: this.serviceName, status, message });
      this.logger.error('api_error', { service: this.serviceName, method, path, status });
    }
    return throwError(() => err);
  }
}
