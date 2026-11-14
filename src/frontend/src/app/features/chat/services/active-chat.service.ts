import { Injectable } from '@angular/core';
import { BehaviorSubject, EMPTY, Observable, interval, merge, of } from 'rxjs';
import { catchError, distinctUntilChanged, startWith, switchMap } from 'rxjs/operators';

import { AuthService } from '../../../auth/auth.service';
import { ConnectionService } from '../../../connections/connection.service';
import { ConnectionResponse } from '../../../connections/models/connection.models';
import { ChatSignalrService } from './chat-signalr.service';

// What the "Return to chat" pill needs to render: which chat to go back to and
// how many messages arrived while the user was elsewhere.
export interface ActiveChat {
  connectionId: string;
  unreadCount: number;
}

// Fallback only: the pushes (ChatOpened / ChatClosed) are the fast path. This
// just heals a push SignalR never replays, exactly like the pending-requests
// bell does.
const SAFETY_REFRESH_MS = 30_000;

// The Problem: leaving the chat screen (back arrow to the radar) destroys
// ChatComponent, and with it the only thing that knew "I am in the middle of a
// conversation". The chat is still open on the server for up to its lifetime,
// but the radar has no way to say so -- the user would have to remember it
// exists and type the URL.
//
// Why it matters: a chat that is only reachable by memory is effectively lost.
// The other person keeps waiting while this user scans for someone new.
//
// How this solves it: a root service (it must outlive every screen, like the
// hub connection) that answers one question -- "is there a live chat this user
// can return to?" -- and exposes it as a stream any screen can render. The
// ANSWER always comes from the server (GET /connections/active, the source of
// truth for the platform's one-active-connection rule); pushes and a slow timer
// only decide WHEN to ask again. Same discipline as PendingRequestsBell: a
// locally kept flag drifts the moment one push is lost.
//
// Single Responsibility (S): this class tracks resumability and unread count.
// It does not navigate, show popups, or own a socket -- ChatFlowService decides
// what the APP does, the pill component only renders.
@Injectable({ providedIn: 'root' })
export class ActiveChatService {
  private started = false;

  // Connections THIS user already confirmed "Met Successfully" on, this
  // session. Once I have met the person I have no reason to resume the chat
  // (and no reason to be pinged by it), even though the server keeps the room
  // open until the OTHER person confirms too. In-memory on purpose: it only
  // hides UI, the server stays the authority on whether the chat is open.
  private readonly metByMe = new Set<string>();

  private readonly stateSubject = new BehaviorSubject<ActiveChat | null>(null);

  // BehaviorSubject (replays the current value), unlike the Subjects in the
  // hub services: this is STATE ("is a chat resumable right now"), not an
  // event, so a pill that mounts after the fact must render correctly at once.
  readonly activeChat$: Observable<ActiveChat | null> = this.stateSubject.asObservable();

  constructor(
    private readonly authService: AuthService,
    private readonly connectionService: ConnectionService,
    private readonly chatSignalr: ChatSignalrService
  ) {}

  // Idempotent -- ChatFlowService.start() calls it once, but a second call
  // must not double-subscribe (every refresh would then fire twice).
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;

    this.authService.isAuthenticated$
      .pipe(
        distinctUntilChanged(),
        switchMap(isAuthenticated => {
          // Guard clause: logged out means nothing to resume, and asking the
          // server without a token would just 401. Also clears the previous
          // user's state on a shared device.
          if (!isAuthenticated) {
            this.reset();
            return EMPTY;
          }
          return merge(this.chatSignalr.chatOpened$, this.chatSignalr.established$, interval(SAFETY_REFRESH_MS)).pipe(
            startWith(undefined),
            // switchMap cancels an in-flight lookup when a newer trigger
            // arrives, so a slow older response can never overwrite a fresher one.
            switchMap(() =>
              this.connectionService.getActiveConnection().pipe(
                // undefined = "lookup failed, keep what we had" -- distinct
                // from null ("server says there is no active connection").
                catchError(() => of(undefined))
              )
            )
          );
        })
      )
      .subscribe(connection => {
        if (connection === undefined) {
          return;
        }
        this.applyServerTruth(connection);
      });

    // The platform allows ONE active connection at a time, so a closed chat
    // means nothing is resumable -- no need to work out which one closed.
    this.chatSignalr.chatClosed$.subscribe(() => this.stateSubject.next(null));
  }

  hasConfirmedMet(connectionId: string): boolean {
    return this.metByMe.has(connectionId);
  }

  // Called when a message arrives for a chat the user is NOT looking at.
  recordUnread(connectionId: string): void {
    const current = this.stateSubject.value;
    // If the first server lookup has not landed yet we still know a chat
    // exists (a message just arrived for it), so seed the state rather than
    // dropping the signal. The next refresh keeps the count.
    const base: ActiveChat = current?.connectionId === connectionId ? current : { connectionId, unreadCount: 0 };
    this.stateSubject.next({ ...base, unreadCount: base.unreadCount + 1 });
  }

  // Called when the chat screen opens: everything is now read.
  markViewed(connectionId: string): void {
    const current = this.stateSubject.value;
    // Guard clause: nothing to reset for a different (or no) chat.
    if (!current || current.connectionId !== connectionId || current.unreadCount === 0) {
      return;
    }
    this.stateSubject.next({ ...current, unreadCount: 0 });
  }

  markMetConfirmed(connectionId: string): void {
    this.metByMe.add(connectionId);
    if (this.stateSubject.value?.connectionId === connectionId) {
      this.stateSubject.next(null);
    }
  }

  private applyServerTruth(connection: ConnectionResponse | null): void {
    if (!connection) {
      this.stateSubject.next(null);
      return;
    }
    const connectionId = String(connection.id);
    if (this.metByMe.has(connectionId)) {
      this.stateSubject.next(null);
      return;
    }
    const current = this.stateSubject.value;
    // Same chat as before: keep its unread count instead of resetting it on
    // every 30-second refresh.
    if (current?.connectionId === connectionId) {
      return;
    }
    this.stateSubject.next({ connectionId, unreadCount: 0 });
  }

  private reset(): void {
    this.metByMe.clear();
    this.stateSubject.next(null);
  }
}
