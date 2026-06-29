import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { AppComponent } from './app.component';
import { ThemeService } from './core/theme.service';
import { testProviders } from '../test-utils';

describe('AppComponent — theme toggle', () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  function render() {
    TestBed.configureTestingModule({ providers: [...testProviders()] });
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders a theme toggle with an accessible label', () => {
    const fixture = render();
    const btn = fixture.nativeElement.querySelector('[data-testid="theme-toggle"]') as HTMLButtonElement;
    expect(btn).toBeTruthy();
    expect(btn.getAttribute('aria-label')).toBeTruthy();
  });

  it('aria-pressed reflects the active theme and flips on click', () => {
    const fixture = render();
    const theme = TestBed.inject(ThemeService);
    const btn = fixture.nativeElement.querySelector('[data-testid="theme-toggle"]') as HTMLButtonElement;

    const initialPressed = theme.theme() === 'light';
    expect(btn.getAttribute('aria-pressed')).toBe(String(initialPressed));

    btn.click();
    fixture.detectChanges();

    expect(btn.getAttribute('aria-pressed')).toBe(String(theme.theme() === 'light'));
    expect(theme.theme() === 'light').toBe(!initialPressed);
  });
});
