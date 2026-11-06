import { Injectable } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';

import { ConnectionService } from '../../../connections/connection.service';
import { ConnectionResponse } from '../../../connections/models/connection.models';
import { UserProfileService } from '../../../profile-setup/user-profile.service';
import { RatingEntryPath } from '../../chat/models/chat.models';
import { ChatApiService } from '../../chat/services/chat-api.service';
import { RatingCompanion } from '../models/rating.models';

// Graceful degradation (same instinct as ChatComponent): a rating can still be
// submitted if the companion's display name cannot be loaded -- only their id
// is required by POST /ratings.
const FALLBACK_COMPANION_NAME = 'your travel companion';

// Single Responsibility (S): "work out the context this screen needs" --
// WHO is being rated and HOW the user arrived -- is its own job, separate from
// RatingComponent (renders + collects tags) and RatingApiService (submits).
// Without the split, the component would grow three unrelated HTTP
// compositions and could not be read as "display + collect + submit".
//
// Database per Service, seen from the client: connection-service knows WHO is
// in a connection (ids), user-service knows what to CALL them, chat-service
// knows HOW the room ended. None exposes the others' data, so the client
// composes the answer -- exactly what ChatComponent.loadParticipants does for
// the chat header.
@Injectable({ providedIn: 'root' })
export class RatingContextService {
  constructor(
    private readonly connectionService: ConnectionService,
    private readonly userProfileService: UserProfileService,
    private readonly chatApi: ChatApiService
  ) {}

  // Emits the person to rate, or null when the route's connection cannot be
  // matched to the caller's active connection (stale link, or the connection
  // is gone). Errors only when the caller's OWN profile/connection lookup
  // fails -- the screen treats that as "unavailable" too.
  resolveCompanion(connectionId: string): Observable<RatingCompanion | null> {
    return forkJoin({
      me: this.userProfileService.getMyProfile(),
      connection: this.connectionService.getActiveConnection()
    }).pipe(
      switchMap(({ me, connection }) => {
        const otherUserId = this.otherParticipantId(me.id, connection, connectionId);
        // Guard clause: without the other person's id there is nothing to
        // submit a rating FOR, so do not guess.
        if (otherUserId === null) {
          return of(null);
        }
        return this.userProfileService.getPublicProfile(otherUserId).pipe(
          map(other => ({ userId: otherUserId, name: other.name, photoUrl: other.photo_url })),
          // Name/photo are cosmetic; the id alone is enough to rate.
          catchError(() => of({ userId: otherUserId, name: FALLBACK_COMPANION_NAME, photoUrl: null }))
        );
      })
    );
  }

  // Server-side answer to "how did this chat end?", used ONLY when navigation
  // state is missing (see RatingComponent's entry-path comment). Never errors:
  // every failure collapses to the strict path.
  //
  // Fail-closed on purpose: 'timeout' (which unlocks Skip) is returned ONLY
  // when the server positively says the room CLOSED for a reason other than
  // "met_successfully". Anything uncertain -- lookup failed, room not closed
  // yet, or closed_reason still null (legacy met-closed rooms written before
  // the reason column existed) -- stays 'met', where Skip is never offered. A
  // wrongly-strict screen costs the user one tap on a tag; a wrongly-lenient
  // one silently lets them dodge a rating they owe, which is the very signal
  // this screen exists to collect.
  resolveEntryFromServer(connectionId: string): Observable<RatingEntryPath> {
    return this.chatApi.getChatByConnection(connectionId).pipe(
      map(room => this.toEntryPath(room.status, room.closed_reason)),
      catchError(() => of<RatingEntryPath>('met'))
    );
  }

  private toEntryPath(status: string, closedReason: string | null): RatingEntryPath {
    if (status !== 'CLOSED' || closedReason === null || closedReason === 'met_successfully') {
      return 'met';
    }
    return 'timeout';
  }

  private otherParticipantId(
    myUserId: number,
    connection: ConnectionResponse | null,
    routeConnectionId: string
  ): number | null {
    // Guard clauses: no active connection, or a different one than the route
    // names -- do not guess who the other person is.
    if (!connection || String(connection.id) !== routeConnectionId) {
      return null;
    }
    return connection.requester_id === myUserId ? connection.recipient_id : connection.requester_id;
  }
}
