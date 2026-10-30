import { Injectable } from '@angular/core';
import { CanActivate, Router, UrlTree } from '@angular/router';

import { AuthService } from './auth.service';

// Guard (Angular routing pattern): centralizes the "is this user allowed
// past this point" check in one place instead of every protected component
// re-implementing the same isAuthenticated() check in ngOnInit. Same idea as
// the API Gateway validating a JWT once at the edge rather than every
// downstream microservice re-validating it.
@Injectable({ providedIn: 'root' })
export class AuthGuard implements CanActivate {
  constructor(private readonly authService: AuthService, private readonly router: Router) {}

  canActivate(): boolean | UrlTree {
    // Guard clause: fail fast and redirect immediately, rather than nesting
    // the "allowed" path inside an else branch.
    if (!this.authService.isAuthenticated()) {
      return this.router.createUrlTree(['/auth/login']);
    }
    return true;
  }
}
