import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { EMPTY, Observable, Subject, forkJoin, merge, of, timer } from 'rxjs';
import { catchError, filter, map, switchMap, take, takeUntil, tap, timeout } from 'rxjs/operators';

import { environment } from '../../../environments/environment';
import { ErrorEnvelope } from '../../auth/models/auth.models';
import { ConnectionService } from '../../connections/connection.service';
import { ConnectionResponse } from '../../connections/models/connection.models';
import { PublicUserProfileDto, UserProfileDto } from '../../profile-setup/models/profile-setup.models';
import { UserProfileService } from '../../profile-setup/user-profile.service';
import { ToastService } from '../../shared/toast/toast.service';
import {
  ChatHubStatus,
  ChatMessage,
  ChatParticipants,
  ChatRoomLookup,
  ChatTimelineItem,
  RatingEntryPath
} from './models/chat.models';
import { ActiveChatService } from './services/active-chat.service';
import { ChatApiService } from './services/chat-api.service';
import { ChatFlowService } from './services/chat-flow.service';
import { ChatSignalrService } from './services/chat-signalr.service';

// 'resolving' = the route opened (the accept click navigates here immediately)
// but ChatOpened has not told us the chat id yet; 'unavailable' = it never did,
// or the server refused the room.
type ChatViewState = 'resolving' | 'ready' | 'unavailable';

// How long to wait for "ChatOpened" after the screen opens. The normal gap is
// a Kafka hop plus a SignalR push (well under a second); this only bounds the
// case where it never comes -- e.g. a page reload on /chat/:id, where the
// in-memory connection -> chat id mapping is gone and chat-service has no
// lookup endpoint (see the T068 follow-up note).
const ROOM_RESOLVE_TIMEOUT_MS = 15_000;

// FIGMA_SPEC.md Screen 12: a centered timestamp appears when two messages are
// more than 5 minutes apart; the countdown system message appears at 30
// minutes left.
const TIMESTAMP_GAP_MS = 5 * 60_000;
const CLOSING_WARNING_MINUTES = 30;
const CLOSING_CHECK_INTERVAL_MS = 30_000;

// Graceful degradation (same instinct as InboundRequestComponent's fallback
// card): the chat still works if the other person's profile cannot be loaded.
const FALLBACK_OTHER_NAME = 'Your travel companion';

// FIGMA_SPEC.md Screens 12 + 13 (Chat + Met Successfully confirmation),
// matched to the approved Screen 12 mockup
// (https://claude.ai/artifact/CAu6pBZqysAqa8yKct6jrw).
//
// Single Responsibility (S), per the ticket: this component only RENDERS chat
// state and forwards user actions. It does not own the socket
// (ChatSignalrService), the "where do I go when a chat opens/closes" decision
// (ChatFlowService) or the HTTP call (ChatApiService) -- which is also why it
// can be destroyed by the back arrow while the connection lives on.
//
// Observer (via events): messages arrive as a stream the component subscribes
// to; it has no idea who sent the push or how it travelled, and nothing here
// polls.
//
// Ephemeral-by-design still holds: messages are held in memory only, never
// cached or exported client-side, and chat-service's MongoDB TTL is the sole
// authority on message lifetime. The one exception is loadHistory(), which
// re-reads the LIVE room's recent messages so leaving and re-entering the
// screen during the chat's lifetime does not look like a new empty chat.
@Component({
  selector: 'app-chat',
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.css',
  // Module-based project (no standalone components) -- see UserCardComponent.
  standalone: false
})
export class ChatComponent implements OnInit, OnDestroy {
  viewState: ChatViewState = 'resolving';
  hubStatus: ChatHubStatus = 'disconnected';
  participants: ChatParticipants | null = null;
  photoUrl: string | null = null;
  timeline: ChatTimelineItem[] = [];

  draft = '';
  isSending = false;
  showMetDialog = false;
  isConfirmingMet = false;

  @ViewChild('scrollArea') private scrollArea?: ElementRef<HTMLElement>;

