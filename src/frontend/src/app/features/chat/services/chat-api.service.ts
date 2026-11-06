import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { environment } from '../../../../environments/environment';
import { ErrorEnvelope } from '../../../auth/models/auth.models';
import { ChatRoomLookup, MarkMetResponse } from '../models/chat.models';

// Single Responsibility (S): the request/response half of chat only -- today
// exactly one endpoint. Real-time sending lives on the hub
// (ChatSignalrService); chat-service itself keeps the same split (ChatHub vs
// ChatController), and mirroring it here is why a component never injects
// HttpClient (angular-pwa skill).
//
// Deliberately NO "get message history" method, even though chat-service has a
// GET /chats/:id/messages: T068's no-history-by-design rule says the UI shows
// only what arrives live in this session. Chat messages are ephemeral
// (MongoDB TTL) -- a client that re-fetched and cached them would quietly
// build the very history the platform promises not to keep.
@Injectable({ providedIn: 'root' })
export class ChatApiService {
  constructor(private readonly http: HttpClient) {}

  // GET /chats/by-connection/:connectionId -- the "pull" twin of the one-time
  // ChatOpened push. A page reload (or a late hub connect) misses that push, so
  // this is how the client recovers the chat id. Returns the room's address and
  // its CURRENT status (so a room that closed while offline is detected), never
  // messages -- it is not a history endpoint.
  getChatByConnection(connectionId: string): Observable<ChatRoomLookup> {
    return this.http
      .get<ChatRoomLookup>(`${environment.apiBase}/chat/chats/by-connection/${encodeURIComponent(connectionId)}`)
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  // POST /chats/:id/met -- records THIS user's "Met Successfully". The chat
  // only starts closing once BOTH users have confirmed (both_users_met).
  markMetSuccessfully(chatId: string): Observable<MarkMetResponse> {
    return this.http
      .post<MarkMetResponse>(`${environment.apiBase}/chat/chats/${encodeURIComponent(chatId)}/met`, {})
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  // Same normalization AuthService/ConnectionService each keep as their own
  // private copy -- see UserProfileService's comment for the
  // duplication-vs-coupling reasoning this repeats.
  private toApplicationError(error: HttpErrorResponse): Observable<never> {
    const backendEnvelope = error.error as Partial<ErrorEnvelope> | null;
    const envelope: ErrorEnvelope = {
      code: backendEnvelope?.code ?? 'UNKNOWN_ERROR',
      // Not error.message: it can embed the raw request URL.
      message: backendEnvelope?.message ?? 'Something went wrong — please try again.',
      traceId: backendEnvelope?.traceId ?? ''
    };
    return throwError(() => envelope);
  }
}
