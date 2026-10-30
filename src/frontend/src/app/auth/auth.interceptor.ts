import { Injectable } from '@angular/core';
import {
  HttpErrorResponse,
  HttpEvent,
  HttpHandler,
  HttpInterceptor,
  HttpRequest
} from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';

import { AuthService } from './auth.service';

const REFRESH_ENDPOINT_FRAGMENT = '/auth/refresh';

// Interceptor (cross-cutting concern, DI-registered once): attaches the JWT
// to every outgoing request in one place instead of every service method
// setting the header itself. The same "handle it once, centrally" idea the
// API Gateway applies server-side, just on the client.
@Injectable()
export class AuthInterceptor implements HttpInterceptor {
  constructor(private readonly authService: AuthService, private readonly router: Router) {}

  intercept(req: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    return next.handle(this.attachToken(req)).pipe(
      catchError((error: HttpErrorResponse) => {
        // Guard clause: only a 401 on a call that isn't the refresh call
        // itself is worth recovering from. Anything else -- a different
        // status, or a 401 from /auth/refresh -- must propagate as-is, or a
        // failing refresh would retry itself forever.
        const isRefreshCall = req.url.includes(REFRESH_ENDPOINT_FRAGMENT);
        if (error.status !== 401 || isRefreshCall) {
          return throwError(() => error);
        }

        return this.authService.refreshToken().pipe(
          switchMap(newToken => next.handle(this.attachToken(req, newToken))),
          catchError(refreshError => {
            // Refresh itself failed -- the session cannot be recovered.
            // Only now, after a refresh attempt, is logout correct: AC says
            // "attempt refresh, then logout", not "logout on first 401".
            this.authService.clearToken();
            this.router.navigate(['/auth/login']);
            return throwError(() => refreshError);
          })
        );
      })
    );
  }

  private attachToken(req: HttpRequest<unknown>, tokenOverride?: string): HttpRequest<unknown> {
    const token = tokenOverride ?? this.authService.getToken();
    // Guard clause: an unauthenticated request (e.g. /auth/google itself)
    // has no token to attach -- send it through unchanged.
    if (!token) {
      return req;
    }
    // Pattern: immutable clone -- HttpRequest is frozen once constructed, so
    // adding a header means cloning with the merged header set, never
    // mutating the original request object.
    return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
}
