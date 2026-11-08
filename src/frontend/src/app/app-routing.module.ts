import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { AdminGuard } from './admin/guards/admin.guard';
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
  },
  // T067: Inbound Request screen (FIGMA_SPEC.md Screen 10). Guarded like
  // every protected feature route. Note the always-on part of this feature
  // (hub connection, banner, sound) is NOT behind this route: it is started
  // from AppComponent so it runs on every screen.
  {
    path: 'connections',
    canActivate: [AuthGuard],
    loadChildren: () => import('./connections/connections.module').then(m => m.ConnectionsModule)
  },
  // T068: Chat (FIGMA_SPEC.md Screen 12), /chat/:connectionId. Guarded like
  // every protected feature route; like connections above, the always-on part
  // (hub connection, ChatOpened/ChatClosed navigation) is started from
  // AppComponent, not behind this lazy route.
  {
    path: 'chat',
    canActivate: [AuthGuard],
    loadChildren: () => import('./features/chat/chat.module').then(m => m.ChatModule)
  },
  // T069: Rating (FIGMA_SPEC.md Screen 14), /rating/:connectionId. Entered via
  // ChatFlowService.openRating with navigation state { entry: 'met' | 'timeout' }.
  // Guarded like every protected feature route.
  {
    path: 'rating',
    canActivate: [AuthGuard],
    loadChildren: () => import('./features/rating/rating.module').then(m => m.RatingModule)
  },
  // T070: Profile (/profile) + Settings (/profile/settings), FIGMA_SPEC.md
  // Screens 15/16/18. Guarded like every protected feature route.
  {
    path: 'profile',
    canActivate: [AuthGuard],
    loadChildren: () => import('./profile/profile.module').then(m => m.ProfileModule)
  },
  // T071: Admin (FIGMA_SPEC.md Screens 20-22), /admin, /admin/users/:userId,
  // /admin/stats. canMatch (AdminGuard), not canActivate: canMatch runs while
  // the router is still choosing a route, BEFORE the lazy chunk is fetched, so
  // a non-admin never downloads AdminModule's code at all -- and when canMatch
  // rejects it returns a UrlTree redirect instead of falling through to a
  // "no route matched" error. AuthGuard stays on canActivate like every other
  // protected feature route (AdminGuard also checks authentication itself, so
  // an expired session is sent to login rather than to the radar). Both guards
  // are UX only; admin-service enforces the role server-side.
  {
    path: 'admin',
    canMatch: [AdminGuard],
    canActivate: [AuthGuard],
    loadChildren: () => import('./admin/admin.module').then(m => m.AdminModule)
  }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
