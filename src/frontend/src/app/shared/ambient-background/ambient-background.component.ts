import { Component } from '@angular/core';

// Shared UI (SharedModule, per CLAUDE.md's Angular Rules): the app's
// FINALIZED ambient background -- a static "classic atlas map" illustration
// (ocean, three landmasses with elevation/contour shading, a dashed air
// route a plane flies, a car looping one landmass's own contour ring, a
// boat crossing open water, a compass rose, decorative lat/long labels)
// dropped into every screen that wants the same finished background (Login,
// OTP, Profile Setup, Radar) instead of each screen carrying its own copy.
// Approved design: https://claude.ai/artifact/Fa8U6UyKV2daPnf18c93NV
// ("Option 2: Classic atlas map"). Single Responsibility (S): this
// component only knows how to render and animate the background; it has no
// idea what screen or what real content is stacked on top of it.
//
// This REPLACES an earlier, interactive version (drifting orbs/star field +
// a mouse-reactive map pin, with its own NgZone/ElementRef pointer-tracking
// logic) explored and superseded during design review -- that logic is
// gone along with it, not kept running alongside the atlas. A finalized
// design is the one background, not the old background plus a new layer on
// top of it. The component is now purely a static template + CSS, with
// nothing left for a TypeScript class to do.
//
// Usage: drop <app-ambient-background></app-ambient-background> as the
// FIRST child of any page wrapper that is itself `position: relative` with
// `overflow: hidden` (every current screen's page wrapper already is, for
// other reasons -- clipping the decor layer and centering the content on
// top of it). This component's own :host fills that wrapper AND paints the
// page's full background itself -- callers no longer declare their own page
// background (see radar-shared.css/auth-shared.css's own comments on why
// theirs was removed) -- and sits behind everything else purely through DOM
// order + z-index, no wrapper-specific markup required.
@Component({
  selector: 'app-ambient-background',
  templateUrl: './ambient-background.component.html',
  styleUrl: './ambient-background.component.css',
  // Angular 19 defaults new components to standalone: true. This project is
  // module-based only (no standalone components, per project-wide Angular
  // rule), so that default must be overridden explicitly on every
  // hand-written component.
  standalone: false
})
export class AmbientBackgroundComponent {}
