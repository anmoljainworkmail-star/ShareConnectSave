import { Injectable } from '@angular/core';
import { HttpError, HubConnection, HubConnectionBuilder, LogLevel } from '@microsoft/signalr';
import { BehaviorSubject, Observable, Subject } from 'rxjs';
import { filter } from 'rxjs/operators';

import { environment } from '../../environments/environment';
import { AuthService } from '../auth/auth.service';
import {
  RECONNECT_DELAYS_MS,
  RECONNECT_STEADY_DELAY_MS,
  UnlimitedRetryPolicy
} from '../shared/signalr/unlimited-retry-policy';
import {
  HubConnectionStatus,
  NOTIFICATION_EVENT_NAMES,
  NotificationEvent,
  NotificationEventName,
  NotificationPayload
} from './models/connection.models';

// "SignalR reconnects automatically" (T067 AC3) needs a custom IRetryPolicy
// that never gives up -- moved to a shared file in T068 so the chat hub reuses
// the exact same schedule instead of keeping a second copy.
// Observer (GoF) -- the project's own Kafka-topics pattern, here on the
// browser end of a WebSocket. Plain English: a newspaper does not know who its
// subscribers are or what they do with it; it just gets delivered, and each
// subscriber reacts independently. The server pushes "RequestReceived" /
// "RequestAccepted" / ... to this user's personal SignalR group without any
// idea what the Angular app will do about it; this service turns each push
// into a value on `events$`, and every interested part of the app (toast,
// banner, router, inbound screen) subscribes on its own. Nothing here polls.
//
// Single Responsibility (S): this class owns exactly two things -- the hub
// connection's lifecycle and fan-out of what arrives on it. It deliberately
// does NOT navigate, show toasts, play sounds or call the REST API: that is
// ConnectionFlowService's job. Without that split, a change to "what should
// happen on accept" would mean editing the class that also manages
// reconnect timers.
//
// Lives in a root-provided service, not a component (angular-pwa skill's
// "connection lifecycle owned by service, not component"): components mount
// and unmount on every route change, but the socket must survive navigation
// or a request arriving while the user is on another screen would be lost.
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private connection: HubConnection | null = null;
  private retryTimerId: ReturnType<typeof setTimeout> | null = null;
  // Distinguishes "we asked it to stop" (logout) from "it died" -- only the
  // latter should trigger a restart.
  private intentionallyStopped = true;

  private readonly eventsSubject = new Subject<NotificationEvent>();
  private readonly establishedSubject = new Subject<void>();
  private readonly statusSubject = new BehaviorSubject<HubConnectionStatus>('disconnected');

  // Hot stream of every server push. Subject (not ReplaySubject): a push is a
  // moment in time, not state -- a late subscriber must not be replayed a
  // "RequestAccepted" from ten minutes ago and navigate to chat.
  readonly events$: Observable<NotificationEvent> = this.eventsSubject.asObservable();

  // Fires every time the socket becomes usable -- first start AND each
  // reconnect. SignalR has no message replay: anything the server pushed
  // while the socket was down is gone. Consumers use this signal to re-sync
  // from the REST source of truth instead of assuming they saw everything.
  readonly established$: Observable<void> = this.establishedSubject.asObservable();

  readonly status$: Observable<HubConnectionStatus> = this.statusSubject.asObservable();

  constructor(private readonly authService: AuthService) {}

  // Convenience for consumers that care about one event type only.
  on(eventName: NotificationEventName): Observable<NotificationPayload> {
    return new Observable<NotificationPayload>(subscriber => {
      const subscription = this.events$
        .pipe(filter(event => event.name === eventName))
        .subscribe(event => subscriber.next(event.payload));
      return () => subscription.unsubscribe();
    });
  }

  // Idempotent: called on every "authenticated" emission, safe to call twice.
  connect(): void {
    if (this.connection) {
      return;
    }
    this.intentionallyStopped = false;

    const connection = new HubConnectionBuilder()
      // Route: gateway's "notification-route" strips "/notification", so this
      // reaches notification-service's MapHub("/hubs/notifications").
      .withUrl(`${environment.apiBase}/notification/hubs/notifications`, {
        // Browsers cannot set an Authorization header on a WebSocket upgrade
        // request, so SignalR sends this as `?access_token=` -- exactly what
        // the gateway's JwtValidationMiddleware accepts for /hubs/ paths
        // (T067 "Known follow-ups"; do not replace with a header). A factory
        // (not a captured string) so every reconnect asks for the CURRENT token.
        // getFreshAccessToken() refreshes it BEFORE it expires, because SignalR's
        // own HTTP calls bypass AuthInterceptor -- without this, a reconnect
        // after the 60-minute access token lapses would 401 forever. The token
        // itself comes from AuthService's storage -- never hardcoded here.
        accessTokenFactory: () => this.authService.getFreshAccessToken()
      })
      .withAutomaticReconnect(new UnlimitedRetryPolicy())
      .configureLogging(environment.production ? LogLevel.Warning : LogLevel.Information)
      .build();

    // Open/Closed (O): one loop over the event-name list, not one hand-written
    // .on() per event.
    for (const eventName of NOTIFICATION_EVENT_NAMES) {
      connection.on(eventName, (payload: NotificationPayload) => {
        this.eventsSubject.next({ name: eventName, payload });
      });
    }

    connection.onreconnecting(() => this.statusSubject.next('reconnecting'));
    connection.onreconnected(() => this.markEstablished());
    // With an unlimited retry policy onclose only fires for stop() or a
    // non-recoverable server decision; either way, restart unless WE stopped
    // it, so the "dead until reload" state cannot happen.
    connection.onclose(() => {
      this.statusSubject.next('disconnected');
      if (this.intentionallyStopped) {
        return;
      }
      this.scheduleStart(RECONNECT_STEADY_DELAY_MS);
    });

    this.connection = connection;
    this.startConnection();
  }

  disconnect(): void {
    this.intentionallyStopped = true;
    this.clearRetryTimer();

    const connection = this.connection;
    this.connection = null;
    this.statusSubject.next('disconnected');
    if (!connection) {
      return;
    }
    // Best-effort: stop() rejects if the connection never finished starting,
    // which is fine -- the goal (no live socket) is met either way.
    connection.stop().catch(() => undefined);
  }

  // withAutomaticReconnect only covers a connection that HAD started and then
  // dropped. If the very first start() fails (server restarting, offline at
  // page load) nothing retries it -- so this loop is what makes "reconnect
  // automatically" true from the first second, not just after the first
  // successful connect.
  private startConnection(): void {
    const connection = this.connection;
    if (!connection) {
      return;
    }

    this.statusSubject.next('connecting');
    connection
      .start()
      .then(() => this.markEstablished())
      .catch((error: unknown) => {
        if (this.intentionallyStopped || this.connection !== connection) {
          return;
        }
        this.statusSubject.next('disconnected');
        this.refreshTokenIfRejected(error);
        this.scheduleStart(RECONNECT_DELAYS_MS[1]);
      });
  }

  // A 401 on the negotiate request is the one failure retrying cannot fix: the
  // stored access token is expired, and SignalR's own HTTP calls bypass
  // AuthInterceptor's refresh-on-401. Ask AuthService for a fresh one (it
  // stores it, so the next accessTokenFactory call sees it) and let the
  // ordinary retry timer try again.
  private refreshTokenIfRejected(error: unknown): void {
    if (!(error instanceof HttpError) || error.statusCode !== 401) {
      return;
    }
    this.authService.refreshToken().subscribe({ error: () => undefined });
  }

  private scheduleStart(delayMs: number): void {
    this.clearRetryTimer();
    this.retryTimerId = setTimeout(() => {
      this.retryTimerId = null;
      this.startConnection();
    }, delayMs);
  }

  private clearRetryTimer(): void {
    if (this.retryTimerId === null) {
      return;
    }
    clearTimeout(this.retryTimerId);
    this.retryTimerId = null;
  }

  private markEstablished(): void {
    this.statusSubject.next('connected');
    this.establishedSubject.next();
  }
}
