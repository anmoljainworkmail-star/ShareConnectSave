import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import { environment } from '../../environments/environment';
import { ErrorEnvelope } from '../auth/models/auth.models';
import {
  DiscoveredUserCardDto,
  ScanLocationRequest,
  ScanStartRequest,
  ScanStartResponse
} from './models/radar.models';
import { MOCK_NEARBY_USERS } from './radar-discovery.mock';

// Single Responsibility (S): this service only knows how to call
// discovery-service's four /scan/* endpoints -- it has no idea status is
// also written to User Service (RadarComponent composes both calls; see its
// startScan()/stopScan() methods), and no idea how a photo gets fetched
// (UserProfileService.resolvePhotoUrl, reused as-is rather than duplicated --
// see UserCardComponent). Same split AuthService/UserProfileService already
// establish for their own two services.
@Injectable({ providedIn: 'root' })
export class RadarDiscoveryService {
  constructor(private readonly http: HttpClient) {}

  startScan(request: ScanStartRequest): Observable<ScanStartResponse> {
    return this.http
      .post<ScanStartResponse>(`${environment.apiBase}/discovery/scan/start`, request)
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  getNearby(): Observable<DiscoveredUserCardDto[]> {
    return this.http.get<DiscoveredUserCardDto[]>(`${environment.apiBase}/discovery/scan/nearby`).pipe(
      // Dev-only testing aid (see radar-discovery.mock.ts's header comment):
      // a solo developer's local environment has no second real account for
      // a genuine scan to find, so a correctly-working backend legitimately
      // returns an empty array forever -- which makes the radar screen look
      // broken rather than idle. Substituting the mock list ONLY when the
      // real response is empty, and ONLY outside production builds, lets the
      // scan UI/animations be exercised without ever shipping fabricated
      // rows to a real user.
      map(users => (!environment.production && users.length === 0 ? [...MOCK_NEARBY_USERS] : users)),
      catchError(error => this.toApplicationError(error))
    );
  }

  stopScan(): Observable<void> {
    return this.http
      .post<void>(`${environment.apiBase}/discovery/scan/stop`, {})
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  updateLocation(request: ScanLocationRequest): Observable<void> {
    return this.http
      .put<void>(`${environment.apiBase}/discovery/scan/location`, request)
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  // Same normalization AuthService/UserProfileService each perform, kept as
  // its own private copy rather than a shared base class -- see
  // UserProfileService.toApplicationError's comment for the Liskov-style
  // duplication-vs-coupling reasoning this repeats a third time.
  private toApplicationError(error: HttpErrorResponse): Observable<never> {
    const backendEnvelope = error.error as Partial<ErrorEnvelope> | null;
    const envelope: ErrorEnvelope = {
      code: backendEnvelope?.code ?? 'UNKNOWN_ERROR',
      // Deliberately NOT error.message here: when the backend response
      // doesn't match ErrorEnvelope's shape (e.g. the gateway itself
      // returned the error, not a service behind it), HttpErrorResponse's
      // own .message can contain the raw request URL and HTTP status text --
      // internal detail that shouldn't reach the end user. A generic string
      // is the safe fallback; only a real backend-provided message is ever
      // shown verbatim.
      message: backendEnvelope?.message ?? 'Something went wrong — please try again.',
      traceId: backendEnvelope?.traceId ?? ''
    };
    return throwError(() => envelope);
  }
}
