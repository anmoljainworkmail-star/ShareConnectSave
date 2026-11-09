import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';

import { AmbientBackgroundComponent } from './ambient-background/ambient-background.component';
import { PendingRequestsBellComponent } from './pending-requests-bell/pending-requests-bell.component';
import { PullToRefreshComponent } from './pull-to-refresh/pull-to-refresh.component';
import { ToastHostComponent } from './toast/toast-host.component';

// First real use of SharedModule (CLAUDE.md's Angular Rules: "Shared UI goes
// in SharedModule. Feature-specific logic stays in its own feature module.").
// AmbientBackgroundComponent is genuinely reused across unrelated feature
// modules (AuthModule, ProfileSetupModule, RadarModule) -- exactly the bar
// profile-setup.component.css's own progress-dots comment sets for when a
// SharedModule component is actually warranted, as opposed to one screen's
// local markup.
// ToastHostComponent (T067) clears the same bar: it is mounted once in the
// root AppComponent and fed by ToastService from any feature, so it belongs
// to no single one. RouterModule (not forRoot -- just the routerLink
// directive) is imported because the in-app banner's action is a routerLink.
@NgModule({
  declarations: [AmbientBackgroundComponent, ToastHostComponent, PullToRefreshComponent, PendingRequestsBellComponent],
  imports: [CommonModule, RouterModule],
  exports: [AmbientBackgroundComponent, ToastHostComponent, PullToRefreshComponent, PendingRequestsBellComponent]
})
export class SharedModule {}
