import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

/**
 * ML shell (Change 1). A single top-level "ML" page consolidating the three ML-facing
 * concerns as deep-linkable sub-tabs backed by child routes:
 *   - Pattern mining  → /ml/patterns  (PatternListComponent — discovered/approved review + XAI)
 *   - Noise filtering → /ml/noise     (Noise heatmaps + Chatter management together)
 *   - Config          → /ml/config    (Knowledge model-params, grouped by ML feature)
 *
 * This component owns ONLY the sub-tab nav; each sub-tab's content is the EXISTING feature
 * component, composed via the router-outlet (no rewrites). Noise filtering is the default
 * landing (`/ml` redirects here) as it is the focus of this consolidation.
 */
@Component({
  selector: 'app-ml',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="ml-shell" data-testid="ml-shell">
      <nav class="ml-subnav" aria-label="ML sections">
        <ul>
          <li>
            <a
              routerLink="/ml/patterns"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
              data-testid="ml-subtab-patterns"
              >Pattern mining</a
            >
          </li>
          <li>
            <a
              routerLink="/ml/noise"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
              data-testid="ml-subtab-noise"
              >Noise filtering</a
            >
          </li>
          <li>
            <a
              routerLink="/ml/config"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
              data-testid="ml-subtab-config"
              >Config</a
            >
          </li>
        </ul>
      </nav>

      <section class="ml-body">
        <router-outlet />
      </section>
    </div>
  `,
  styles: [
    `
      .ml-shell {
        display: flex;
        flex-direction: column;
        gap: 1.1rem;
      }
      .ml-subnav ul {
        list-style: none;
        display: flex;
        gap: 0.25rem;
        margin: 0;
        padding: 0.25rem;
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        background: var(--surface);
        flex-wrap: wrap;
        width: fit-content;
      }
      .ml-subnav a {
        display: inline-block;
        padding: 0.4rem 0.9rem;
        border-radius: var(--radius-sm);
        color: var(--text-muted);
        text-decoration: none;
        font-weight: 600;
        transition:
          background 0.15s ease,
          color 0.15s ease;
      }
      .ml-subnav a:hover {
        background: var(--surface-2);
        color: var(--text);
      }
      .ml-subnav a.active {
        background: var(--accent-strong);
        color: var(--on-accent);
        box-shadow: var(--shadow-sm);
      }
      .ml-subnav a:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 2px;
      }
    `,
  ],
})
export class MlComponent {}
