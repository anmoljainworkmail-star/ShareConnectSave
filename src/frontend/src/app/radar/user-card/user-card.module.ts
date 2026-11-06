import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';

import { UserCardComponent } from './user-card.component';

// Interface Segregation (I), at module scale. T064's comment in RadarModule
// planned for InboundRequestComponent to import RadarModule to reach
// UserCardComponent. That would have been a trap: RadarModule imports
// RadarRoutingModule, whose forChild routes ('' -> RadarComponent) would then
// be merged into the importing feature's own route table -- the Radar screen
// would quietly appear under /connections. Giving the one reusable component
// its own tiny module means a consumer imports exactly what it uses (a card)
// and not a whole screen's routing with it. Same reasoning as why SharedModule
// holds components, not feature routes.
//
// Angular allows a component to be declared in exactly one NgModule; every
// other module that wants it imports THIS module.
@NgModule({
  declarations: [UserCardComponent],
  imports: [CommonModule],
  exports: [UserCardComponent]
})
export class UserCardModule {}
