import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { RadarComponent } from './radar/radar.component';

// forChild (not forRoot) -- this module is lazy-loaded by the root router
// (see app-routing.module.ts's canActivate-guarded 'radar' route), so it
// only registers the single child route it owns, the same pattern
// AuthRoutingModule/ProfileSetupRoutingModule already establish.
const routes: Routes = [{ path: '', component: RadarComponent }];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class RadarRoutingModule {}
