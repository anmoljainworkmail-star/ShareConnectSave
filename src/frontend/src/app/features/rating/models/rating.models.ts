// Wire DTOs for rating-service, reached through the gateway at
// `${environment.apiBase}/rating/ratings` (api-gateway's "rating-route" strips
// the "/rating" prefix, same shape as the chat/connection routes). Field names
// are copied from RatingSubmissionDto.java / RatingResponse.java (read, not
// guessed) -- the same discipline connection.models.ts and chat.models.ts use.

// Mirrors RatingSubmissionDto.java: POST /ratings request body.
//  - connection_id / rated_id are numeric ids (Long server-side).
//  - tags are the Title Case wire strings from rating-tags.ts (min 1).
//  - comment is optional (max 500) and not collected by Screen 14, so it is
//    never sent.
export interface RatingSubmitRequest {
  connection_id: number;
  rated_id: number;
  tags: string[];
}

// Mirrors RatingResponse.java (201 body). The screen only needs to know the
// call succeeded, but the type documents what comes back.
export interface RatingResponse {
  id: number;
  connection_id: number;
  rater_id: number;
  rated_id: number;
  tags: string[];
  comment: string | null;
  created_at: string;
}

// The person being rated, resolved from the connection + user-service. The
// id is what POST /ratings needs (rated_id); name/photo are what the screen
// shows.
export interface RatingCompanion {
  userId: number;
  name: string;
  photoUrl: string | null;
}

// Error codes rating-service answers with (RatingExceptionHandler), named once
// so the component does not compare against scattered string literals.
export const RATING_ERROR_CODES = {
  alreadySubmitted: 'RATING_ALREADY_SUBMITTED',
  chatNotClosed: 'CHAT_NOT_CLOSED',
  invalidTag: 'INVALID_RATING_TAG'
} as const;
