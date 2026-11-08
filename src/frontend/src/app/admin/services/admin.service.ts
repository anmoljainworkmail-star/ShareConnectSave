import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../auth/auth.service';
import { ErrorEnvelope } from '../../auth/models/auth.models';
import {
  DashboardStats,
  ReinstateResponse,
  ReviewQueueItem,
  STATUS_SUSPENDED,
  SuspendResponse,
  TrustScoreOverrideResponse,
  UserDetail
} from '../models/admin.models';

// Public URL = gateway route prefix + admin-service's own controller path.
// The gateway's "admin-route" (api-gateway appsettings.json) matches
// /admin/{**catch-all} and then STRIPS the first "/admin" (PathRemovePrefix),
// exactly like /user -> user-service. admin-service's controllers, however, are
// mapped under /admin/... themselves (ReviewQueueController: "/admin/queue"), so
// the path a browser must call through today is /admin/admin/queue. The doubled
// segment looks like a typo but is what the current gateway config requires;
// it is isolated in this ONE constant so that if the gateway route or the
// controller prefix is ever aligned, this is the only line that changes.
const ADMIN_GATEWAY_BASE = `${environment.apiBase}/admin`;
const ADMIN_CONTROLLER_PREFIX = '/admin';

// Facade pattern (client side of T057/T059/T060): this service talks ONLY to
// admin-service, whose endpoints are themselves facades that aggregate User,
// Rating, Report, Connection and Discovery services. The frontend never calls
// those services directly -- Database per Service holds on the wire too: one
// service owns the aggregation, and a change in how it gathers data never
// ripples into the Angular code.
//
// Single Responsibility (S): HTTP calls plus the one piece of cross-page state
// the admin screens genuinely share (the review-queue snapshot, below). Pages
// own their own view state.
//
// Provided in AdminModule (not providedIn: 'root'): this is feature-scoped
// state that no screen outside /admin should be able to inject.
@Injectable()
export class AdminService {
  // Pattern: BehaviorSubject as shared state (project convention, no NgRx).
  // Why the queue is cached at all: after the admin suspends or reinstates a user
  // on the detail page and goes "back to the queue" (FIGMA_SPEC.md flow), the
  // queue must already show the change -- not flash the old list while a refetch
  // runs. null means "never loaded"; [] means "loaded and genuinely empty" -- two
  // different facts the UI must not conflate.
  private readonly queueSubject = new BehaviorSubject<ReviewQueueItem[] | null>(null);
  readonly queue$: Observable<ReviewQueueItem[] | null> = this.queueSubject.asObservable();

  constructor(private readonly http: HttpClient, private readonly authService: AuthService) {
    // Observer pattern: this service reacts to the auth state instead of being
    // told about logout by every caller. AdminService lives in AdminModule's
    // injector, which outlives a logout, so without this a second admin signing
    // in on the same tab would be shown the first admin's cached queue.
    // The BehaviorSubject replays its current value on subscribe, so the first
    // emission is harmless (the snapshot is still null at construction).
    this.authService.isAuthenticated$.subscribe(authenticated => {
      // Guard clause: only a logout clears the snapshot.
      if (authenticated) {
        return;
      }
      this.queueSubject.next(null);
    });
  }

  get hasQueueSnapshot(): boolean {
    return this.queueSubject.value !== null;
  }

  // ---- Reads ---------------------------------------------------------------

  // GET /admin/queue -- suspended accounts, one row each, with per-row degrade
  // flags. The result is pushed into queue$; callers read the stream and use the
  // returned Observable only for loading/error handling.
  refreshQueue(): Observable<ReviewQueueItem[]> {
    return this.http.get<ReviewQueueItem[]>(this.url('/queue')).pipe(
      tap(items => this.queueSubject.next(sortQueue(items))),
      catchError(error => this.toApplicationError(error))
    );
  }

  // GET /admin/users/{id} -- profile + trust score + report history in one call.
  getUserDetail(userId: string): Observable<UserDetail> {
    return this.http.get<UserDetail>(this.url(`/users/${encodeURIComponent(userId)}`)).pipe(
      // Anti-corruption layer (DDD): user-service writes "Active" / "Suspended"
      // (capitalised) while the UI's STATUS_* constants are lowercase. Translating
      // the wire value once, here at the boundary, means no component has to know
      // about the upstream casing; a case-insensitive compare scattered across
      // the pages would be one forgotten check away from a broken button.
      map(detail => ({ ...detail, status: normaliseStatus(detail.status) })),
      catchError(error => this.toApplicationError(error))
    );
  }

