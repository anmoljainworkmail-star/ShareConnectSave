import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';

import { SharedModule } from '../shared/shared.module';
import { UserCardModule } from '../radar/user-card/user-card.module';
import { ConnectionsRoutingModule } from './connections-routing.module';
import { InboundRequestComponent } from './inbound-request/inbound-request.component';

// Feature module for the connection-request UI (project Angular Rules: no
// standalone components, feature modules lazy-loaded). Only what is
// screen-local is declared here: InboundRequestComponent.
//
// The services (ConnectionService, NotificationService, ConnectionFlowService,
// NotificationSoundService) are deliberately NOT provided here: they are
// root-provided singletons because the SignalR connection and the reaction to
// pushes must be alive from login onward, before this lazy module is ever
// downloaded -- a request can arrive while the user is on the radar.
//
// UserCardModule (not RadarModule) supplies <app-user-card>: see that file for
// why importing RadarModule would drag Radar's routes into this feature.
@NgModule({
  declarations: [InboundRequestComponent],
  imports: [CommonModule, ConnectionsRoutingModule, SharedModule, UserCardModule]
})
export class ConnectionsModule {}
