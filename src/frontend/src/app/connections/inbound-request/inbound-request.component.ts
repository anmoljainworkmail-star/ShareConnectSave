import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject, forkJoin, interval, of } from 'rxjs';
import { catchError, map, startWith, switchMap, takeUntil, tap } from 'rxjs/operators';

import { ErrorEnvelope } from '../../auth/models/auth.models';
import { UserProfileService } from '../../profile-setup/user-profile.service';
import { UserCardData } from '../../radar/models/radar.models';
import { ToastService } from '../../shared/toast/toast.service';
import { ConnectionFlowService } from '../connection-flow.service';
import { ConnectionService } from '../connection.service';
import { ConnectionResponse } from '../models/connection.models';
import { NotificationService } from '../notification.service';

// One waiting request plus the sender's displayable identity. The connection
// row only carries ids; the identity comes from User Service.
interface InboundRequestView {
  connection: ConnectionResponse;
  sender: UserCardData;
}

// Shown when the sender's public profile cannot be loaded (User Service down,
// profile deleted): the request is still real and still answerable, so
// degrade to an anonymous card rather than hiding a request the user may want
// to accept. Same graceful-degradation instinct as discovery-service's
// Circuit Breaker fallback card.
const FALLBACK_SENDER_NAME = 'A nearby traveler';

// FIGMA_SPEC.md Screen 10 (Inbound Request): half-height bottom sheet with
// the sender's card, a countdown, and Decline / Accept.
//
// Reuse, not a second card (T067 agent notes): the sender is rendered by
// <app-user-card>, including its authenticated photo fetch, via
// [showSendRequest]="false" and projected action buttons -- this component
// never fetches a photo itself.
//
// Thin component: it decides what is shown and when; HTTP lives in
// ConnectionService / UserProfileService, navigation-to-chat in
// ConnectionFlowService.
@Component({
  selector: 'app-inbound-request',
  templateUrl: './inbound-request.component.html',
  styleUrl: './inbound-request.component.css',
  // Module-based project (no standalone components) -- see UserCardComponent.
  standalone: false
})
export class InboundRequestComponent implements OnInit, OnDestroy {
  requests: InboundRequestView[] = [];
  isLoading = true;
  loadError: string | null = null;
  isActing = false;

  // Ticks once a second to drive the countdown. A plain timestamp property
  // (not a stored "seconds left") so the numbers are always derived from
  // expires_at and cannot drift if a tick is delayed (backgrounded tab).
  private now = Date.now();

