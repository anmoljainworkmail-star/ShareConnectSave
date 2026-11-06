import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { distinctUntilChanged } from 'rxjs/operators';

import { AuthService } from '../../../auth/auth.service';
import { ToastService } from '../../../shared/toast/toast.service';
import { ChatClosedNotification, ChatOpenedNotification, RatingEntryPath } from '../models/chat.models';
import { ChatSignalrService } from './chat-signalr.service';

const CHAT_ROUTE = '/chat';
// Rating screen (T069, FIGMA_SPEC.md Screen 14). The param is the CONNECTION
// id -- rating-service validates a rating against the connection_id it stored
// from chat.closed. T069 has not shipped yet, so until it registers this
// route the router rejects the navigation; openRating() degrades to a toast
// instead of an unhandled promise rejection.
const RATING_ROUTE = '/rating';

// "What does the APP do when a chat opens or closes" -- the same office-manager
// role ConnectionFlowService plays for connection events (read its header
// comment for the full analogy). Plain English: ChatSignalrService is the
// mailroom that receives the envelopes; this class reads each one and tells
// the right person (the router) what to do. Keeping the decision here means
// "go to the rating screen when a chat closes" can change without touching
// reconnect logic, and ChatComponent stays a pure presentation component
// (T068 "Single Responsibility").
//
// A root service started once from AppComponent, not logic inside
// ChatComponent: ChatOpened must pull the user into the chat from ANY screen
// (they are usually on the radar when the other side accepts), and a component
// that is not mounted yet cannot react to anything.
@Injectable({ providedIn: 'root' })
export class ChatFlowService {
  private started = false;

  constructor(
    private readonly authService: AuthService,
    private readonly chatSignalr: ChatSignalrService,
    private readonly toastService: ToastService,
    private readonly router: Router
  ) {}

  // Idempotent -- AppComponent calls it once, but a second call must not
  // double-subscribe (every event would then navigate twice).
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;

    // Hub lifetime follows the login session, exactly like NotificationService:
    // connect when a token exists, tear down on logout so the previous user's
    // socket cannot keep receiving their chat on a shared device.
    this.authService.isAuthenticated$.pipe(distinctUntilChanged()).subscribe(isAuthenticated => {
      if (isAuthenticated) {
        this.chatSignalr.connect();
        return;
      }
      this.chatSignalr.disconnect();
    });

    this.chatSignalr.chatOpened$.subscribe(notification => this.handleChatOpened(notification));
    this.chatSignalr.chatClosed$.subscribe(notification => this.handleChatClosed(notification));
  }

  // Shared by "ChatClosed" (server-driven) and the Met Successfully confirm
  // (user-driven), so both paths land on the rating screen the same way.
  openRating(connectionId: string, entry: RatingEntryPath): void {
    // Guard clause: already there (e.g. the user confirmed Met, and then the
    // server's ChatClosed arrives) -- navigating again would just re-trigger
    // the screen's own initialisation.
    if (this.router.url.startsWith(`${RATING_ROUTE}/`)) {
      return;
    }
    // Navigation state (not a query param) tells T069 which entry path this
    // is, so "Skip" can be offered on the timeout path only without the path
    // being forgeable by editing the URL.
    this.router.navigate([RATING_ROUTE, connectionId], { state: { entry } }).catch(() => {
      this.toastService.show('The chat has ended. The rating screen is not available yet.');
    });
  }

  private handleChatOpened(notification: ChatOpenedNotification): void {
    const target = `${CHAT_ROUTE}/${notification.connection_id}`;
    // Guard clause: the accept click already navigated here (ConnectionFlowService
    // .openChat) -- the push confirms it rather than needing a second hop.
    if (this.router.url === target) {
      return;
    }
    this.router.navigate([CHAT_ROUTE, notification.connection_id]).catch(() => undefined);
  }

  private handleChatClosed(notification: ChatClosedNotification): void {
    // The push may carry only the chat id; the route needs the connection id,
    // which we learned from ChatOpened.
    const connectionId = notification.connection_id ?? this.chatSignalr.connectionIdForChat(notification.chat_id);
    // Guard clause: a chat this session never saw open cannot be mapped to a
    // rating screen -- better to do nothing than navigate to a broken URL.
    if (!connectionId) {
      return;
    }
    const entry: RatingEntryPath = notification.closed_reason === 'met_successfully' ? 'met' : 'timeout';
    this.openRating(connectionId, entry);
  }
}
