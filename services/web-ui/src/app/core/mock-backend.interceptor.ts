import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';
import { ApiConfigService } from './api-config.service';
import { MOCK_NOT_FOUND, MOCK_FIXTURES } from './mock-fixtures';

/**
 * Mock backend (active only under `INTEGRATION_MODE=mock`). Serves the frozen producer shapes
 * from in-memory fixtures so unit/component tests and the mock dev profile run with NO real HTTP
 * call (spec AC 50). Under `real` mode this interceptor is a no-op and calls pass through to the
 * Compose service addresses. An Angular HttpInterceptor (rather than a service worker) keeps the
 * mock transport dependency-light and fully deterministic in jsdom.
 */
export function mockBackendInterceptor(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  const config = inject(ApiConfigService);
  if (!config.isMock) {
    return next(req);
  }
  const handler = MOCK_FIXTURES.find((h) => h.matches(req));
  if (!handler) {
    return next(req);
  }
  const body = handler.respond(req);
  // A handler may signal a 404 (e.g. an alarm id that does not exist) by returning the
  // MOCK_NOT_FOUND sentinel — mirror the real backend so by-id resilience is exercised.
  if (body === MOCK_NOT_FOUND) {
    return throwError(
      () => new HttpErrorResponse({ status: 404, statusText: 'Not Found', url: req.url }),
    );
  }
  return of(new HttpResponse({ status: 200, body }));
}
