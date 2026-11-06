import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';

import { AmbientBackgroundComponent } from './ambient-background/ambient-background.component';

// First real use of SharedModule (CLAUDE.md's Angular Rules: "Shared UI goes
// in SharedModule. Feature-specific logic stays in its own feature module.").
// AmbientBackgroundComponent is genuinely reused across unrelated feature
// modules (AuthModule, ProfileSetupModule, RadarModule) -- exactly the bar
// profile-setup.component.css's own progress-dots comment sets for when a
// SharedModule component is actually warranted, as opposed to one screen's
// local markup.
@NgModule({
  declarations: [AmbientBackgroundComponent],
  imports: [CommonModule],
  exports: [AmbientBackgroundComponent]
})
export class SharedModule {}
