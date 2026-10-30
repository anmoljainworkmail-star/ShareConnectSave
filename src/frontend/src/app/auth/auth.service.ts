import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';

import { environment } from '../../environments/environment';
import {
  ErrorEnvelope,
  GoogleAuthApiResponse,
  OtpSendApiResponse,
  OtpVerifyApiResponse,
  RefreshApiResponse
} from './models/auth.models';

const ACCESS_TOKEN_STORAGE_KEY = 'scs_access_token';

// Separate storage key, not a second field inside the access-token record:
// an access token and a refresh token are two different credentials with
// different lifetimes and different trust levels (see JwtIssuer.Issue's
// "token_use" comment on the backend) -- keeping them as two independent
// localStorage entries is what lets clearToken() below drop both atomically
// without either one leaking past a logout.
const REFRESH_TOKEN_STORAGE_KEY = 'scs_refresh_token';

// Single Responsibility (S): this service only knows how to call the four
// /auth/* endpoints and read/write the two tokens they produce. It does not
// know about routing (that is AuthGuard/components) or request headers
// (that is AuthInterceptor). Any class that needs a token asks this service
// for it -- nobody else touches localStorage directly, so the storage
// mechanism (localStorage today, something else tomorrow) can change
// without touching a component, guard, or interceptor.
@Injectable({ providedIn: 'root' })
export class AuthService {
  // Pattern: BehaviorSubject as app-wide auth state (project convention --
  // no NgRx, services + BehaviorSubject instead). A plain Subject would miss
  // anyone who subscribes after the value already changed; BehaviorSubject
  // always replays the current value to a new subscriber, so a component
  // that mounts after login still renders the right state immediately.
  private readonly authenticated$ = new BehaviorSubject<boolean>(this.hasStoredToken());

  constructor(private readonly http: HttpClient) {}

  get isAuthenticated$(): Observable<boolean> {
    return this.authenticated$.asObservable();
  }

  signInWithGoogle(idToken: string): Observable<void> {
    // Wire contract (AuthController.GoogleAuthRequest): the JSON key is
    // "id_token", pinned there by [JsonPropertyName] on the C# side -- never
    // the TypeScript-idiomatic "idToken". ASP.NET Core's default
    // case-INSENSITIVE model binding still only ignores letter casing, not
    // the underscore itself, so a camelCase body here would silently bind
    // to an empty string and fail the backend's guard clause on every call.
    return this.http
      .post<GoogleAuthApiResponse>(`${environment.apiBase}/user/auth/google`, { id_token: idToken })
      .pipe(
        tap(response => this.storeTokens(response.access_token, response.refresh_token)),
        map(() => undefined),
        catchError(error => this.toApplicationError(error))
      );
  }

  sendOtp(phoneNumber: string): Observable<void> {
    return this.http
      .post<OtpSendApiResponse>(`${environment.apiBase}/user/auth/otp/send`, { phone_number: phoneNumber })
      .pipe(
        map(() => undefined),
        catchError(error => this.toApplicationError(error))
      );
  }

  verifyOtp(phoneNumber: string, code: string): Observable<void> {
    return this.http
      .post<OtpVerifyApiResponse>(`${environment.apiBase}/user/auth/otp/verify`, {
        phone_number: phoneNumber,
        code
      })
      .pipe(
        tap(response => {
          // Guard clause: most verifications don't reissue a token (see
          // OtpVerifyApiResponse's comment) -- the access token obtained at
          // the Google-sign-in step is still current, so there is nothing
          // to overwrite.
          if (!response.access_token) {
            return;
          }
          this.storeToken(response.access_token);
        }),
        map(() => undefined),
        catchError(error => this.toApplicationError(error))
      );
  }

