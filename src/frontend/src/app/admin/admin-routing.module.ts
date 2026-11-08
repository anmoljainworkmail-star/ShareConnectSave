import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { QueueListComponent } from './pages/queue-list/queue-list.component';
import { StatsDashboardComponent } from './pages/stats-dashboard/stats-dashboard.component';
import { UserDetailComponent } from './pages/user-detail/user-detail.component';

// forChild: lazy-loaded under the root router's guarded 'admin' route
// (app-routing.module.ts: AuthGuard + AdminGuard.canMatch). Three screens, one
// module, matching FIGMA_SPEC.md's admin flow:
//   /admin             Screen 20 Review Queue
//   /admin/users/:id   Screen 21 User Detail
//   /admin/stats       Screen 22 Stats Dashboard (reached from the queue's stats icon)
// The guard lives once on the parent route, not on each child -- every route in
// this file inherits it.
const routes: Routes = [
  { path: '', component: QueueListComponent },
  { path: 'users/:userId', component: UserDetailComponent },
  { path: 'stats', component: StatsDashboardComponent }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class AdminRoutingModule {}
