import { Injectable } from '@angular/core';
import { CanMatch, Router, UrlTree } from '@angular/router';

import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../shared/toast/toast.service';

// The JWT "role" claim value that grants admin access. Exact, lowercase,
// case-sensitive -- the same literal admin-service's AdminRoleAuthenticationFilter
// compares X-User-Role against, so client and server can never disagree about
// what "admin" spells.
const ADMIN_ROLE = 'admin';

// Where a signed-in non-admin lands: the app's protected home (the root route
// redirects '' to it, see app-routing.module.ts).
const NON_ADMIN_REDIRECT = '/radar';

// Guard pattern (Angular routing): the "may this user enter /admin" decision is
// made here once, instead of every admin page re-checking the role in ngOnInit.
// Same idea as the API Gateway validating a JWT once at the edge.
//
// canMatch, not canActivate (CanMatch runs while the router is still deciding
// WHICH route config matches, i.e. BEFORE it fetches the lazy chunk): for a
// non-admin, AdminModule's JavaScript is never even downloaded -- the admin UI's
// code and its route names are not shipped to people who can never use them.
// canActivate would only run after the chunk was already loaded.
//
// READ THIS BEFORE TRUSTING IT: this guard is a UX convenience, NOT security.
// The browser cannot verify a JWT signature, so anyone can edit localStorage and
// make getRole() return 'admin' -- and all that buys them is an admin SHELL that
// shows errors, because every admin API call is still enforced server-side: the
// gateway verifies the token signature and admin-service rejects any request
// whose gateway-injected X-User-Role is not 'admin' (403). Never move an
// authorization decision into code a user can edit.
//
// providedIn: 'root' (not AdminModule's providers): the root router resolves this
// guard while matching the route, before AdminModule's own injector exists.
@Injectable({ providedIn: 'root' })
export class AdminGuard implements CanMatch {
  constructor(
    private readonly authService: AuthService,
    private readonly router: Router,
    private readonly toastService: ToastService
  ) {}

  canMatch(): boolean | UrlTree {
    // Guard clauses: fail fast and redirect via UrlTree (the router cancels the
    // current navigation and starts the redirect as ONE atomic step, unlike
    // calling router.navigate() inside a guard).
    if (!this.authService.isAuthenticated()) {
      return this.router.createUrlTree(['/auth/login']);
    }
    if (this.authService.getRole() !== ADMIN_ROLE) {
      this.toastService.show('You do not have access to that page.', 'error');
      return this.router.createUrlTree([NON_ADMIN_REDIRECT]);
    }
    return true;
  }
}
