import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { InboundRequestComponent } from './inbound-request/inbound-request.component';

// forChild: lazy-loaded under the root router's guarded 'connections' route
// (app-routing.module.ts), same shape as RadarRoutingModule.
const routes: Routes = [
  { path: '', redirectTo: 'inbound', pathMatch: 'full' },
  { path: 'inbound', component: InboundRequestComponent }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class ConnectionsRoutingModule {}
