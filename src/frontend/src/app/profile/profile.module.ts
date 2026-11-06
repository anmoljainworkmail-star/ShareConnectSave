import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ProfileRoutingModule } from './profile-routing.module';
import { ProfileComponent } from './profile.component';
import { SettingsComponent } from './settings/settings.component';

// Feature module (project Angular Rules: no standalone components, lazy via
// loadChildren). Only screen-local pieces are declared. The services it leans
// on -- UserProfileService (profile CRUD + authenticated photo fetch) and
// ScanSessionService (women-only filter state) -- are root-provided, so this
// module neither re-provides nor re-implements them. SharedModule is not
// imported: the ripple background is plain CSS in profile-shared.css.
@NgModule({
  declarations: [ProfileComponent, SettingsComponent],
  imports: [CommonModule, FormsModule, ProfileRoutingModule]
})
export class ProfileModule {}