  // GET /admin/stats -- the T060 dashboard aggregate.
  getStats(): Observable<DashboardStats> {
    return this.http
      .get<DashboardStats>(this.url('/stats'))
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  // ---- Writes --------------------------------------------------------------
  // Each write patches the queue snapshot in a tap() AFTER the server confirmed
  // it, so the shared queue never shows a change the server has not accepted.
  // (The optimistic, pre-confirmation update lives in the detail page's own view
  // state, where a rollback is just "put the old object back".)

  // POST /admin/users/{id}/suspend. The acting admin comes from the gateway's
  // X-User-Id header, never from this body.
  suspendUser(user: UserDetail, reason: string): Observable<SuspendResponse> {
    return this.http
      .post<SuspendResponse>(this.url(`/users/${encodeURIComponent(user.userId)}/suspend`), { reason })
      .pipe(
        tap(response => this.upsertSuspendedInQueue(user, response)),
        catchError(error => this.toApplicationError(error))
      );
  }

  // POST /admin/users/{id}/reinstate (POST, not the OpenAPI file's PUT -- the
  // controller follows the approved ticket; see ReviewQueueController's comment).
  reinstateUser(userId: string): Observable<ReinstateResponse> {
    return this.http
      .post<ReinstateResponse>(this.url(`/users/${encodeURIComponent(userId)}/reinstate`), {})
      .pipe(
        tap(() => this.removeFromQueue(userId)),
        catchError(error => this.toApplicationError(error))
      );
  }

  // PATCH /admin/users/{id}/trust-score (T058). `reason` is mandatory server-side
  // because it feeds the override audit log. The response is returned as-is and
  // deliberately NOT written to the queue here: the stub behind it echoes the
  // request without storing it, so the detail page verifies with a re-read first
  // (see UserDetailComponent.confirmOverride) and only then patches the queue.
  overrideTrustScore(userId: string, score: number, reason: string): Observable<TrustScoreOverrideResponse> {
    return this.http
      .patch<TrustScoreOverrideResponse>(this.url(`/users/${encodeURIComponent(userId)}/trust-score`), { score, reason })
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  // ---- Queue snapshot maintenance (immutable updates) ------------------------

  private removeFromQueue(userId: string): void {
    const current = this.queueSubject.value;
    // Guard clause: no snapshot yet means the queue page will fetch fresh data
    // on entry anyway; there is nothing to patch.
    if (current === null) {
      return;
    }
    this.queueSubject.next(current.filter(item => item.userId !== userId));
  }

  private upsertSuspendedInQueue(user: UserDetail, response: SuspendResponse): void {
    const current = this.queueSubject.value;
    if (current === null) {
      return;
    }
    // Rebuild the row from what the admin was just looking at. The degrade flags
    // are carried over, never defaulted to "available": a score or count the
    // detail page could not load must still read as unavailable in the queue.
    const row: ReviewQueueItem = {
      userId: user.userId,
      displayName: user.displayName ?? `User ${user.userId}`,
      status: STATUS_SUSPENDED,
      trustScore: user.trustScore,
      trustScoreUnavailable: user.trustScoreUnavailable,
      reportCount: user.reportHistoryUnavailable ? null : user.reportCount,
      reportCountUnavailable: user.reportHistoryUnavailable,
      suspendedAt: response.updatedAt
    };
    this.queueSubject.next(sortQueue([...current.filter(item => item.userId !== user.userId), row]));
  }

  // Public on purpose: the only caller is the detail page, and only after its
  // read-after-write check has confirmed the score is stored.
  patchQueueTrustScore(userId: string, score: number): void {
    const current = this.queueSubject.value;
    if (current === null) {
      return;
    }
    this.queueSubject.next(
      current.map(item =>
        item.userId === userId ? { ...item, trustScore: score, trustScoreUnavailable: false } : item
      )
    );
  }

  private url(path: string): string {
    return `${ADMIN_GATEWAY_BASE}${ADMIN_CONTROLLER_PREFIX}${path}`;
  }

  // Same normalization AuthService/RatingApiService each keep as their own
  // private copy (duplication over coupling between unrelated features).
  private toApplicationError(error: HttpErrorResponse): Observable<never> {
    const backendEnvelope = error.error as Partial<ErrorEnvelope> | null;
    const envelope: ErrorEnvelope = {
      code: backendEnvelope?.code ?? 'UNKNOWN_ERROR',
      // Not error.message: it can embed the raw request URL.
      message: backendEnvelope?.message ?? 'Something went wrong. Please try again.',
      traceId: backendEnvelope?.traceId ?? ''
    };
    return throwError(() => envelope);
  }
}

// Null stays null (= status unknown, the detail page offers no action); any
// non-string is treated as unknown rather than crashing on .toLowerCase().
function normaliseStatus(status: string | null): string | null {
  if (typeof status !== 'string') {
    return null;
  }
  return status.toLowerCase();
}

// FIGMA_SPEC.md Screen 20: "Sorted by report count descending." Rows whose
// count is unavailable sort LAST (we cannot rank what we cannot measure, and
// ranking them as 0 would bury a possibly-serious account without saying so);
// ties break newest-suspension first so the order is deterministic.
function sortQueue(items: ReviewQueueItem[]): ReviewQueueItem[] {
  return [...items].sort((a, b) => {
    const countA = a.reportCountUnavailable || a.reportCount === null ? -1 : a.reportCount;
    const countB = b.reportCountUnavailable || b.reportCount === null ? -1 : b.reportCount;
    if (countA !== countB) {
      return countB - countA;
    }
    return (Date.parse(b.suspendedAt ?? '') || 0) - (Date.parse(a.suspendedAt ?? '') || 0);
  });
}
