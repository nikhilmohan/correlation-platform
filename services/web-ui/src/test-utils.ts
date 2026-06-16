import { Provider } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, Routes } from '@angular/router';
import { mockBackendInterceptor } from './app/core/mock-backend.interceptor';

/** Standard test providers: mock-backed HttpClient + a router. Mock mode is the env default. */
export function testProviders(routes: Routes = []): Provider[] {
  return [provideRouter(routes), provideHttpClient(withInterceptors([mockBackendInterceptor]))];
}

export function flush(ms = 0): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
