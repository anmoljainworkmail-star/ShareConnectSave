import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { AuthGuard } from './auth/auth.guard';

// Pattern: lazy loading via loadChildren -- AuthModule's JS is only fetched
// when a user actually navigates to /auth, instead of being bundled into
// the initial app shell every visitor downloads. Project-wide rule: every
// feature module is registered this way, never eagerly imported here.
// Root redirect target (updated by T064): T063's own comment here said "no
// protected home screen exists yet ... the root path's only sensible
// destination is the front door" and pointed at 'auth' unconditionally.
// Radar is now that protected home screen, and AuthGuard on its own route
// below already redirects an unauthenticated visit to '/auth/login' --
// redirecting '' straight to 'radar' is therefore correct for BOTH an
// authenticated user (lands on the actual app) and an unauthenticated one
// (bounced to login by the guard), unlike the old unconditional 'auth'
// redirect, which sent an already-logged-in user back to the login screen
// every time they opened the app at '/'.
const routes: Routes = [
  { path: '', redirectTo: 'radar', pathMatch: 'full' },
  { path: 'auth', loadChildren: () => import('./auth/auth.module').then(m => m.AuthModule) },
  // Guard (first real use of AuthGuard on a protected route -- Login/OTP
  // above are deliberately public). canActivate runs before the lazy
  // chunk is even fetched, so an unauthenticated visit never downloads
  // ProfileSetupModule's JS at all, not just "never renders" it.
  {
    path: 'profile-setup',
    canActivate: [AuthGuard],
    loadChildren: () => import('./profile-setup/profile-setup.module').then(m => m.ProfileSetupModule)
  },
  // First genuinely protected FEATURE route (profile-setup above is onboarding,
  // not a feature screen) -- same AuthGuard-before-lazy-chunk pattern.
  {
    path: 'radar',
    canActivate: [AuthGuard],
    loadChildren: () => import('./radar/radar.module').then(m => m.RadarModule)
  }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
