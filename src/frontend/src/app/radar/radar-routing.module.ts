import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { RadarMapComponent } from './map-v2/radar-map.component';
import { RadarComponent } from './radar/radar.component';

// forChild (not forRoot) -- this module is lazy-loaded by the root router
// (see app-routing.module.ts's canActivate-guarded 'radar' route), so it
// only registers the child routes it owns, the same pattern
// AuthRoutingModule/ProfileSetupRoutingModule already establish.
// 'v2' is the map-based radar (Strangler Fig: built beside the classic screen,
// reachable at /radar/v2 for testing; the classic screen is also its fallback).
const routes: Routes = [
  { path: '', component: RadarComponent },
  { path: 'v2', component: RadarMapComponent }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class RadarRoutingModule {}
