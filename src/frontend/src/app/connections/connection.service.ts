import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { environment } from '../../environments/environment';
import { ErrorEnvelope } from '../auth/models/auth.models';
import { ConnectionCreatedResponse, ConnectionResponse, CreateConnectionRequest } from './models/connection.models';

// Single Responsibility (S): the request/response half of the connection
// flow only -- the four /connections endpoints. It knows nothing about
// SignalR (NotificationService owns the push half) and nothing about
// navigation or toasts (components/ConnectionFlowService own those). Same
// split RadarDiscoveryService / UserProfileService already establish, and the
// reason a component never injects HttpClient itself (angular-pwa skill).
//
// Request/response vs push, kept deliberately separate: sending, accepting
// and declining are COMMANDS the user triggers (so plain HTTP with a clear
// success/failure result), while "someone asked you" / "they accepted" are
// EVENTS the server pushes. Mixing the two into one service is how you end
// up polling for state the server could simply tell you about.
@Injectable({ providedIn: 'root' })
export class ConnectionService {
  constructor(private readonly http: HttpClient) {}

  sendRequest(recipientId: number): Observable<ConnectionCreatedResponse> {
    const body: CreateConnectionRequest = { recipient_id: recipientId };
    return this.http
      .post<ConnectionCreatedResponse>(`${environment.apiBase}/connection/connections`, body)
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  acceptRequest(connectionId: number): Observable<ConnectionResponse> {
    return this.http
      .post<ConnectionResponse>(`${environment.apiBase}/connection/connections/${connectionId}/accept`, {})
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  declineRequest(connectionId: number): Observable<ConnectionResponse> {
    return this.http
      .post<ConnectionResponse>(`${environment.apiBase}/connection/connections/${connectionId}/decline`, {})
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  // The source of truth for "what is waiting for me" -- used on screen open
  // and again after a SignalR reconnect, because a push sent while the socket
  // was down is gone for good (SignalR does not replay missed messages).
  getPendingInbound(): Observable<ConnectionResponse[]> {
    return this.http
      .get<ConnectionResponse[]>(`${environment.apiBase}/connection/connections/pending`)
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  // T068: the chat header needs both participants' ids, and chat-service does
  // not expose them -- connection-service does (requester_id / recipient_id).
  // The server answers 204 when there is no active connection, which
  // HttpClient surfaces as a null body; callers must handle null.
  getActiveConnection(): Observable<ConnectionResponse | null> {
    return this.http
      .get<ConnectionResponse | null>(`${environment.apiBase}/connection/connections/active`)
      .pipe(catchError(error => this.toApplicationError(error)));
  }

  // Same normalization AuthService/UserProfileService/RadarDiscoveryService
  // each keep as their own private copy -- see UserProfileService's comment
  // for the duplication-vs-coupling reasoning this repeats.
  private toApplicationError(error: HttpErrorResponse): Observable<never> {
    const backendEnvelope = error.error as Partial<ErrorEnvelope> | null;
    const envelope: ErrorEnvelope = {
      code: backendEnvelope?.code ?? 'UNKNOWN_ERROR',
      // Not error.message: it can embed the raw request URL (see
      // RadarDiscoveryService.toApplicationError).
      message: backendEnvelope?.message ?? 'Something went wrong — please try again.',
      traceId: backendEnvelope?.traceId ?? ''
    };
    return throwError(() => envelope);
  }
}
