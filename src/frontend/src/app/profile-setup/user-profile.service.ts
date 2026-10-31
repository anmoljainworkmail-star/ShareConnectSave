import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';

import { environment } from '../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { ErrorEnvelope } from '../auth/models/auth.models';
import { ProfileMutationApiResponse, UpdateProfileRequest, UserProfileDto } from './models/profile-setup.models';

// Single Responsibility (S): AuthService (see its own file header comment)
// deliberately only knows the four /auth/* endpoints and the two tokens they
// produce -- it has no idea /users/me even exists. This service is the
// mirror of the backend's own split (UserController vs AuthController, both
// delegating to their own IXxxService): profile CRUD is a different reason
// to change than "how does a session get established", so it lives here
// instead of being bolted onto AuthService.
@Injectable({ providedIn: 'root' })
export class UserProfileService {
  constructor(private readonly http: HttpClient, private readonly authService: AuthService) {}

  getMyProfile(): Observable<UserProfileDto> {
    return this.http
      .get<UserProfileDto>(`${environment.apiBase}/user/users/me`)
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  updateProfile(patch: UpdateProfileRequest): Observable<UserProfileDto> {
    return this.http.patch<ProfileMutationApiResponse>(`${environment.apiBase}/user/users/me`, patch).pipe(
      tap(response => this.applyTokenIfPresent(response)),
      map(response => response.profile),
      catchError(error => this.toApplicationError(error))
    );
  }

  uploadPhoto(file: File): Observable<UserProfileDto> {
    // Field name must be exactly "photo" -- POST /users/me/photo binds
    // straight to an `IFormFile? photo` parameter on the backend, not a
    // wrapped request object, so the multipart part name IS the contract.
    const formData = new FormData();
    formData.append('photo', file, file.name);

    return this.http.post<ProfileMutationApiResponse>(`${environment.apiBase}/user/users/me/photo`, formData).pipe(
      tap(response => this.applyTokenIfPresent(response)),
      map(response => response.profile),
      catchError(error => this.toApplicationError(error))
    );
  }

  // Authenticated photo fetch (REQUIREMENTS.md §2): every gateway route
  // requires a JWT except POST /auth/google -- deliberately including the
  // static photo files once a user has uploaded one, per that requirement's
  // own note. A plain `<img src="...">` has no way to attach an
  // Authorization header, so a self-hosted photo_url (anything under
  // environment.apiBase, e.g. "http://localhost:8080/user/uploads/photos/...")
  // must be fetched through HttpClient instead -- AuthInterceptor then
  // attaches the bearer token the same way it does for every other call,
  // and the resulting blob becomes a same-origin blob: URL an <img> can bind
  // to freely. A Google-provided photo_url (e.g. "https://lh3.googleusercontent.com/...")
  // is the opposite case: it is NOT behind our gateway, has no JWT
  // requirement, and routing it through HttpClient would let AuthInterceptor
  // attach our bearer token to a cross-origin request to Google's own CDN --
  // an unnecessary custom header that can trigger a CORS preflight Google's
  // image host has no reason to allow, breaking a plain image load that
  // would otherwise just work. The environment.apiBase prefix check is what
  // tells these two cases apart.
  resolvePhotoUrl(photoUrl: string | null): Observable<string | null> {
    if (!photoUrl) {
      return of(null);
    }

    if (!photoUrl.startsWith(environment.apiBase)) {
      return of(photoUrl);
    }

    return this.http
      .get(photoUrl, { responseType: 'blob' })
      .pipe(
        map(blob => URL.createObjectURL(blob)),
        catchError(error => this.toApplicationError(error))
      );
  }

  // Reissue-on-claim-change: treat "access_token present in the body" as the
  // signal to swap tokens, never "every response carries one" -- most calls
  // here leave onboarding_complete unchanged and therefore omit the field
  // entirely (backend: [JsonIgnore(WhenWritingNull)]-style omission).
  // Delegating the actual swap to AuthService.storeToken keeps "where the
  // token lives" a single piece of knowledge owned by one class.
  private applyTokenIfPresent(response: ProfileMutationApiResponse): void {
    if (!response.access_token) {
      return;
    }
    this.authService.storeToken(response.access_token);
  }

  // Same normalization AuthService.toApplicationError performs, kept as its
  // own private copy rather than an imported shared function: Liskov-style
  // duplication-vs-coupling tradeoff -- two services independently agreeing
  // on the { code, message, traceId } shape (CLAUDE.md's Error Envelope
  // rule) is safer here than introducing a shared base class neither service
  // otherwise needs.
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
