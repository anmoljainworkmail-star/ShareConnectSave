import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { ProfileSetupComponent } from './profile-setup.component';

// forChild (not forRoot) -- this module is lazy-loaded by the root router
// (see app-routing.module.ts's canActivate-guarded 'profile-setup' route),
// so it only registers the single child route it owns, the same pattern
// AuthRoutingModule already establishes for /auth/*.
const routes: Routes = [{ path: '', component: ProfileSetupComponent }];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class ProfileSetupRoutingModule {}