  private connectionId = '';
  private chatId: string | null = null;
  private stickToBottom = true;
  private lastMessageAtMs: number | null = null;
  private closingWarningShown = false;
  // De-duplication by server-assigned id: a rejoin after reconnect must never
  // render the same message twice.
  private readonly seenMessageIds = new Set<string>();
  private nextSystemKey = 1;
  // Only a blob: URL THIS component minted is ours to revoke (same rule
  // UserCardComponent applies).
  private ownedBlobUrl: string | null = null;
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly chatSignalr: ChatSignalrService,
    private readonly chatApi: ChatApiService,
    private readonly chatFlow: ChatFlowService,
    private readonly activeChat: ActiveChatService,
    private readonly connectionService: ConnectionService,
    private readonly userProfileService: UserProfileService,
    private readonly toastService: ToastService,
    private readonly host: ElementRef<HTMLElement>
  ) {}

  // Offline = anything other than a live socket. The banner and the disabled
  // input are REQUIRED behaviour (T068), not polish -- a silently-disconnected
  // chat looks identical to a quiet one, so the user must be told.
  get isOffline(): boolean {
    return this.hubStatus !== 'connected';
  }

  get offlineLabel(): string {
    return this.hubStatus === 'connecting' ? 'Connecting...' : 'Reconnecting...';
  }

  get otherName(): string {
    return this.participants?.otherName ?? FALLBACK_OTHER_NAME;
  }

  get canSend(): boolean {
    return !this.isOffline && !this.isSending && this.draft.trim().length > 0;
  }

  ngOnInit(): void {
    this.trackVisualViewport();

    const connectionId = this.route.snapshot.paramMap.get('id');
    // Guard clause: a route without an id cannot name a chat.
    if (!connectionId) {
      this.viewState = 'unavailable';
      return;
    }
    this.connectionId = connectionId;
    // Opening the screen is what "read" means: clears the unread badge on the
    // radar's "Return to chat" pill.
    this.activeChat.markViewed(connectionId);

    this.chatSignalr.status$.pipe(takeUntil(this.destroy$)).subscribe(status => {
      this.hubStatus = status;
    });

    this.chatSignalr.roomJoinFailed$
      .pipe(
        filter(failedChatId => failedChatId === this.chatId),
        takeUntil(this.destroy$)
      )
      .subscribe(() => this.markUnavailable('You can no longer join this chat.'));

    // Two independent things the screen needs before it can render honestly:
    // the chat id (waits for ChatOpened) and who is in the room. forkJoin runs
    // them in parallel and emits once both are done.
    forkJoin({
      chatId: this.resolveChatId(connectionId).pipe(timeout({ first: ROOM_RESOLVE_TIMEOUT_MS })),
      participants: this.loadParticipants()
    })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: ({ chatId, participants }) => this.enterRoom(chatId, participants),
        error: () => this.markUnavailable()
      });
  }

  // Race of two independent sources for the same fact, first one wins:
  //  - PUSH: wait for the hub's one-time ChatOpened (covers "user tapped
  //    Accept and the room is still being created" -- the REST call below 404s
  //    in that window, which is expected and ignored).
  //  - PULL: ask chat-service directly (covers "page was reloaded / hub
  //    connected late", where the push already happened and is gone).
  // Without the pull, a reload on /chat/:id would sit on a spinner forever
  // because ChatOpened is fired once and never replayed.
  private resolveChatId(connectionId: string): Observable<string> {
    const pulled$ = this.chatApi.getChatByConnection(connectionId).pipe(
      // A CLOSED room is not a live chat, so it never becomes a chat id. The
      // screen leaves for the rating flow instead (the navigation destroys this
      // component, which also cancels the pending timeout in forkJoin).
      tap(room => {
        if (room.status === 'CLOSED') {
          this.sendClosedRoomToRating(room);
        }
      }),
      filter(room => room.status !== 'CLOSED'),
      tap(room => this.chatSignalr.rememberChatRoom(connectionId, room.chat_id)),
      map(room => room.chat_id),
      catchError(() => EMPTY)
    );
    // merge + take(1), NOT race(): race() forwards a loser's complete, so the
    // REST 404 -> EMPTY would end the whole stream before ChatOpened arrives.
    return merge(this.chatSignalr.chatIdForConnection$(connectionId), pulled$).pipe(take(1));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    // Leaving the screen is not closing the chat: only drop THIS screen's
    // interest in the room. The hub connection itself stays up (it also
    // delivers ChatOpened/ChatClosed for every other screen).
    if (this.chatId) {
      this.chatSignalr.leaveRoom(this.chatId);
    }
    if (this.ownedBlobUrl) {
      URL.revokeObjectURL(this.ownedBlobUrl);
    }
    this.stopTrackingVisualViewport();
  }

  // The Problem: on a phone, the on-screen keyboard covers part of the page but
  // does NOT shrink the "layout viewport" the browser lays CSS out against. So a
  // shell sized `100dvh` stays as tall as the whole screen, and the keyboard
  // slides over its bottom edge, hiding the message box and the Send button.
  //
  // Why it matters: a chat screen whose input is hidden by the keyboard is
  // unusable, and the bug only shows on real phones (desktop has no keyboard
  // overlay), so it is easy to ship without noticing.
  //
  // How this solves it: the Visual Viewport API reports the part of the page the
  // user can actually SEE, keyboard excluded. Mirroring its height into a CSS
  // variable makes the flex column (header, scrolling list, footer) fit exactly
  // the visible area, so the footer sits just above the keyboard. Feature-
  // detected (progressive enhancement): browsers without the API keep the plain
  // 100dvh fallback in the stylesheet and behave as before.
  //
  // Example: a 800px-tall phone opens the keyboard (~300px). visualViewport.height
  // becomes ~500px, the shell shrinks to 500px, and the input row stays visible.
  private trackVisualViewport(): void {
    const viewport = window.visualViewport;
    // Guard clause: no Visual Viewport support, keep the CSS fallback.
    if (!viewport) {
      return;
    }

    const apply = (): void => {
      this.host.nativeElement.style.setProperty('--chat-viewport-height', `${viewport.height}px`);
      // iOS Safari pans the whole page upward to keep a focused field in view
      // instead of resizing it; pinning the page back to the top stops the
      // header from being pushed off-screen.
      if (viewport.offsetTop > 0) {
        window.scrollTo(0, 0);
      }
      // Fewer pixels for the list means the latest message can slip below the
      // fold, so keep it in view if the reader was already at the bottom.
      if (this.stickToBottom) {
        this.scrollToBottomSoon();
      }
    };

    apply();
    viewport.addEventListener('resize', apply);
    viewport.addEventListener('scroll', apply);
    this.stopTrackingVisualViewport = () => {
      viewport.removeEventListener('resize', apply);
      viewport.removeEventListener('scroll', apply);
      this.host.nativeElement.style.removeProperty('--chat-viewport-height');
    };
  }

  private stopTrackingVisualViewport: () => void = () => undefined;

  // Esc closes the confirmation dialog -- standard modal behaviour, and the
  // keyboard route to "Not yet".
  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.cancelMet();
  }

  goBack(): void {
    this.router.navigate(['/radar']);
  }

  // Reporting is its own flow (Report Service, later ticket); the flag is in
  // the spec'd app bar, so it is present but only acknowledges the tap.
  reportUser(): void {
    this.toastService.show('Reporting from chat is coming soon.');
  }

  send(): void {
    // Guard clause: one check covers empty text, offline and in-flight.
    if (!this.chatId || !this.canSend) {
      return;
    }
    const content = this.draft.trim();
    this.isSending = true;
    this.chatSignalr
      .sendMessage(this.chatId, content)
      .then(() => {
        // Server-echo as the single source of truth: we do NOT append the
        // message locally. The hub persists first, then broadcasts to the whole
        // room -- including the sender -- so the sender's own bubble arrives
        // through the same "ReceiveMessage" path as everyone's. An optimistic
        // local append would show a message the server might still reject, and
        // would render twice when the echo arrived.
        this.draft = '';
        this.stickToBottom = true;
      })
      .catch(() => this.toastService.show('Message not sent. Please try again.', 'error'))
      .finally(() => {
        this.isSending = false;
      });
  }

  openMetDialog(): void {
    // Guard clause: nothing to confirm until a room exists.
    if (!this.chatId) {
      return;
    }
    this.showMetDialog = true;
  }

  cancelMet(): void {
    // Guard clause: do not allow dismissing mid-request -- the POST is already
    // on its way and the user would lose the outcome.
    if (this.isConfirmingMet) {
      return;
    }
    this.showMetDialog = false;
  }

  // Met Successfully is irreversible (it starts closing the chat for both
  // people), which is why it can only be reached THROUGH the dialog: the
  // button opens it, only this method calls the API.
  confirmMet(): void {
    if (!this.chatId || this.isConfirmingMet) {
      return;
    }
    this.isConfirmingMet = true;
    this.chatApi
      .markMetSuccessfully(this.chatId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isConfirmingMet = false;
          this.showMetDialog = false;
          // Met is the user's own "I am done here": stop offering a way back
          // into this chat (the server keeps the room until the other person
          // confirms too).
          this.activeChat.markMetConfirmed(this.connectionId);
          this.chatFlow.openRating(this.connectionId, 'met');
        },
        error: (error: ErrorEnvelope) => {
          this.isConfirmingMet = false;
          this.showMetDialog = false;
          // The chat was already closed (timeout, or the other person's
          // confirmation finished it first): the user still belongs on the
          // rating screen. Entered as the timeout path, whose rules are the
          // lenient ones (Skip allowed) -- safer than forcing the strict
          // "pick a tag" path when we cannot tell which side closed it.
          if (error.code === 'CHAT_ALREADY_CLOSED') {
            this.chatFlow.openRating(this.connectionId, 'timeout');
            return;
          }
          this.toastService.show(error.message, 'error');
        }
      });
  }

  onScroll(): void {
    const element = this.scrollArea?.nativeElement;
    if (!element) {
      return;
    }
    // Follow new messages only while the reader is already at (or near) the
    // bottom -- yanking the view down while they scroll up to re-read would be
    // hostile.
    const distanceFromBottom = element.scrollHeight - element.scrollTop - element.clientHeight;
    this.stickToBottom = distanceFromBottom < 80;
  }

  isSentByMe(message: ChatMessage): boolean {
    return this.participants?.myUserId === message.sender_id;
  }

  initials(name: string): string {
    const letters = name
      .split(/\s+/)
      .filter(part => part.length > 0)
      .slice(0, 2)
      .map(part => part.charAt(0).toUpperCase());
    return letters.join('') || '?';
  }

  trackByKey(_index: number, item: ChatTimelineItem): string {
    return item.key;
  }

  private enterRoom(chatId: string, participants: ChatParticipants): void {
    this.chatId = chatId;
    this.participants = participants;
    this.resolvePhoto(participants.otherPhotoUrl);

    const openedAt = this.chatSignalr.openedAtFor(chatId);
    this.timeline = [{ kind: 'system', key: this.systemKey(), text: this.openedMessage(openedAt) }];

    // Subscribe BEFORE joining: nothing is delivered until JoinRoom succeeds,
    // but subscribing first means no message can ever slip between "joined"
    // and "listening".
    this.chatSignalr.messages$
      .pipe(
        filter(message => message.chat_id === chatId),
        takeUntil(this.destroy$)
      )
      .subscribe(message => this.appendMessage(message));

    // If the socket is not up yet this only records the room; the service
    // joins it as soon as the connection is established, and again after every
    // reconnect.
    this.chatSignalr.joinRoom(chatId).catch(() => this.markUnavailable('You can no longer join this chat.'));

    // Pattern: poll-on-reconnect (a state re-sync, not a replayed event). The
    // ChatClosed push is fire-and-forget: a close that happened while this
    // client was offline is never redelivered. Each time the socket comes back,
    // ask the server for the room's CURRENT status instead of trusting that the
    // push arrived. A closed room is handed to the rating flow.
    this.chatSignalr.established$
      .pipe(
        switchMap(() => this.chatApi.getChatByConnection(this.connectionId).pipe(catchError(() => EMPTY))),
        filter(room => room.status === 'CLOSED'),
        takeUntil(this.destroy$)
      )
      .subscribe(room => this.sendClosedRoomToRating(room));

    this.watchClosingCountdown(chatId);
    this.viewState = 'ready';
    this.loadHistory(chatId);
  }

  // The Problem: this component is destroyed whenever the user leaves the
  // screen (to rating, to the radar) and rebuilt when they return, and the live
  // feed only carries messages sent AFTER subscribing. Coming back therefore
  // showed an empty "Chat opened." as if the conversation never happened.
  //
  // How this solves it: ask chat-service for the room's recent messages once,
  // after the live subscription and join are already in place. Ordering matters:
  // anything that arrives live during the fetch is already in seenMessageIds, so
  // the merge simply skips it -- the same de-duplication a reconnect rejoin uses.
  // History is older than anything seen live, so it is inserted right after the
  // "Chat opened" line rather than appended.
  private loadHistory(chatId: string): void {
    this.chatApi
      .getMessages(chatId)
      .pipe(
        // Best-effort: the live chat works without history, so a failed fetch
        // must not break the screen.
        catchError(() => of<ChatMessage[]>([])),
        takeUntil(this.destroy$)
      )
      .subscribe(history => this.prependHistory(history));
  }

  private prependHistory(history: ChatMessage[]): void {
    const fresh = history.filter(message => !this.seenMessageIds.has(message.id));
    // Guard clause: nothing older than what is already on screen.
    if (fresh.length === 0) {
      return;
    }

    const items: ChatTimelineItem[] = [];
    let previousAtMs: number | null = null;
    for (const message of fresh) {
      this.seenMessageIds.add(message.id);
      const sentAtMs = Date.parse(message.sent_at);
      if (previousAtMs !== null && sentAtMs - previousAtMs > TIMESTAMP_GAP_MS) {
        items.push({ kind: 'timestamp', key: `ts-${message.id}`, at: message.sent_at });
      }
      items.push({ kind: 'message', key: message.id, message });
      previousAtMs = sentAtMs;
    }

    // Only seed the gap tracker if no live message got there first.
    if (this.lastMessageAtMs === null) {
      this.lastMessageAtMs = previousAtMs;
    }

    // index 0 is the "Chat opened" system line; history follows it.
    this.timeline = [this.timeline[0], ...items, ...this.timeline.slice(1)];
    this.stickToBottom = true;
    this.scrollToBottomSoon();
  }

  // Entry path mirrors ChatFlowService.handleChatClosed: a met-closed room
  // enters the STRICT 'met' path, so "Skip" is never offered for a rating the
  // user owes. Only a timeout-closed room gets the lenient 'timeout' path.
  private sendClosedRoomToRating(room: ChatRoomLookup): void {
    const entry: RatingEntryPath = room.closed_reason === 'met_successfully' ? 'met' : 'timeout';
    this.chatFlow.openRating(this.connectionId, entry);
  }

  private appendMessage(message: ChatMessage): void {
    // Guard clause: a rejoin / duplicate delivery must not render twice.
    if (this.seenMessageIds.has(message.id)) {
      return;
    }
    this.seenMessageIds.add(message.id);

    const sentAtMs = Date.parse(message.sent_at);
    const additions: ChatTimelineItem[] = [];
    if (this.lastMessageAtMs !== null && sentAtMs - this.lastMessageAtMs > TIMESTAMP_GAP_MS) {
      additions.push({ kind: 'timestamp', key: `ts-${message.id}`, at: message.sent_at });
    }
    additions.push({ kind: 'message', key: message.id, message });
    this.lastMessageAtMs = sentAtMs;
    this.timeline = [...this.timeline, ...additions];

    if (this.stickToBottom || this.isSentByMe(message)) {
      this.scrollToBottomSoon();
    }
  }

  // Reverse-scroll behaviour ("newest at the bottom"): the list grows
  // downward and the viewport follows. Deferred one tick so the new row is in
  // the DOM (and measured) before scrolling to the end.
  private scrollToBottomSoon(): void {
    setTimeout(() => {
      const element = this.scrollArea?.nativeElement;
      if (!element) {
        return;
      }
      element.scrollTop = element.scrollHeight;
    });
  }

  // "Chat closes in 30 minutes." (FIGMA_SPEC.md Screen 12). Display-only: it is
  // computed from the client's own "chat opened" moment and the configured
  // lifetime -- the SERVER alone decides when the chat really closes, so a
  // wrong clock can only make this line early or late, never break anything.
  private watchClosingCountdown(chatId: string): void {
    const openedAt = this.chatSignalr.openedAtFor(chatId);
    // Guard clause: no known open time (e.g. page reloaded) means no honest
    // countdown -- show nothing rather than invent one.
    if (openedAt === null || !Number.isFinite(environment.chatLifetimeMinutes)) {
      return;
    }
    timer(0, CLOSING_CHECK_INTERVAL_MS)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        if (this.closingWarningShown) {
          return;
        }
        const minutesLeft = environment.chatLifetimeMinutes - (Date.now() - openedAt) / 60_000;
        if (minutesLeft > CLOSING_WARNING_MINUTES || minutesLeft <= 0) {
          return;
        }
        this.closingWarningShown = true;
        this.timeline = [
          ...this.timeline,
          { kind: 'system', key: this.systemKey(), text: `Chat closes in ${Math.ceil(minutesLeft)} minutes.` }
        ];
        if (this.stickToBottom) {
          this.scrollToBottomSoon();
        }
      });
  }

  // The "You have N hours" line describes the lifetime from the moment the chat
  // opened. After a reload the lifetime is still known but WHEN it opened is
  // not, so stating hours left would be a guess presented as fact.
  private openedMessage(openedAt: number | null): string {
    const minutes = environment.chatLifetimeMinutes;
    if (openedAt === null || !Number.isFinite(minutes) || minutes <= 0) {
      return 'Chat opened.';
    }
    if (minutes % 60 === 0) {
      const hours = minutes / 60;
      return `Chat opened. You have ${hours} ${hours === 1 ? 'hour' : 'hours'}.`;
    }
    return `Chat opened. You have ${minutes} minutes.`;
  }

  private systemKey(): string {
    return `sys-${this.nextSystemKey++}`;
  }

  // Participants come from two services that each own their own slice of the
  // truth (Database per Service): connection-service knows WHO is in the
  // connection (ids), user-service knows what to CALL them. chat-service
  // exposes neither, so the client composes the answer.
  private loadParticipants(): Observable<ChatParticipants> {
    return forkJoin({
      me: this.userProfileService.getMyProfile(),
      connection: this.connectionService.getActiveConnection()
    }).pipe(
      switchMap(({ me, connection }) => {
        const otherUserId = this.otherParticipantId(me, connection);
        if (otherUserId === null) {
          return of(this.toParticipants(me, null));
        }
        return this.userProfileService.getPublicProfile(otherUserId).pipe(
          map(other => this.toParticipants(me, other)),
          // The chat is still usable without the other person's display name.
          catchError(() => of(this.toParticipants(me, null)))
        );
      })
    );
  }

  private otherParticipantId(me: UserProfileDto, connection: ConnectionResponse | null): number | null {
    // Guard clauses: no active connection, or it is a different connection
    // than the one this route names -- do not guess who the other person is.
    if (!connection || String(connection.id) !== this.connectionId) {
      return null;
    }
    return connection.requester_id === me.id ? connection.recipient_id : connection.requester_id;
  }

  private toParticipants(me: UserProfileDto, other: PublicUserProfileDto | null): ChatParticipants {
    return {
      myUserId: me.id,
      myName: me.name,
      otherName: other?.name ?? FALLBACK_OTHER_NAME,
      otherPhotoUrl: other?.photo_url ?? null
    };
  }

  // Authenticated photo fetch (REQUIREMENTS.md section 2) delegated to the one
  // shared helper -- a bare <img src> cannot carry the JWT. Best-effort: the
  // initials avatar is the fallback.
  private resolvePhoto(photoUrl: string | null): void {
    this.userProfileService
      .resolvePhotoUrl(photoUrl)
      .pipe(
        catchError(() => of(null)),
        takeUntil(this.destroy$)
      )
      .subscribe(resolved => {
        this.photoUrl = resolved;
        if (resolved?.startsWith('blob:')) {
          this.ownedBlobUrl = resolved;
        }
      });
  }

  private markUnavailable(message?: string): void {
    this.viewState = 'unavailable';
    if (message) {
      this.toastService.show(message, 'error');
    }
  }
}
