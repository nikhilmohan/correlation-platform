import '@analogjs/vite-plugin-angular/setup-vitest';
import { getTestBed } from '@angular/core/testing';
import {
  BrowserDynamicTestingModule,
  platformBrowserDynamicTesting,
} from '@angular/platform-browser-dynamic/testing';

getTestBed().initTestEnvironment(BrowserDynamicTestingModule, platformBrowserDynamicTesting());

// MapLibre GL and Cytoscape both observe their container with ResizeObserver, which jsdom does
// not implement. Provide a no-op so the guarded init paths can run without throwing. The WebGL
// guard (canvas.getContext) is left as-is so map/graph construction is skipped under jsdom.
globalThis.ResizeObserver = class {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
} as any;
