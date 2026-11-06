import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { SharedModule } from '../shared/shared.module';
import { RadarRoutingModule } from './radar-routing.module';
import { RadarComponent } from './radar/radar.component';
import { UserAvatar3dComponent } from './user-avatar-3d/user-avatar-3d.component';
import { UserCardComponent } from './user-card/user-card.component';

// Module-based only (project-wide Angular rule, no standalone components) --
// both screen components this ticket builds are declared here.
// UserCardComponent is declared (not just used) in THIS module because
// Angular requires every component to be declared by exactly one NgModule,
// but it is written to be import-and-reuse-able from a different feature
// module too: T067's InboundRequestComponent (a different module,
// ConnectionModule) will need RadarModule added to ITS `imports` array and
// UserCardComponent added to RadarModule's `exports` array below -- the same
// shape SharedModule components use, just scoped to this one reusable
// component rather than the whole module, since UserCardComponent is
// Radar-family-specific (Interface Segregation note in the ticket: it is
// shaped around "show one discovered user's data," not around Radar itself).
// RadarDiscoveryService/UserProfileService are plain
// @Injectable({ providedIn: 'root' }) services, so neither appears here --
// only the two screen components are feature-module-local.
// UserAvatar3dComponent (the Three.js standing figure rendered on each
// radar dot) is declared here, not SharedModule -- it is Radar-specific
// (RadarComponent's own template is the only place that uses it today),
// unlike AmbientBackgroundComponent which genuinely spans unrelated feature
// modules.
@NgModule({
  declarations: [RadarComponent, UserCardComponent, UserAvatar3dComponent],
  imports: [CommonModule, FormsModule, RadarRoutingModule, SharedModule],
  exports: [UserCardComponent]
})
export class RadarModule {}
