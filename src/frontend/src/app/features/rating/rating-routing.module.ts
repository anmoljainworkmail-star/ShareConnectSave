import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { RatingComponent } from './rating.component';

// forChild: lazy-loaded under the root router's guarded 'rating' route
// (app-routing.module.ts). The param is the CONNECTION id -- that is what
// ChatFlowService.openRating navigates with, and what rating-service stores
// ratings against (connection_id).
const routes: Routes = [{ path: ':id', component: RatingComponent }];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class RatingRoutingModule {}
