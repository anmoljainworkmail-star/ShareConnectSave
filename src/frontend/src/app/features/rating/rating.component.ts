import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject, forkJoin, of } from 'rxjs';
import { catchError, takeUntil } from 'rxjs/operators';

import { ErrorEnvelope } from '../../auth/models/auth.models';
import { UserProfileService } from '../../profile-setup/user-profile.service';
import { ToastService } from '../../shared/toast/toast.service';
import { RatingEntryPath } from '../chat/models/chat.models';
import { ChatFlowService } from '../chat/services/chat-flow.service';
import { RATING_TAG_GROUPS, RatingTagGroup, isNegativeTag } from './models/rating-tags';
import { RATING_ERROR_CODES, RatingCompanion } from './models/rating.models';
import { RatingApiService } from './services/rating-api.service';
import { RatingContextService } from './services/rating-context.service';

// 'loading' = resolving who to rate and how the user arrived; 'unavailable' =
// the route does not map to a connection this user can rate.
type RatingViewState = 'loading' | 'ready' | 'unavailable';

// Where both exits (submit / skip) land: the radar is the app's protected home
// (app-routing.module.ts redirects '' to it).
const HOME_ROUTE = '/radar';

const FALLBACK_NAME = 'your travel companion';

// FIGMA_SPEC.md Screen 14 -- Rating. The page is plain --color-background plus
// the global ripple texture; Screen 14 has no approved pixel mockup, so the
// written spec is the contract.
//
// Single Responsibility (S), per the ticket: this component only DISPLAYS the
// person being rated and COLLECTS + SUBMITS tags. It does not calculate trust
// scores -- the client has no idea what a score even is; rating-service's
// TrustScoreCalculator owns that, and a client that tried to "predict" the
// score would drift from the server's rules the first time they changed.
// Resolving context (who/how) lives in RatingContextService, the HTTP call in
// RatingApiService.
@Component({
  selector: 'app-rating',
  templateUrl: './rating.component.html',
  styleUrl: './rating.component.css',
  // Module-based project (no standalone components) -- see UserCardComponent.
  standalone: false
})
export class RatingComponent implements OnInit, OnDestroy {
  viewState: RatingViewState = 'loading';
  companion: RatingCompanion | null = null;
  photoUrl: string | null = null;
  entry: RatingEntryPath = 'met';

  // The tag grid is rendered from this config (Open/Closed) -- see
  // models/rating-tags.ts.
  readonly tagGroups: readonly RatingTagGroup[] = RATING_TAG_GROUPS;

  // Multi-select state is a plain array bound to the grid. Replaced (never
  // mutated) on every toggle so the template's bindings always see a new
  // reference.
  selectedTags: string[] = [];
  isSubmitting = false;
  // Inline, retryable failure (e.g. CHAT_NOT_CLOSED) -- a toast would vanish
  // in 3s, but this message stays until the user acts.
  submitError: string | null = null;

