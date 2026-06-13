import { Injectable } from '@angular/core';
import { environment } from '../../environments/environment';

type Level = 'debug' | 'info' | 'warn' | 'error';
const ORDER: Record<Level, number> = { debug: 0, info: 1, warn: 2, error: 3 };

/** Client-side structured JSON logging (level from env). No PII; service/endpoint/status only. */
@Injectable({ providedIn: 'root' })
export class LoggerService {
  private readonly threshold = ORDER[environment.logLevel];

  private emit(level: Level, message: string, context?: Record<string, unknown>): void {
    if (ORDER[level] < this.threshold) {
      return;
    }
    const entry = { ts: new Date().toISOString(), level, message, ...context };
    // eslint-disable-next-line no-console
    (console[level] ?? console.log)(JSON.stringify(entry));
  }

  debug(message: string, context?: Record<string, unknown>): void {
    this.emit('debug', message, context);
  }
  info(message: string, context?: Record<string, unknown>): void {
    this.emit('info', message, context);
  }
  warn(message: string, context?: Record<string, unknown>): void {
    this.emit('warn', message, context);
  }
  error(message: string, context?: Record<string, unknown>): void {
    this.emit('error', message, context);
  }
}
