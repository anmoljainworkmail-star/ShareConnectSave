import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subject, interval, merge, of } from 'rxjs';
import { catchError, map, startWith, switchMap, takeUntil } from 'rxjs/operators';

import { ConnectionService } from '../../connections/connection.service';
import { NotificationService } from '../../connections/notification.service';

// Fallback only: pushes are the fast path, this just heals a missed one
// (SignalR never replays) and notices requests that expired without a push.
const SAFETY_REFRESH_MS = 30_000;

// The Problem: a connection request arrives as a one-shot toast/banner. If the
// user is looking away, or on another screen, the banner fades and there is no
// persistent place that says "someone is waiting for you".
//
// Why it matters: a request is only valid for a short window. A user who misses
// the toast loses a real connection without ever knowing it existed.
//
// How this solves it: a bell with a count badge that is always derived from the
// SERVER's list of pending requests (the source of truth), never from counting
// pushes locally -- a locally-kept counter drifts the moment one push is lost.
// Pushes and a slow timer only decide WHEN to re-ask, not what the answer is.
//
// Observer (via events): it reacts to the notification stream without the
// stream knowing a bell exists. switchMap cancels an in-flight fetch when a
// newer trigger arrives, so a slow response can never overwrite a fresher one.
@Component({
  selector: 'app-pending-requests-bell',
  templateUrl: './pending-requests-bell.component.html',
  styleUrl: './pending-requests-bell.component.css',
  // Module-based project (no standalone components) -- see UserCardComponent.
  standalone: false
})
export class PendingRequestsBellComponent implements OnInit, OnDestroy {
  count = 0;

  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly connectionService: ConnectionService,
    private readonly notificationService: NotificationService
  ) {}

  get label(): string {
    return this.count === 0
      ? 'Connection requests'
      : `Connection requests, ${this.count} waiting`;
  }

  get badgeText(): string {
    return this.count > 9 ? '9+' : String(this.count);
  }

  ngOnInit(): void {
    merge(
      this.notificationService.on('RequestReceived'),
      this.notificationService.on('RequestExpired'),
      this.notificationService.on('RequestDeclined'),
      this.notificationService.established$,
      interval(SAFETY_REFRESH_MS)
    )
      .pipe(
        startWith(undefined),
        switchMap(() =>
          this.connectionService.getPendingInbound().pipe(
            map(pending => pending.length),
            // A failed lookup keeps the last known count instead of flashing 0.
            catchError(() => of(null))
          )
        ),
        takeUntil(this.destroy$)
      )
      .subscribe(count => {
        if (count !== null) {
          this.count = count;
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
