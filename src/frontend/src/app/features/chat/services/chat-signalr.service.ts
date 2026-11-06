import { Injectable } from '@angular/core';
import { HttpError, HubConnection, HubConnectionBuilder, HubConnectionState, LogLevel } from '@microsoft/signalr';
import { BehaviorSubject, Observable, Subject } from 'rxjs';
import { filter, map, take } from 'rxjs/operators';

import { environment } from '../../../../environments/environment';
import { AuthService } from '../../../auth/auth.service';
import {
  RECONNECT_DELAYS_MS,
  RECONNECT_STEADY_DELAY_MS,
  UnlimitedRetryPolicy
} from '../../../shared/signalr/unlimited-retry-policy';
import {
  ChatClosedNotification,
  ChatHubStatus,
  ChatMessage,
  ChatOpenedNotification
} from '../models/chat.models';

// Observer (GoF) -- the same pattern chat-service's ChatHub documents on the
// server side, seen from the browser end. Plain English: a radio station does
// not know who is listening or what they do with the broadcast; it just
// transmits. The hub pushes "ChatOpened" / "ReceiveMessage" / "ChatClosed"
// with no idea what the Angular app will do about each one; this service turns
// every push into an RxJS stream (the "radio receiver"), and each interested
// part of the app tunes in independently. Nothing here polls.
//
// Single Responsibility (S): this class owns exactly the hub connection's
// lifecycle and the fan-out of what arrives on it. It deliberately does NOT
// navigate, show toasts, render anything, or call REST -- ChatFlowService
// decides what the APP does on ChatOpened/ChatClosed, ChatComponent only
// renders, ChatApiService only does the HTTP call. Without that split, "what
// happens when a chat closes" would be tangled into reconnect-timer code.
//
// Root-provided service, not component-owned (angular-pwa skill: "connection
// lifecycle owned by service, not component"): the socket has to be alive
// BEFORE ChatComponent exists -- "ChatOpened" is what causes the component to
// be navigated to in the first place -- and has to survive the component being
// destroyed when the user taps the back arrow while the chat is still open.
@Injectable({ providedIn: 'root' })
export class ChatSignalrService {
  private connection: HubConnection | null = null;
  private retryTimerId: ReturnType<typeof setTimeout> | null = null;
  // Distinguishes "we asked it to stop" (logout) from "it died" -- only the
  // latter should trigger a restart.
  private intentionallyStopped = true;

  // SignalR groups are bound to a CONNECTION id, not to a user. After an
  // automatic reconnect the browser holds a brand-new connection id that
  // belongs to none of the groups the old one joined, so the server would
  // silently stop sending this chat's messages. Remembering which rooms the UI
  // wants lets every (re)connect re-issue JoinRoom -- without this, the
  // "reconnected" banner would disappear while messages quietly stopped
  // arriving, the worst kind of failure because nothing looks broken.
  // (The personal "user:{id}" group needs no such bookkeeping: the server
  // re-adds it in ChatHub.OnConnectedAsync on every connection.)
  private readonly joinedRooms = new Set<string>();

  // connection_id -> chat_id, learned ONLY from "ChatOpened". The route is
  // /chat/:connectionId (that is what ConnectionFlowService navigates with)
  // but JoinRoom/SendMessage/POST met all key on the Mongo chat id, and
  // chat-service exposes no "look up room by connection" endpoint. In-memory on
  // purpose -- it is a routing hint, not message history.
  private readonly chatRoomsSubject = new BehaviorSubject<ReadonlyMap<string, string>>(new Map());
  // Client-side "when did I learn this chat opened" (epoch ms) -- the only
  // clock available for the closes-in-30-minutes system message, since
  // ChatOpened carries no timestamp.
  private readonly openedAtByChat = new Map<string, number>();

