import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ThemeService } from './theme.service';

function freshService(): ThemeService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [ThemeService] });
  return TestBed.inject(ThemeService);
}

describe('ThemeService', () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    vi.restoreAllMocks();
  });

  it('defaults to dark when localStorage is empty and prefers-color-scheme is not light', () => {
    localStorage.clear();
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: false } as MediaQueryList);
    const svc = freshService();
    expect(svc.theme()).toBe('dark');
  });

  it('seeds from localStorage when set to light', () => {
    localStorage.setItem('acp-theme', 'light');
    const svc = freshService();
    expect(svc.theme()).toBe('light');
  });

  it('toggle flips dark → light → dark and reflects data-theme + persists', () => {
    localStorage.clear();
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: false } as MediaQueryList);
    const svc = freshService();

    TestBed.tick();
    expect(svc.theme()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('acp-theme')).toBe('dark');

    svc.toggle();
    TestBed.tick();
    expect(svc.theme()).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(localStorage.getItem('acp-theme')).toBe('light');

    svc.toggle();
    TestBed.tick();
    expect(svc.theme()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('acp-theme')).toBe('dark');
  });

  it('constructs without throwing when matchMedia is absent', () => {
    localStorage.clear();
    const original = window.matchMedia;
    // Simulate a jsdom/SSR environment with no matchMedia.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).matchMedia = undefined;
    try {
      expect(() => freshService()).not.toThrow();
      expect(freshService().theme()).toBe('dark');
    } finally {
      window.matchMedia = original;
    }
  });
});