  private connectionId = '';
  // Navigation state is only readable DURING the navigation that created this
  // component, so it is captured in the constructor (see readNavigationEntry).
  private readonly navigationEntry: RatingEntryPath | null;
  private ownedBlobUrl: string | null = null;
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly ratingApi: RatingApiService,
    private readonly ratingContext: RatingContextService,
    private readonly userProfileService: UserProfileService,
    private readonly toastService: ToastService,
    private readonly chatFlow: ChatFlowService
  ) {
    this.navigationEntry = this.readNavigationEntry();
  }

  get companionName(): string {
    return this.companion?.name ?? FALLBACK_NAME;
  }

  // "Skip" exists ONLY on the timeout path (Screen 14): after a Met
  // Successfully the user owes a rating, because that signal is what keeps the
  // discovery pool trustworthy. The template hides the button with this getter
  // AND skip() re-checks it, so the rule does not live in the markup alone.
  get canSkip(): boolean {
    return this.entry === 'timeout';
  }

  // Submit state derives straight from "is the selection empty" -- no separate
  // flag that could drift out of sync with the array.
  //
  // Deliberate deviation from FIGMA_SPEC.md line 645 ("enabled always on
  // timeout path"): rating-service declares `tags` @NotEmpty, so an empty
  // submit would only come back as a 400. Disabling it client-side gives the
  // same outcome with no wasted round trip, and the timeout path's
  // no-rating-given alternative is the Skip button instead.
  get canSubmit(): boolean {
    return !this.isSubmitting && this.selectedTags.length > 0;
  }

  // Spec: "Selecting a negative tag shows a subtle warning".
  get showNegativeWarning(): boolean {
    return this.selectedTags.some(tag => isNegativeTag(tag));
  }

  ngOnInit(): void {
    const connectionId = this.route.snapshot.paramMap.get('id');
    // Guard clause: a route without an id cannot name a connection.
    if (!connectionId) {
      this.viewState = 'unavailable';
      return;
    }
    this.connectionId = connectionId;

    // Two independent lookups, run in parallel: forkJoin emits once both are
    // done, so the screen renders once with everything it needs.
    forkJoin({
      companion: this.ratingContext.resolveCompanion(connectionId),
      entry: this.navigationEntry
        ? of(this.navigationEntry)
        : this.ratingContext.resolveEntryFromServer(connectionId)
    })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: ({ companion, entry }) => {
          // Guard clause: no resolvable person means no rated_id to send.
          if (!companion) {
            this.viewState = 'unavailable';
            return;
          }
          this.companion = companion;
          this.entry = entry;
          this.resolvePhoto(companion.photoUrl);
          this.viewState = 'ready';
        },
        error: () => {
          this.viewState = 'unavailable';
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    // Same cleanup rule UserCardComponent/ChatComponent apply: only a blob: URL
    // THIS component minted is ours to revoke.
    if (this.ownedBlobUrl) {
      URL.revokeObjectURL(this.ownedBlobUrl);
    }
  }

  isSelected(tag: string): boolean {
    return this.selectedTags.includes(tag);
  }

  toggleTag(tag: string): void {
    // Guard clause: the grid is frozen while the request is in flight, so the
    // payload on the wire always matches what the user last saw selected.
    if (this.isSubmitting) {
      return;
    }
    this.submitError = null;
    this.selectedTags = this.isSelected(tag)
      ? this.selectedTags.filter(selected => selected !== tag)
      : [...this.selectedTags, tag];
  }

  submit(): void {
    // Guard clause: one check covers "no tags" and "already in flight" --
    // together they are the double-submit protection (the button is also
    // disabled, but a keyboard Enter or a fast double tap must not slip past).
    if (!this.companion || !this.canSubmit) {
      return;
    }
    this.isSubmitting = true;
    this.submitError = null;

    this.ratingApi
      .submitRating({
        connection_id: Number(this.connectionId),
        rated_id: this.companion.userId,
        tags: this.selectedTags
      })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.toastService.show('Thanks for rating your companion.', 'success');
          this.finishRating();
        },
        error: (error: ErrorEnvelope) => this.handleSubmitError(error)
      });
  }

  skip(): void {
    // Guard clause: Skip is timeout-only even if something calls it directly.
    if (!this.canSkip || this.isSubmitting) {
      return;
    }
    this.finishRating();
  }

  initials(name: string): string {
    const letters = name
      .split(/\s+/)
      .filter(part => part.length > 0)
      .slice(0, 2)
      .map(part => part.charAt(0).toUpperCase());
    return letters.join('') || '?';
  }

  goHome(): void {
    // replaceUrl: the rating screen must not stay in history -- Back from the
    // radar would otherwise return to a screen that can only answer "already
    // rated".
    this.router.navigate([HOME_ROUTE], { replaceUrl: true });
  }

  // The three "this user is done rating" exits (submit, skip, already rated)
  // share one path so each tells ChatFlowService, which would otherwise re-open
  // this screen when the other participant's ChatClosed push arrives later.
  // goHome() alone is NOT enough: the "Back to radar" button in the unavailable
  // state also calls it, and that connection was never actually rated.
  private finishRating(): void {
    this.chatFlow.markRatingResolved(this.connectionId);
    this.goHome();
  }

  private handleSubmitError(error: ErrorEnvelope): void {
    this.isSubmitting = false;

    // Idempotent outcome: a 409 means the rating this user wanted to record
    // IS recorded (double tap, retry after a lost response, or the second
    // ChatClosed push re-opening this screen). Treat it as success-ish and move
    // on rather than stranding them on a screen that can never succeed.
    if (error.code === RATING_ERROR_CODES.alreadySubmitted) {
      this.toastService.show('You have already rated this companion.');
      this.finishRating();
      return;
    }

    // Known open question (logged for the tech lead): the first person to
    // confirm Met Successfully reaches this screen BEFORE the chat is CLOSED
    // (it only closes once BOTH confirm), while rating-service validates
    // against the closed chat. The answer is a temporary 400 CHAT_NOT_CLOSED,
    // so say what is happening and leave Submit enabled for a retry instead of
    // treating it as a hard failure.
    if (error.code === RATING_ERROR_CODES.chatNotClosed) {
      this.submitError =
        'Your chat has not finished closing yet. Please wait a moment and tap Submit Rating again.';
      return;
    }

    this.submitError = error.message;
    this.toastService.show(error.message, 'error');
  }

  // Router state is the mechanism for the entry path (ChatFlowService passes
  // `{ state: { entry } }`), not a query param, so the URL cannot be edited to
  // unlock Skip.
  //
  // Angular copies a history entry's state back into the navigation on reload
  // and on Back/Forward, so a reload on /rating/:id usually KEEPS the entry.
  // It is only genuinely absent when the URL was opened directly (typed,
  // bookmarked, new tab). Then the constructor returns null and ngOnInit asks
  // the server how the chat ended (RatingContextService.resolveEntryFromServer),
  // defaulting to the STRICT path -- see that method for the lenient-vs-strict
  // reasoning.
  private readNavigationEntry(): RatingEntryPath | null {
    const navigation = this.router.getCurrentNavigation();
    // Only fall back to history.state when no navigation is in progress; during
    // one, history.state still describes the PREVIOUS entry and could leak a
    // stale entry path from another connection.
    const state = navigation ? navigation.extras.state : (history.state as Record<string, unknown> | null);
    const entry = state?.['entry'];
    return entry === 'met' || entry === 'timeout' ? entry : null;
  }

  // Authenticated photo fetch (REQUIREMENTS.md section 2), DRY: the ONE shared
  // helper UserProfileService.resolvePhotoUrl -- the same one UserCardComponent
  // and ChatComponent call -- not a second implementation. A bare
  // <img src="{{photo_url}}"> cannot carry the JWT, so the photo is fetched
  // through HttpClient (AuthInterceptor adds the bearer token) and bound as a
  // blob: URL. Best-effort: the initials avatar is the fallback.
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
}