  // Subjects (hot, no replay): a push is a moment in time, not state. A late
  // subscriber must not be replayed a "ChatOpened" from ten minutes ago and be
  // yanked into a chat screen.
  private readonly chatOpenedSubject = new Subject<ChatOpenedNotification>();
  private readonly chatClosedSubject = new Subject<ChatClosedNotification>();
  private readonly messageSubject = new Subject<ChatMessage>();
  private readonly establishedSubject = new Subject<void>();
  private readonly roomJoinFailedSubject = new Subject<string>();
  private readonly statusSubject = new BehaviorSubject<ChatHubStatus>('disconnected');

  readonly chatOpened$: Observable<ChatOpenedNotification> = this.chatOpenedSubject.asObservable();
  readonly chatClosed$: Observable<ChatClosedNotification> = this.chatClosedSubject.asObservable();
  readonly messages$: Observable<ChatMessage> = this.messageSubject.asObservable();
  readonly established$: Observable<void> = this.establishedSubject.asObservable();
  // Emits the chat id of a room the server refused (not a participant, etc.).
  readonly roomJoinFailed$: Observable<string> = this.roomJoinFailedSubject.asObservable();
  readonly status$: Observable<ChatHubStatus> = this.statusSubject.asObservable();

  constructor(private readonly authService: AuthService) {}

  // Emits (once) the chat id for a connection as soon as "ChatOpened" has told
  // us -- immediately if it already did. This is what lets ChatComponent be
  // opened by the accept click BEFORE the Kafka -> chat-service -> SignalR hop
  // finishes: it simply waits here instead of racing.
  chatIdForConnection$(connectionId: string): Observable<string> {
    return this.chatRoomsSubject.pipe(
      map(rooms => rooms.get(connectionId)),
      filter((chatId): chatId is string => chatId !== undefined),
      take(1)
    );
  }

  connectionIdForChat(chatId: string): string | null {
    for (const [connectionId, knownChatId] of this.chatRoomsSubject.value) {
      if (knownChatId === chatId) {
        return connectionId;
      }
    }
    return null;
  }

  openedAtFor(chatId: string): number | null {
    return this.openedAtByChat.get(chatId) ?? null;
  }

  // Idempotent: called on every "authenticated" emission, safe to call twice.
  connect(): void {
    if (this.connection) {
      return;
    }
    this.intentionallyStopped = false;

    const connection = new HubConnectionBuilder()
      // Route: gateway's "chat-route" strips "/chat", so this reaches
      // chat-service's MapHub("/hubs/chat").
      .withUrl(`${environment.apiBase}/chat/hubs/chat`, {
        // Browsers cannot set an Authorization header on a WebSocket upgrade
        // request, so SignalR sends this as `?access_token=` -- exactly what
        // the gateway's JwtValidationMiddleware accepts for /hubs/ paths
        // (T068 "Known follow-ups"; do not replace with a header). A factory,
        // not a captured string, so every reconnect asks for the CURRENT token.
        // getFreshAccessToken() refreshes it BEFORE it expires: SignalR's own
        // HTTP calls bypass AuthInterceptor, so without this the automatic
        // reconnect would keep presenting a token that expired an hour ago.
        // The token itself still comes from AuthService's storage, never hardcoded.
        accessTokenFactory: () => this.authService.getFreshAccessToken()
      })
      .withAutomaticReconnect(new UnlimitedRetryPolicy())
      .configureLogging(environment.production ? LogLevel.Warning : LogLevel.Information)
      .build();

    connection.on('ChatOpened', (notification: ChatOpenedNotification) => this.handleChatOpened(notification));
    connection.on('ReceiveMessage', (message: ChatMessage) => this.messageSubject.next(message));
    connection.on('ChatClosed', (notification: ChatClosedNotification) => this.chatClosedSubject.next(notification));

    connection.onreconnecting(() => this.statusSubject.next('reconnecting'));
    connection.onreconnected(() => this.markEstablished());
    // With an unlimited retry policy onclose only fires for stop() or a
    // non-recoverable server decision; either way, restart unless WE stopped
    // it, so a "dead until reload" chat cannot happen.
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
    // Logout ends every room: the next user on this device must not inherit
    // the previous user's id mappings or group memberships.
    this.joinedRooms.clear();
    this.openedAtByChat.clear();
    this.chatRoomsSubject.next(new Map());
    if (!connection) {
      return;
    }
    // Best-effort: stop() rejects if the connection never finished starting,
    // which is fine -- the goal (no live socket) is met either way.
    connection.stop().catch(() => undefined);
  }

