import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ErrorBannerService } from './core/error-banner.service';

interface NavLink {
  path: string;
  label: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="visually-hidden" href="#main-content">Skip to main content</a>
    <header class="shell-header">
      <span class="shell-brand">Alarm Correlation Platform</span>
      <nav aria-label="Primary">
        <ul class="shell-nav">
          @for (link of links; track link.path) {
            <li>
              <a [routerLink]="link.path" routerLinkActive="active" ariaCurrentWhenActive="page">{{
                link.label
              }}</a>
            </li>
          }
        </ul>
      </nav>
    </header>

    @if (errors.errors().length) {
      <section class="shell-errors" aria-live="assertive">
        @for (err of errors.errors(); track err.service) {
          <div class="error-banner" role="alert">{{ err.message }}</div>
        }
      </section>
    }

    <main id="main-content" class="shell-main" tabindex="-1">
      <router-outlet />
    </main>
  `,
  styles: [
    `
      .shell-header {
        display: flex;
        align-items: center;
        gap: 1.5rem;
        padding: 0.6rem 1rem;
        background: var(--surface);
        border-bottom: 1px solid var(--border);
        flex-wrap: wrap;
      }
      .shell-brand {
        font-weight: 700;
      }
      .shell-nav {
        list-style: none;
        display: flex;
        gap: 0.4rem;
        margin: 0;
        padding: 0;
        flex-wrap: wrap;
      }
      .shell-nav a {
        display: inline-block;
        padding: 0.35rem 0.7rem;
        border-radius: 6px;
        color: var(--text-muted);
        text-decoration: none;
      }
      .shell-nav a.active {
        background: var(--accent-strong);
        color: #fff;
      }
      .shell-errors {
        padding: 0 1rem;
      }
      .shell-main {
        padding: 1rem;
        max-width: 1400px;
        margin: 0 auto;
      }
    `,
  ],
})
export class AppComponent {
  readonly errors = inject(ErrorBannerService);
  readonly links: NavLink[] = [
    { path: '/dashboard', label: 'Dashboard' },
    { path: '/streaming', label: 'Streaming' },
    { path: '/topology', label: 'Topology' },
    { path: '/patterns', label: 'Patterns' },
    { path: '/chatter', label: 'Chatter' },
    { path: '/config', label: 'Config' },
    { path: '/stats', label: 'Stats' },
  ];
}
