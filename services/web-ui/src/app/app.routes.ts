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
  {
    path: 'noise',
    title: 'Noise filter',
    loadComponent: () => import('./noise/noise-view.component').then((m) => m.NoiseViewComponent),
  },
  // The former Streaming view + Stats module merged into the unified /alarms view (Part 3) + the
  // graphical /noise view (Part 4). Keep the old paths as redirects so deep links still land.
  { path: 'streaming', redirectTo: 'alarms', pathMatch: 'full' },
  { path: 'stats', redirectTo: 'alarms', pathMatch: 'full' },
  // Topology & trails is no longer a separate page — the geo-site map + in-place site graph now
  // live on the DASHBOARD. The former `/topology` and `/topology/:siteId` routes were removed;
  // trail deep links land on `/dashboard?trailId=<id>` (the embedded map reads the query param).
  {
    path: 'patterns',
    title: 'Pattern review',
    loadComponent: () => import('./patterns/pattern-list.component').then((m) => m.PatternListComponent),
  },
  {
    path: 'incidents/:incidentId',
    title: 'Incident detail',
    loadComponent: () => import('./incident-detail/incident-detail.component').then((m) => m.IncidentDetailComponent),
  },
  {
    path: 'config',
    title: 'Config',
    loadComponent: () => import('./config/model-params-form.component').then((m) => m.ModelParamsFormComponent),
  },
  {
    path: 'chatter',
    title: 'Chatter management',
    loadComponent: () => import('./chatter/chatter-management.component').then((m) => m.ChatterManagementComponent),
  },
  { path: '**', redirectTo: 'dashboard' },
];