  // Resolves once the server has put THIS connection into the chat's group.
  // If the socket is not up yet (or is mid-reconnect) there is nothing to
  // invoke -- the room is only recorded, and markEstablished() joins it the
  // moment the socket is usable. Rejects if the server refuses (HubException:
  // not a participant).
  joinRoom(chatId: string): Promise<void> {
    this.joinedRooms.add(chatId);
    const connection = this.connection;
    if (!connection || connection.state !== HubConnectionState.Connected) {
      return Promise.resolve();
    }
    return connection.invoke('JoinRoom', chatId);
  }

  leaveRoom(chatId: string): void {
    this.joinedRooms.delete(chatId);
    const connection = this.connection;
    // Guard clause: nothing to tell the server if the socket is not up -- a
    // dead connection's groups vanish with it anyway.
    if (!connection || connection.state !== HubConnectionState.Connected) {
      return;
    }
    connection.invoke('LeaveRoom', chatId).catch(() => undefined);
  }

  // Rejects (never silently queues) while offline: the UI disables the input
  // when disconnected, and a message that looks "sent" but is not is far worse
  // than one the user is told did not go. No silent retry (T068 "what NOT to
  // do") -- the user decides whether to resend.
  sendMessage(chatId: string, content: string): Promise<void> {
    const connection = this.connection;
    if (!connection || connection.state !== HubConnectionState.Connected) {
      return Promise.reject(new Error('Not connected to chat.'));
    }
    return connection.invoke('SendMessage', chatId, content);
  }

  // Records a mapping learned from the REST lookup (GET
  // /chats/by-connection/:id) -- the recovery path for a client that missed the
  // one-time ChatOpened push. Does NOT emit chatOpened$: this is not a "chat
  // just opened" moment, so it must not yank anyone into a chat screen.
  // Deliberately does NOT set openedAtByChat: the lookup carries no open time,
  // and Date.now() here would be a made-up clock (wrong countdown after reload).
  // Only handleChatOpened, which actually observed the open, records a time.
  rememberChatRoom(connectionId: string, chatId: string): void {
    const rooms = new Map(this.chatRoomsSubject.value);
    rooms.set(String(connectionId), chatId);
    this.chatRoomsSubject.next(rooms);
  }

  private handleChatOpened(notification: ChatOpenedNotification): void {
    // Record the mapping BEFORE emitting, so a subscriber reacting to the
    // event (ChatFlowService navigating, ChatComponent resolving) can already
    // look the chat id up.
    const rooms = new Map(this.chatRoomsSubject.value);
    rooms.set(String(notification.connection_id), notification.chat_id);
    this.openedAtByChat.set(notification.chat_id, Date.now());
    this.chatRoomsSubject.next(rooms);
    this.chatOpenedSubject.next(notification);
  }

  // withAutomaticReconnect only covers a connection that HAD started and then
  // dropped. If the very first start() fails (server restarting, offline at
  // page load) nothing retries it -- so this loop is what makes "reconnect
  // automatically" true from the first second.
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
    this.rejoinRooms();
    this.establishedSubject.next();
  }

  private rejoinRooms(): void {
    const connection = this.connection;
    if (!connection) {
      return;
    }
    for (const chatId of this.joinedRooms) {
      connection.invoke('JoinRoom', chatId).catch(() => this.roomJoinFailedSubject.next(chatId));
    }
  }
}