  // /auth/refresh is a public gateway route for the opposite reason
  // /auth/google is: the access token this call would otherwise present is
  // expired by definition (that's why this method exists), so the real
  // credential travels in the body instead, as the REFRESH token -- never
  // the expired access token. This is distinct from a bug that shipped
  // earlier in this ticket: sending the access token here would always be
  // rejected by user-service's token_use check (JwtIssuer.ValidateRefreshToken),
  // not just occasionally.
  refreshToken(): Observable<string> {
    const refreshToken = this.getRefreshToken();
    // Guard clause: nothing to redeem without a stored refresh token -- fail
    // fast instead of sending a request the backend can only reject.
    if (!refreshToken) {
      return throwError(() => this.unauthenticatedError());
    }

    return this.http
      .post<RefreshApiResponse>(`${environment.apiBase}/user/auth/refresh`, { refresh_token: refreshToken })
      .pipe(
        tap(response => this.storeToken(response.access_token)),
        map(response => response.access_token),
        catchError(error => this.toApplicationError(error))
      );
  }

  // Known follow-up (T067/T068): this method is handed directly to
  // SignalR's HubConnectionBuilder as accessTokenFactory, e.g.
  //   .withUrl(hubUrl, { accessTokenFactory: () => authService.getToken() ?? '' })
  // SignalR calls accessTokenFactory synchronously on every (re)connect, so
  // this stays a plain synchronous getter rather than an Observable/Promise.
  // This is deliberately the ACCESS token, never the refresh token -- a hub
  // connection is exactly the kind of authenticated request the gateway's
  // JwtValidationMiddleware must be able to validate, same as any REST call.
  getToken(): string | null {
    return this.readStorage(ACCESS_TOKEN_STORAGE_KEY);
  }

  storeToken(token: string): void {
    this.writeStorage(ACCESS_TOKEN_STORAGE_KEY, token);
    this.authenticated$.next(true);
  }

  clearToken(): void {
    this.removeStorage(ACCESS_TOKEN_STORAGE_KEY);
    this.removeStorage(REFRESH_TOKEN_STORAGE_KEY);
    this.authenticated$.next(false);
  }

  isAuthenticated(): boolean {
    return this.getToken() !== null;
  }

  private storeTokens(accessToken: string, refreshToken: string): void {
    this.writeStorage(REFRESH_TOKEN_STORAGE_KEY, refreshToken);
    // storeToken() flips authenticated$ -- written second so the refresh
    // token is already on disk by the time any subscriber reacts to
    // "authenticated" becoming true.
    this.storeToken(accessToken);
  }

  private getRefreshToken(): string | null {
    return this.readStorage(REFRESH_TOKEN_STORAGE_KEY);
  }

  private hasStoredToken(): boolean {
    return this.getToken() !== null;
  }

  private readStorage(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      // Guard clause: private browsing / blocked storage should degrade to
      // "not authenticated", never throw and break the caller.
      return null;
    }
  }

  private writeStorage(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // Swallow storage errors -- the in-memory authenticated$ state still
      // lets the current session work; only persistence across reloads is
      // lost, which is the best this method can do without localStorage.
    }
  }

  private removeStorage(key: string): void {
    try {
      localStorage.removeItem(key);
    } catch {
      // Nothing to clean up if storage was never reachable.
    }
  }

  private unauthenticatedError(): ErrorEnvelope {
    return { code: 'NOT_AUTHENTICATED', message: 'No refresh token available.', traceId: '' };
  }

  // Normalizes every HTTP failure (backend error envelope or a transport-
  // level failure the envelope never reached) into the same ErrorEnvelope
  // shape, so every caller (components, the interceptor) handles errors one
  // way regardless of what actually failed.
  private toApplicationError(error: HttpErrorResponse): Observable<never> {
    const backendEnvelope = error.error as Partial<ErrorEnvelope> | null;
    const envelope: ErrorEnvelope = {
      code: backendEnvelope?.code ?? 'UNKNOWN_ERROR',
      message: backendEnvelope?.message ?? error.message ?? 'An unexpected error occurred.',
      traceId: backendEnvelope?.traceId ?? ''
    };
    return throwError(() => envelope);
  }
}
