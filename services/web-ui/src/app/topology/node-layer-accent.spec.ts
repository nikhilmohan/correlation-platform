import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { SiteGraphComponent } from './site-graph.component';

/**
 * Operator-feedback refinement: the device-node box is now a UNIFORM GREY OUTLINE (no per-layer
 * border colour), so the logical layer is conveyed by a SMALL layer-colour ACCENT DOT overlaid per
 * node (mirroring the amber expand-cue overlay). These cover CHANGE 4: the layerDotMarkers computed
 * maps each node's `layer` to its LAYER_COLORS swatch (matching the layer legend), and the bottom-
 * left dot position is derived from the node's rendered position.
 */
async function mount() {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(SiteGraphComponent);
  fixture.componentRef.setInput('siteId', 'Site:LON');
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('CHANGE 4 — per-node layer-colour accent dot', () => {
  it('layerDotMarkers maps each node layer to its LAYER_COLORS swatch (matches the legend key)', async () => {
    const fixture = await mount();
    const cmp = fixture.componentInstance;

    // Seed overlay markers the way refreshOverlayMarkers() would from a real Cytoscape render.
    cmp.overlayMarkers.set([
      { id: 'a', name: 'A', x: 10, y: 20, layer: 'IP', dotX: 2, dotY: 30 },
      { id: 'b', name: 'B', x: 40, y: 50, layer: 'fiber', dotX: 32, dotY: 60 },
      { id: 'c', name: 'C', x: 70, y: 80, layer: 'mystery-layer', dotX: 62, dotY: 90 },
    ]);

    const dots = cmp.layerDotMarkers();
    expect(dots).toHaveLength(3);

    const ip = dots.find((d) => d.id === 'a')!;
    expect(ip.color).toBe(SiteGraphComponent.LAYER_COLORS['IP']);
    expect(ip.x).toBe(2);
    expect(ip.y).toBe(30);
    expect(ip.layer).toBe('IP');

    const fiber = dots.find((d) => d.id === 'b')!;
    expect(fiber.color).toBe(SiteGraphComponent.LAYER_COLORS['fiber']);

    // An unknown layer falls back to the 'other' swatch — never colour-less.
    const other = dots.find((d) => d.id === 'c')!;
    expect(other.color).toBe(SiteGraphComponent.LAYER_COLORS['other']);
  });

  it('the dot colour set is a subset of the layer legend colours (single source of truth)', async () => {
    const fixture = await mount();
    const cmp = fixture.componentInstance;
    cmp.overlayMarkers.set([
      { id: 'a', name: 'A', x: 0, y: 0, layer: 'LSP', dotX: 0, dotY: 0 },
      { id: 'b', name: 'B', x: 0, y: 0, layer: 'service', dotX: 0, dotY: 0 },
    ]);
    const legendColors = new Set(cmp.LAYER_LEGEND.map((l) => l.color));
    for (const d of cmp.layerDotMarkers()) {
      expect(legendColors.has(d.color)).toBe(true);
    }
  });
});
