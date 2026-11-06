import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { environment } from '../../../../environments/environment';
import { ErrorEnvelope } from '../../../auth/models/auth.models';
import { RatingResponse, RatingSubmitRequest } from '../models/rating.models';

// Single Responsibility (S): the HTTP half of rating, exactly one endpoint.
// Trust-score calculation stays server-side in rating-service's
// TrustScoreCalculator -- this client only reports WHAT the user picked and
// never computes or even sees a score. A component never injects HttpClient
// (angular-pwa skill), which is also why this class exists.
@Injectable({ providedIn: 'root' })
export class RatingApiService {
  constructor(private readonly http: HttpClient) {}

  // POST /ratings. The rater is NOT sent: the gateway injects X-User-Id from
  // the validated JWT and rating-service reads it from that header, so a
  // client cannot rate "as" somebody else by editing the body.
  submitRating(request: RatingSubmitRequest): Observable<RatingResponse> {
    return this.http
      .post<RatingResponse>(`${environment.apiBase}/rating/ratings`, request)
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  // Same normalization AuthService/ChatApiService each keep as their own
  // private copy -- see UserProfileService's comment for the
  // duplication-vs-coupling reasoning this repeats.
  private toApplicationError(error: HttpErrorResponse): Observable<never> {
    const backendEnvelope = error.error as Partial<ErrorEnvelope> | null;
    const envelope: ErrorEnvelope = {
      code: backendEnvelope?.code ?? 'UNKNOWN_ERROR',
      // Not error.message: it can embed the raw request URL.
      message: backendEnvelope?.message ?? 'Something went wrong — please try again.',
      traceId: backendEnvelope?.traceId ?? ''
    };
    return throwError(() => envelope);
  }
}
