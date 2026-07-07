import { Routes } from '@angular/router';

/** Lazy-loaded standalone components per module (spec Non-functional → deep-linkable routes). */
export const APP_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard',
    loadComponent: () => import('./dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'alarms',
    title: 'Alarms',
    loadComponent: () => import('./alarms/alarms.component').then((m) => m.AlarmsComponent),
  },
  // ---- ML page (Change 1): a single top-level tab with three deep-linkable sub-tabs. ----
  // The shell owns the sub-tab nav; each child route renders an EXISTING feature component.
  // `/ml` lands on Noise filtering (the focus of this consolidation).
  {
    path: 'ml',
    title: 'ML',
    loadComponent: () => import('./ml/ml.component').then((m) => m.MlComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'noise' },
      {
        path: 'patterns',
        title: 'ML · Pattern mining',
        loadComponent: () => import('./patterns/pattern-list.component').then((m) => m.PatternListComponent),
      },
      {
        path: 'noise',
        title: 'ML · Noise filtering',
        loadComponent: () => import('./ml/noise-filtering.component').then((m) => m.NoiseFilteringComponent),
      },
      {
        path: 'config',
        title: 'ML · Config',
        loadComponent: () => import('./config/model-params-form.component').then((m) => m.ModelParamsFormComponent),
      },
    ],
  },
  // The former Streaming view + Stats module merged into the unified /alarms view (Part 3) + the
  // graphical /noise view (Part 4). Keep the old paths as redirects so deep links still land.
  { path: 'streaming', redirectTo: 'alarms', pathMatch: 'full' },
  { path: 'stats', redirectTo: 'alarms', pathMatch: 'full' },
  // Topology & trails is no longer a separate page — the geo-site map + in-place site graph now
  // live on the DASHBOARD. The former `/topology` and `/topology/:siteId` routes were removed;
  // trail deep links land on `/dashboard?trailId=<id>` (the embedded map reads the query param).
  //
  // Patterns / Noise / Chatter / Config are now sub-tabs of the ML page. Keep the OLD top-level
  // paths as redirects so existing deep links (and the /noise → /chatter cross-link) still land.
  { path: 'patterns', redirectTo: 'ml/patterns', pathMatch: 'full' },
  { path: 'noise', redirectTo: 'ml/noise', pathMatch: 'full' },
  { path: 'chatter', redirectTo: 'ml/noise', pathMatch: 'full' },
  { path: 'config', redirectTo: 'ml/config', pathMatch: 'full' },
  {
    path: 'incidents/:incidentId',
    title: 'Incident detail',
    loadComponent: () => import('./incident-detail/incident-detail.component').then((m) => m.IncidentDetailComponent),
  },
  { path: '**', redirectTo: 'dashboard' },
];