  private readonly reload$ = new Subject<void>();
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly connectionService: ConnectionService,
    private readonly userProfileService: UserProfileService,
    private readonly notificationService: NotificationService,
    private readonly connectionFlowService: ConnectionFlowService,
    private readonly toastService: ToastService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    // switchMap: a new reload cancels an in-flight one, so two quick triggers
    // (screen open + a push arriving) can never apply out of order.
    this.reload$
      .pipe(
        startWith(undefined),
        tap(() => {
          this.isLoading = true;
          this.loadError = null;
        }),
        switchMap(() => this.fetchRequests()),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: views => {
          this.requests = views;
          this.isLoading = false;
        },
        error: (error: ErrorEnvelope) => {
          this.isLoading = false;
          this.loadError = error.message;
        }
      });

    // Observer in action: a request that lands while this screen is open is
    // pushed in -- no polling, no manual refresh button.
    this.notificationService
      .on('RequestReceived')
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.reload$.next());

    interval(1000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        this.now = Date.now();
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get current(): InboundRequestView | null {
    return this.requests[0] ?? null;
  }

  // "n more waiting" label for the queue behind the one on screen.
  get waitingBehind(): number {
    return Math.max(0, this.requests.length - 1);
  }

  get secondsLeft(): number {
    const current = this.current;
    if (!current) {
      return 0;
    }
    return Math.max(0, Math.floor((Date.parse(current.connection.expires_at) - this.now) / 1000));
  }

  // Client-clock based: a skewed device clock can show expiry a little early
  // or late. That is only cosmetic -- the server is the authority and rejects
  // an accept on a request it has already expired (handled in accept()).
  get isExpired(): boolean {
    return this.current !== null && this.secondsLeft === 0;
  }

  get countdownLabel(): string {
    const minutes = Math.floor(this.secondsLeft / 60);
    const seconds = this.secondsLeft % 60;
    return `Expires in ${minutes}:${seconds.toString().padStart(2, '0')}`;
  }

  // 1 = full ring, 0 = empty. Drives the "request expiry ring" animation
  // (FIGMA_SPEC.md section 6): shrinks over the request's TTL.
  get ringFraction(): number {
    const current = this.current;
    if (!current) {
      return 0;
    }
    const totalMs = Date.parse(current.connection.expires_at) - Date.parse(current.connection.created_at);
    if (totalMs <= 0) {
      return 0;
    }
    return Math.min(1, (this.secondsLeft * 1000) / totalMs);
  }

  accept(): void {
    const current = this.current;
    // Guard clause: nothing on screen, a call already in flight, or an
    // expired request -- never send the call.
    if (!current || this.isActing || this.isExpired) {
      return;
    }

    this.isActing = true;
    const connectionId = current.connection.id;
    this.connectionService
      .acceptRequest(connectionId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isActing = false;
          this.connectionFlowService.openChat(connectionId);
        },
        error: (error: ErrorEnvelope) => this.handleActionError(error)
      });
  }

  decline(): void {
    const current = this.current;
    if (!current || this.isActing || this.isExpired) {
      return;
    }

    this.isActing = true;
    this.connectionService
      .declineRequest(current.connection.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isActing = false;
          this.toastService.show('Request declined.');
          this.removeCurrent();
        },
        error: (error: ErrorEnvelope) => this.handleActionError(error)
      });
  }

  // For an expired card: the server's scheduler will mark it EXPIRED; the
  // user just needs it off the screen.
  dismissExpired(): void {
    this.removeCurrent();
  }

  // Handle-tap / back: leave the request PENDING and return to the radar.
  closeSheet(): void {
    this.router.navigate(['/radar']);
  }

  private fetchRequests(): Observable<InboundRequestView[]> {
    return this.connectionService.getPendingInbound().pipe(
      map(pending => [...pending].sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at))),
      switchMap(pending => {
        if (pending.length === 0) {
          return of<InboundRequestView[]>([]);
        }
        // Parallel lookups (forkJoin), each with its own fallback so one bad
        // profile cannot sink the whole list.
        return forkJoin(pending.map(connection => this.toView(connection)));
      })
    );
  }

  private toView(connection: ConnectionResponse): Observable<InboundRequestView> {
    return this.userProfileService.getPublicProfile(connection.requester_id).pipe(
      map(profile => ({
        connection,
        // Only fields the public profile really has -- rating, destination and
        // route match are not in the push or the connection row, so the card
        // omits those rows (its template *ngIf-hides absent fields) rather
        // than showing invented values.
        sender: {
          id: profile.id,
          name: profile.name,
          photo_url: profile.photo_url,
          identity_badge: profile.identity_badge
        }
      })),
      catchError(() =>
        of({
          connection,
          sender: { id: connection.requester_id, name: FALLBACK_SENDER_NAME, photo_url: null, identity_badge: false }
        })
      )
    );
  }

  private handleActionError(error: ErrorEnvelope): void {
    this.isActing = false;
    this.toastService.show(error.message, 'error');
    // The request may have expired, been withdrawn, or already handled from
    // another device -- re-sync from the server instead of leaving a stale
    // card with dead buttons.
    this.reload$.next();
  }

  private removeCurrent(): void {
    this.requests = this.requests.slice(1);
    // Guard clause: more requests waiting means the next card simply takes
    // the sheet; an empty queue closes it.
    if (this.requests.length > 0) {
      return;
    }
    this.router.navigate(['/radar']);
  }
}
