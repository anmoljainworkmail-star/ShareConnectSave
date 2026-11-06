import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { distinctUntilChanged } from 'rxjs/operators';

import { AuthService } from '../auth/auth.service';
import { ToastService } from '../shared/toast/toast.service';
import { ConnectionService } from './connection.service';
import { NotificationPayload } from './models/connection.models';
import { NotificationService } from './notification.service';
import { NotificationSoundService } from './notification-sound.service';

const INBOUND_ROUTE = '/connections/inbound';

// Mediator-shaped coordination, named plainly: this class is the one place
// that decides "when event X arrives, what does the APP do about it". Plain
// English: NotificationService is the mailroom (it receives what the server
// pushes and sorts it, but never opens the envelopes); the toast, sound and
// router are the people who act; this class is the office manager who reads
// each envelope and tells the right person. That is exactly the split T067's
// "Single Responsibility" note asks for -- the hub class stays free of
// navigation and UI, and "what happens on accept" can change without touching
// reconnect logic. (Not a formal GoF Mediator -- there are no peer objects
// talking through it -- so no pattern is claimed beyond SRP.)
//
// A root service started once from AppComponent rather than logic inside a
// component: the reaction has to happen no matter which screen is open (or
// none), and components mount/unmount on every route change.
@Injectable({ providedIn: 'root' })
export class ConnectionFlowService {
  private started = false;

  constructor(
    private readonly authService: AuthService,
    private readonly notificationService: NotificationService,
    private readonly connectionService: ConnectionService,
    private readonly toastService: ToastService,
    private readonly soundService: NotificationSoundService,
    private readonly router: Router
  ) {}

  // Idempotent -- AppComponent calls it once, but a second call must not
  // double-subscribe (every event would then fire two toasts).
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;

    // The hub connection's lifetime follows the login session: connect when a
    // token exists, tear down on logout (otherwise the old user's socket
    // would keep receiving their notifications on a shared device).
    this.authService.isAuthenticated$.pipe(distinctUntilChanged()).subscribe(isAuthenticated => {
      if (isAuthenticated) {
        this.notificationService.connect();
        return;
      }
      this.notificationService.disconnect();
    });

    this.notificationService.on('RequestReceived').subscribe(payload => this.handleRequestReceived(payload));
    this.notificationService.on('RequestAccepted').subscribe(payload => this.handleRequestAccepted(payload));
    this.notificationService.on('RequestDeclined').subscribe(() => this.toastService.show('Request declined.'));
    this.notificationService.on('RequestExpired').subscribe(() => this.toastService.show('Request expired.'));

    // Re-sync after every (re)connect. SignalR does not replay pushes sent
    // while the socket was down, so a request that arrived during a tunnel
    // would otherwise never surface. The REST list is the source of truth;
    // the push is only a "look now" nudge.
    this.notificationService.established$.subscribe(() => this.surfacePendingRequests());
  }

  // Shared by the "accepted" push (requester side) and the Accept button
  // (recipient side), so both parties land in the same place.
  openChat(connectionId: number): void {
    // Guard clause: a payload with no usable id cannot be navigated to.
    if (!Number.isFinite(connectionId)) {
      return;
    }
    // The chat route is registered by T068 (Chat UI). Until it exists the
    // router rejects an unknown path -- degrade to a message instead of an
    // unhandled promise rejection. Param is the CONNECTION id: chat-service
    // keys a room by connection id (ChatRoomService.OpenRoomAsync), so T068
    // can resolve the room from it.
    this.router.navigate(['/chat', connectionId]).catch(() => {
      this.toastService.show('Request accepted. The chat screen is not available yet.', 'success');
    });
  }

  private handleRequestReceived(payload: NotificationPayload): void {
    this.soundService.playAlert();

    // The inbound screen refreshes itself on this same event; a banner
    // pointing at the screen the user is already looking at would be noise.
    if (this.isOnInboundScreen()) {
      return;
    }
    this.toastService.showBanner({
      title: 'New connection request',
      message: payload.body,
      actionLabel: 'View',
      actionRoute: [INBOUND_ROUTE]
    });
  }

  private handleRequestAccepted(payload: NotificationPayload): void {
    this.toastService.show('Request accepted. Opening chat...', 'success');
    this.openChat(Number(payload.data?.['connectionId']));
  }

  private surfacePendingRequests(): void {
    if (this.isOnInboundScreen()) {
      return;
    }
    this.connectionService.getPendingInbound().subscribe({
      next: pending => {
        if (pending.length === 0) {
          return;
        }
        this.toastService.showBanner({
          title: 'Pending connection requests',
          message: pending.length === 1 ? 'Someone is waiting for your reply.' : `${pending.length} people are waiting for your reply.`,
          actionLabel: 'View',
          actionRoute: [INBOUND_ROUTE]
        });
      },
      // Best-effort nudge, not a user-initiated action: a failure here is not
      // worth interrupting anyone (they can still open Activity manually).
      error: () => undefined
    });
  }

  private isOnInboundScreen(): boolean {
    return this.router.url.startsWith(INBOUND_ROUTE);
  }
}
