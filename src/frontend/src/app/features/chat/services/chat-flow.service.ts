import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { distinctUntilChanged } from 'rxjs/operators';

import { AuthService } from '../../../auth/auth.service';
import { NotificationSoundService } from '../../../connections/notification-sound.service';
import { ToastService } from '../../../shared/toast/toast.service';
import {
  ChatClosedNotification,
  ChatOpenedNotification,
  NewMessageNotification,
  RatingEntryPath
} from '../models/chat.models';
import { ActiveChatService } from './active-chat.service';
import { ChatSignalrService } from './chat-signalr.service';

const CHAT_ROUTE = '/chat';
// Rating screen (T069, FIGMA_SPEC.md Screen 14). The param is the CONNECTION
// id -- rating-service validates a rating against the connection_id it stored
// from chat.closed. T069 registers this route (RatingModule); openRating()
// still degrades to a toast if the navigation is ever rejected, instead of an
// unhandled promise rejection.
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

  // Connection ids this session has finished rating (submitted, skipped, or
  // found already rated). In-memory on purpose: it only suppresses a duplicate
  // push within this session; the server's 409 remains the source of truth.
  private readonly ratingResolved = new Set<string>();

  constructor(
    private readonly authService: AuthService,
    private readonly chatSignalr: ChatSignalrService,
    private readonly activeChat: ActiveChatService,
    private readonly toastService: ToastService,
    private readonly soundService: NotificationSoundService,
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
      // Logout ends the session: the next user on this device must not inherit
      // the previous user's "already rated" suppressions.
      this.ratingResolved.clear();
    });

    this.chatSignalr.chatOpened$.subscribe(notification => this.handleChatOpened(notification));
    this.chatSignalr.chatClosed$.subscribe(notification => this.handleChatClosed(notification));
    this.chatSignalr.newMessage$.subscribe(notification => this.handleNewMessage(notification));

    // Tracks "is there a chat the user can return to" for the radar pill.
    // Started here (not by the pill) because the answer must stay current on
    // every screen, including ones that render no pill.
    this.activeChat.start();
  }

  // Shared by "ChatClosed" (server-driven) and the Met Successfully confirm
  // (user-driven), so both paths land on the rating screen the same way.
  openRating(connectionId: string, entry: RatingEntryPath): void {
    // Guard clause: this user already rated (or skipped) this connection. The
    // second participant's confirm closes the chat later, and the server's
    // ChatClosed push lands on a user who has long since left the rating
    // screen; without this, that push would yank them back to a screen that can
    // only answer "already rated". Idempotent handling of a duplicate event,
    // the client-side twin of a consumer's processed_events check.
    if (this.ratingResolved.has(connectionId)) {
      return;
    }
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

  // Called by RatingComponent once the user is done with a connection's rating.
  markRatingResolved(connectionId: string): void {
    this.ratingResolved.add(connectionId);
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

  // The Problem: the room-group "ReceiveMessage" only reaches someone who is
  // sitting on the chat screen. A user on the radar, the profile page or the
  // rating screen gets nothing -- the other person could be typing to them for
  // minutes and they would not know.
  //
  // How this solves it: the server also sends "NewMessage" to the recipient's
  // personal group, and this is where the APP decides what to do about it: ping
  // them (sound + tappable banner) unless they are already reading that chat.
  private handleNewMessage(notification: NewMessageNotification): void {
    // Guard clause: the user is looking at this very chat; the message is
    // already on screen, so a banner would only be noise.
    // Exact path match, not startsWith: "/chat/12" is a prefix of "/chat/123",
    // which is a DIFFERENT chat and must still alert.
    const currentPath = this.router.url.split(/[?#]/)[0];
    if (currentPath === `${CHAT_ROUTE}/${notification.connection_id}`) {
      return;
    }
    // Guard clause: this user already confirmed "Met Successfully" -- they have
    // said they are done with this person, so do not keep pulling them back.
    if (this.activeChat.hasConfirmedMet(notification.connection_id)) {
      return;
    }

    this.activeChat.recordUnread(notification.connection_id);
    this.soundService.playAlert();
    this.toastService.showBanner({
      title: 'New message',
      message: notification.preview,
      actionLabel: 'Open',
      actionRoute: [CHAT_ROUTE, notification.connection_id]
    });
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
