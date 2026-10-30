import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

// Pattern: lazy loading via loadChildren -- AuthModule's JS is only fetched
// when a user actually navigates to /auth, instead of being bundled into
// the initial app shell every visitor downloads. Project-wide rule: every
// feature module is registered this way, never eagerly imported here.
// No protected home screen exists yet (Radar/Activity/Chat ship in later
// tickets - see CLAUDE.md's Microservices table) - until one does, the root
// path's only sensible destination is the front door this ticket builds.
const routes: Routes = [
  { path: '', redirectTo: 'auth', pathMatch: 'full' },
  { path: 'auth', loadChildren: () => import('./auth/auth.module').then(m => m.AuthModule) }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
