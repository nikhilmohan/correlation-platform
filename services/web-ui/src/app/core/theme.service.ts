import { Injectable, Signal, effect, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'acp-theme';

/**
 * Whole-UI light/dark theme. The active theme is reflected onto
 * `document.documentElement[data-theme]` (CSS palette in `styles.css` overrides on
 * `[data-theme="light"]`) and persisted to localStorage. Seeded from localStorage, else the
 * OS `prefers-color-scheme`, else dark. Root singleton so it is constructed before any canvas
 * builds and the `data-theme` attribute is set synchronously before any `getComputedStyle`.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly themeSig = signal<Theme>(ThemeService.initialTheme());

  /** The active theme as a read-only signal. */
  readonly theme: Signal<Theme> = this.themeSig.asReadonly();

  constructor() {
    effect(() => {
      const t = this.themeSig();
      // Set data-theme for BOTH values (dark explicitly too) so it is always testable.
      document.documentElement.setAttribute('data-theme', t);
      try {
        localStorage.setItem(STORAGE_KEY, t);
      } catch {
        // Storage may be unavailable (private mode / jsdom quota) — theme still applies in-memory.
      }
    });
  }

  toggle(): void {
    this.themeSig.update((t) => (t === 'dark' ? 'light' : 'dark'));
  }

  setTheme(theme: Theme): void {
    this.themeSig.set(theme);
  }

  private static initialTheme(): Theme {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'light' || stored === 'dark') {
        return stored;
      }
    } catch {
      // ignore unreadable storage
    }
    if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
      if (window.matchMedia('(prefers-color-scheme: light)').matches) {
        return 'light';
      }
    }
    return 'dark';
  }
}
