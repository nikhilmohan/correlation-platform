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
    path: 'streaming',
    title: 'Streaming (live)',
    loadComponent: () => import('./streaming/streaming-view.component').then((m) => m.StreamingViewComponent),
  },
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
    path: 'stats',
    title: 'Correlation stats',
    loadComponent: () => import('./stats/stats.component').then((m) => m.StatsComponent),
  },
  {
    path: 'chatter',
    title: 'Chatter management',
    loadComponent: () => import('./chatter/chatter-management.component').then((m) => m.ChatterManagementComponent),
  },
  { path: '**', redirectTo: 'dashboard' },
];
