import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { ProfileComponent } from './profile.component';
import { SettingsComponent } from './settings/settings.component';

// forChild: lazy-loaded under the root router's guarded 'profile' route
// (app-routing.module.ts). Two screens, one module: Edit Profile at /profile
// and Settings at /profile/settings (angular-pwa skill's routing structure:
// "/profile -> ProfileModule (lazy) -> view, edit, settings").
const routes: Routes = [
  { path: '', component: ProfileComponent },
  { path: 'settings', component: SettingsComponent }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class ProfileRoutingModule {}
