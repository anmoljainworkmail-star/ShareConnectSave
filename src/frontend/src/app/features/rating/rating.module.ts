import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';

import { RatingRoutingModule } from './rating-routing.module';
import { RatingComponent } from './rating.component';

// Feature module for the rating screen (project Angular Rules: no standalone
// components, feature modules lazy-loaded). Only what is screen-local is
// declared here: RatingComponent. Its services are root-provided like the chat
// feature's: RatingApiService/RatingContextService hold no screen state, and
// reuse of ConnectionService / UserProfileService / ChatApiService (also
// root-provided) is what keeps the "who is this person" and authenticated
// photo logic in one place each.
//
// SharedModule is not imported: this screen draws its own background (the
// global ripple, in its own CSS) and uses ToastService, which is root-provided.
// No FormsModule either -- the tag grid is buttons, not form controls.
@NgModule({
  declarations: [RatingComponent],
  imports: [CommonModule, RatingRoutingModule]
})
export class RatingModule {}
