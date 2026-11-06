import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { SharedModule } from '../shared/shared.module';
import { RadarRoutingModule } from './radar-routing.module';
import { DiscoveryFiltersPanelComponent } from './discovery-filters-panel/discovery-filters-panel.component';
import { RadarComponent } from './radar/radar.component';
import { UserAvatar3dComponent } from './user-avatar-3d/user-avatar-3d.component';
import { UserCardModule } from './user-card/user-card.module';

// Module-based only (project-wide Angular rule, no standalone components) --
// both screen components this ticket builds are declared here.
// UserCardComponent (T064) is no longer declared here: T067 needed it in a
// second feature module (ConnectionsModule's InboundRequestComponent), so it
// moved into its own UserCardModule -- see that file's comment for why
// importing RadarModule itself would have dragged Radar's routes along.
// RadarDiscoveryService/UserProfileService are plain
// @Injectable({ providedIn: 'root' }) services, so neither appears here --
// only the two screen components are feature-module-local.
// UserAvatar3dComponent (the Three.js standing figure rendered on each
// radar dot) is declared here, not SharedModule -- it is Radar-specific
// (RadarComponent's own template is the only place that uses it today),
// unlike AmbientBackgroundComponent which genuinely spans unrelated feature
// modules.
@NgModule({
  // DiscoveryFiltersPanelComponent (T066) is declared here -- Radar-specific
  // (opened only from RadarComponent), same reasoning as UserAvatar3dComponent.
  declarations: [RadarComponent, UserAvatar3dComponent, DiscoveryFiltersPanelComponent],
  imports: [CommonModule, FormsModule, RadarRoutingModule, SharedModule, UserCardModule]
})
export class RadarModule {}
