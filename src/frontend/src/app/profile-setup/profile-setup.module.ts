import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { ProfileSetupRoutingModule } from './profile-setup-routing.module';
import { ProfileSetupComponent } from './profile-setup.component';

// A separate feature module from AuthModule (routing decision made in this
// ticket, not AuthModule itself): Login/OTP are deliberately public routes,
// this screen genuinely requires a signed-in session (AuthGuard, applied at
// the root route registration in app-routing.module.ts) -- keeping it out of
// AuthRoutingModule means a public route can never accidentally be declared
// alongside a guarded one in the same routes array.
// UserProfileService is a plain @Injectable({ providedIn: 'root' }), so it
// deliberately does not appear here -- only the screen itself is
// feature-module-local.
@NgModule({
  declarations: [ProfileSetupComponent],
  imports: [CommonModule, FormsModule, ProfileSetupRoutingModule]
})
export class ProfileSetupModule {}
