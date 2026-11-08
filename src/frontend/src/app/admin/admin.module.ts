import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AdminRoutingModule } from './admin-routing.module';
import { QueueListComponent } from './pages/queue-list/queue-list.component';
import { StatsDashboardComponent } from './pages/stats-dashboard/stats-dashboard.component';
import { UserDetailComponent } from './pages/user-detail/user-detail.component';
import { AdminService } from './services/admin.service';

// Feature module (project Angular Rules: no standalone components, lazy via
// loadChildren). Everything admin-specific is declared here and nowhere else;
// nothing admin-related leaks into SharedModule.
//
// Lazy loading: this module's JS is fetched only when an admin navigates to
// /admin (and, via AdminGuard.canMatch, never for a non-admin) -- it stays out
// of the main bundle every regular user downloads.
//
// AdminService is provided HERE, so it is scoped to this feature's injector:
// the shared review-queue snapshot it holds is reachable only from admin pages.
// AdminGuard is the opposite case (providedIn: 'root') because the root router
// needs it before this module has loaded. SharedModule is not imported: the
// ripple background is plain CSS in admin-shared.css, same as the profile family.
@NgModule({
  declarations: [QueueListComponent, UserDetailComponent, StatsDashboardComponent],
  imports: [CommonModule, FormsModule, AdminRoutingModule],
  providers: [AdminService]
})
export class AdminModule {}
