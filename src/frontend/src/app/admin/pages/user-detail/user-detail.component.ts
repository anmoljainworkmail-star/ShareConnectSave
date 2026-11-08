import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { EMPTY, Subject } from 'rxjs';
import { catchError, map, switchMap, takeUntil } from 'rxjs/operators';

import { ToastService } from '../../../shared/toast/toast.service';
import { ReportHistoryEntry, STATUS_ACTIVE, STATUS_SUSPENDED, UserDetail } from '../../models/admin.models';
import { AdminService } from '../../services/admin.service';
import { describeAdminError } from '../../utils/admin-error';
import { avatarToneOf, initialsOf, truncateName } from '../../utils/avatar';
import { relativeTime } from '../../utils/relative-time';

type ViewState = 'loading' | 'error' | 'ready';
type PendingAction = 'suspend' | 'reinstate';
type SheetKind = 'suspend' | 'override';
type SheetPhase = 'closed' | 'open' | 'closing';

interface ActionView {
  kind: PendingAction;
  busy: boolean;
}

// Everything the read-after-write check needs to decide, after the re-fetch,
// whether the override really landed (and what to roll back to if it cannot be
// checked at all).
interface OverrideCheck {
  requestedScore: number;
  rollbackTo: UserDetail;
}

// A load request. `override` is set only by confirmOverride's verification.
interface LoadRequest {
  silent: boolean;
  override?: OverrideCheck;
}

// Four fixed instances, handed out by the `action` getter below: Angular's
// dev-mode change check calls a template getter twice and compares results by
// reference, so a getter that built a fresh object each call would throw
// ExpressionChangedAfterItHasBeenChecked on every render.
const ACTION_VIEWS: Readonly<Record<PendingAction, { idle: ActionView; busy: ActionView }>> = {
  suspend: { idle: { kind: 'suspend', busy: false }, busy: { kind: 'suspend', busy: true } },
  reinstate: { idle: { kind: 'reinstate', busy: false }, busy: { kind: 'reinstate', busy: true } }
};

// FIGMA_SPEC.md section 6: bottom sheet closes in 250ms. Mirrors tokens.css
// --admin-sheet-close-duration (a timer, not animationend, so the sheet is also
// removed when prefers-reduced-motion disables the animation) -- keep the two in
// step, same arrangement as ToastService's FADE_OUT_MS.
const SHEET_CLOSE_MS = 250;

// Trust scores are on the 1.0-5.0 scale TrustScoreCalculator produces (see its
// comment in rating-service). Neither admin-service nor rating-service
// validates the override range today, so this is a UI-side guard against a
// typo, not the authority -- flagged in T071's report.
const TRUST_SCORE_MIN = 1;
const TRUST_SCORE_MAX = 5;
// Scores are decimals; two values closer than this are the same score, so a
// float round-trip cannot make a correct write look like a failed one.
const TRUST_SCORE_TOLERANCE = 0.01;

const QUEUE_ROUTE = ['/admin'];

// FIGMA_SPEC.md Screen 21 -- Admin: User Detail. Profile summary, trust score
// (with override), report timeline, and a bottom action bar (Suspend red /
// Reinstate green depending on current status).
//
// Facade (consumer side): one call, AdminService.getUserDetail, returns the
// whole aggregated view; this component never fetches profile, score and reports
// separately and never talks to User / Rating / Report services.
//
// Optimistic update with rollback (ticket pattern "Suspend and reinstate actions
// update UI immediately"). Plain English: when you tap a light switch you expect
// the light to change NOW, not after the electrician confirms the wiring. So the
// screen flips to the new state the instant the admin confirms, sends the request,
// and if the server refuses, puts the old state back and says why. The cost of
// being wrong is a brief flash of a state that never became true, which is why the
// SHARED queue snapshot is only patched after the server confirms (AdminService)
// -- the optimism stays inside this one screen.
//
// After a FAILED write we also re-fetch: suspend is a multi-step server
// orchestration (user status, then connections, then trust-score event) and a 503
// from a later step can leave an earlier step applied. Rolling back local state
// alone could then show "active" for a user the server already suspended.
@Component({
  selector: 'app-admin-user-detail',
  templateUrl: './user-detail.component.html',
  styleUrl: './user-detail.component.css',
  // Project rule: module-based only (Angular 19 defaults to standalone).
  standalone: false
})
export class UserDetailComponent implements OnInit, OnDestroy {
  readonly scoreMin = TRUST_SCORE_MIN;
  readonly scoreMax = TRUST_SCORE_MAX;

  viewState: ViewState = 'loading';
  loadError: string | null = null;

  detail: UserDetail | null = null;
  // Newest first; derived once per load instead of sorted on every change detection.
  reports: ReportHistoryEntry[] = [];

  pendingAction: PendingAction | null = null;
  pendingOverride = false;

  sheetKind: SheetKind = 'suspend';
  sheetPhase: SheetPhase = 'closed';
  suspendReason = '';
  overrideScore: number | null = null;
  overrideReason = '';

  nowMs = Date.now();

  @ViewChild('sheetField') private sheetField?: ElementRef<HTMLElement>;

  private userId = '';
  private destroyed = false;
  private sheetCloseTimer: ReturnType<typeof setTimeout> | null = null;

  // Latest-wins load stream (switchMap): a silent reconcile fired after a failed
  // write cancels any older in-flight load, so a slow earlier response can never
  // land after, and overwrite, a newer one.
  private readonly load$ = new Subject<LoadRequest>();
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly adminService: AdminService,
    private readonly toastService: ToastService
  ) {}

  ngOnInit(): void {
    this.load$
      .pipe(
        switchMap(request =>
          this.adminService.getUserDetail(this.userId).pipe(
            map(detail => ({ detail, request })),
            catchError(error => {
              this.onLoadFailed(error, request);
              return EMPTY;
            })
          )
        ),
        takeUntil(this.destroy$)
      )
      .subscribe(({ detail, request }) => {
        this.adopt(detail);
        // Guard clause: an ordinary load has no override to verify.
        if (request.override) {
          this.checkOverride(detail, request.override.requestedScore);
        }
      });

    this.route.paramMap.pipe(takeUntil(this.destroy$)).subscribe(params => {
      const userId = params.get('userId');
      // Guard clause: a route without an id cannot be loaded.
      if (!userId) {
        this.loadError = 'No user was specified.';
        this.viewState = 'error';
        return;
      }
      this.userId = userId;
      this.load$.next({ silent: false });
    });
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.sheetCloseTimer !== null) {
      clearTimeout(this.sheetCloseTimer);
    }
    this.destroy$.next();
    this.destroy$.complete();
  }

  retry(): void {
    this.viewState = 'loading';
    this.loadError = null;
    this.load$.next({ silent: false });
  }

  // ---- Derived view data -----------------------------------------------------

  get title(): string {
    const user = this.detail;
    if (!user) {
      return 'User detail';
    }
    return user.displayName ?? `User ${truncateName(user.userId)}`;
  }

  get initials(): string {
    return initialsOf(this.detail?.displayName ?? null);
  }

  get tone(): number {
    return avatarToneOf(this.detail?.displayName ?? this.detail?.userId ?? '');
  }

  // Status arrives normalised to lowercase (AdminService boundary), so the label
  // is capitalised here for display only; comparisons never use the label.
  get statusLabel(): string {
    const status = this.detail?.status;
    // Guard clause: unknown status has no label to capitalise.
    if (!status) {
      return 'Unknown';
    }
    return status.charAt(0).toUpperCase() + status.slice(1);
  }

  get statusClass(): string {
    const status = this.detail?.status;
    if (status === STATUS_SUSPENDED) {
      return 'chip--suspended';
    }
    if (status === STATUS_ACTIVE) {
      return 'chip--active';
    }
    return 'chip--neutral';
  }

  // Which action the bottom bar offers. null = none (profile/status unknown):
  // with the status unavailable we cannot tell Suspend from Reinstate, and
  // guessing would mean offering a destructive action on a stale premise.
  get action(): ActionView | null {
    const user = this.detail;
    if (!user) {
      return null;
    }
    // While a write is in flight the bar keeps showing the ORIGINAL action, busy,
    // even though the optimistic status has already flipped.
    if (this.pendingAction) {
      return ACTION_VIEWS[this.pendingAction].busy;
    }
    if (user.profileUnavailable || user.status === null) {
      return null;
    }
    return ACTION_VIEWS[user.status === STATUS_SUSPENDED ? 'reinstate' : 'suspend'].idle;
  }

  get overrideValid(): boolean {
    const score = this.overrideScore;
    if (score === null || !Number.isFinite(score)) {
      return false;
    }
    return score >= TRUST_SCORE_MIN && score <= TRUST_SCORE_MAX && this.overrideReason.trim().length > 0;
  }

  get suspendValid(): boolean {
    return this.suspendReason.trim().length > 0;
  }

  reportedAgo(entry: ReportHistoryEntry): string | null {
    return relativeTime(entry.createdAt, this.nowMs);
  }

  trackByReportId(_index: number, entry: ReportHistoryEntry): string {
    return entry.id;
  }

  goBack(): void {
    void this.router.navigate(QUEUE_ROUTE);
  }

  // ---- Actions -----------------------------------------------------------------

  reinstate(): void {
    const current = this.detail;
    // Guard clauses: nothing to act on, a write already in flight, or not allowed.
    if (!current || this.pendingAction || this.action?.kind !== 'reinstate') {
      return;
    }
    this.pendingAction = 'reinstate';
    this.detail = { ...current, status: STATUS_ACTIVE };

    this.adminService.reinstateUser(current.userId).subscribe({
      next: () => this.onActionSucceeded(`${this.nameOf(current)} reinstated.`),
      error: error => this.onActionFailed(current, error)
    });
  }

  confirmSuspend(): void {
    const current = this.detail;
    const reason = this.suspendReason.trim();
    if (!current || this.pendingAction || !reason) {
      return;
    }
    this.closeSheet();
    this.pendingAction = 'suspend';
    this.detail = { ...current, status: STATUS_SUSPENDED };

    this.adminService.suspendUser(current, reason).subscribe({
      next: () => this.onActionSucceeded(`${this.nameOf(current)} suspended.`),
      error: error => this.onActionFailed(current, error)
    });
  }

  confirmOverride(): void {
    const current = this.detail;
    const score = this.overrideScore;
    if (!current || this.pendingOverride || score === null || !this.overrideValid) {
      return;
    }
    const reason = this.overrideReason.trim();
    this.closeSheet();
    this.pendingOverride = true;
    this.detail = { ...current, trustScore: score, trustScoreUnavailable: false };

    this.adminService.overrideTrustScore(current.userId, score, reason).subscribe({
      next: () => {
        this.pendingOverride = false;
        // Guard clause: the admin left the screen, so there is nothing to re-check on.
        if (this.destroyed) {
          return;
        }
        // Read-after-write verification (pattern: verify the write by reading it
        // back). The PATCH response is NOT trusted: admin-service's Rating client is
        // a stub that echoes the request and stores nothing, so the echo proves
        // nothing. Only a fresh GET showing the new score counts as success; the
        // check runs through the load stream, so a stale response cannot overwrite it.
        this.load$.next({
          silent: true,
          override: { requestedScore: score, rollbackTo: current }
        });
      },
      error: error => {
        this.pendingOverride = false;
        this.onWriteFailed(current, error);
      }
    });
  }

  // ---- Bottom sheet (spec 3.5): slide up 300ms ease-out, down 250ms ease-in ----

  openSheet(kind: SheetKind): void {
    const user = this.detail;
    if (!user || this.pendingAction || this.pendingOverride || this.sheetPhase !== 'closed') {
      return;
    }
    this.suspendReason = '';
    this.overrideReason = '';
    this.overrideScore = user.trustScore;
    this.sheetKind = kind;
    this.sheetPhase = 'open';
    // The field exists only after the next change-detection pass renders the sheet.
    setTimeout(() => this.sheetField?.nativeElement.focus());
  }

  @HostListener('document:keydown.escape')
  closeSheet(): void {
    if (this.sheetPhase !== 'open') {
      return;
    }
    this.sheetPhase = 'closing';
    this.sheetCloseTimer = setTimeout(() => {
      this.sheetPhase = 'closed';
      this.sheetCloseTimer = null;
    }, SHEET_CLOSE_MS);
  }

  // ---- Internals ---------------------------------------------------------------

  private adopt(detail: UserDetail): void {
    this.detail = detail;
    // A nulled-out list from a broken upstream becomes "no rows", and the
    // reportHistoryUnavailable flag (already rendered as a notice) still says why.
    this.reports = [...(detail.reportHistory ?? [])].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
    this.nowMs = Date.now();
    this.loadError = null;
    this.viewState = 'ready';
  }

  // Read-after-write check result. A match is the only success; a mismatch means
  // the write was accepted but not stored, so the warning says so and the
  // re-fetched value (already adopted) stays on screen.
  private checkOverride(fetched: UserDetail, requestedScore: number): void {
    const stored = fetched.trustScore;
    // Guard clause: a missing or different score is not confirmed. Shown as an
    // error-kind toast because ToastService has no warning kind.
    if (stored === null || Math.abs(stored - requestedScore) >= TRUST_SCORE_TOLERANCE) {
      this.toastService.show(
        'The override was accepted but not stored: Rating Service does not persist trust-score changes yet.',
        'error'
      );
      return;
    }
    // Only a verified score is pushed into the shared queue snapshot.
    this.adminService.patchQueueTrustScore(fetched.userId, stored);
    this.toastService.show('Trust score updated.', 'success');
  }

  private onLoadFailed(error: unknown, request: LoadRequest): void {
    const message = describeAdminError(error);
    // Rollback: if the override cannot be verified we cannot prove the server
    // stored it, so the optimistic score is dropped in favour of the last value
    // the admin knew to be true.
    if (request.override) {
      this.detail = request.override.rollbackTo;
    }
    // Guard clause: a failed SILENT reconcile keeps the page that is already on
    // screen (rolled back) and only tells the admin it could not be re-checked.
    if (request.silent && this.detail) {
      this.toastService.show(`Could not re-check this user's state. ${message}`, 'error');
      return;
    }
    this.loadError = message;
    this.viewState = 'error';
  }

  private onActionSucceeded(message: string): void {
    this.pendingAction = null;
    this.toastService.show(message, 'success');
    // Guard clause: the admin already left this screen, so do not yank them back
    // to the queue (AdminService has already patched the shared snapshot).
    if (this.destroyed) {
      return;
    }
    // FIGMA_SPEC.md admin flow: "status updated, back to queue".
    this.goBack();
  }

  private onActionFailed(previous: UserDetail, error: unknown): void {
    this.pendingAction = null;
    this.onWriteFailed(previous, error);
  }

  // Rollback + surface the server's message + reconcile with the server's truth.
  private onWriteFailed(previous: UserDetail, error: unknown): void {
    this.toastService.show(describeAdminError(error), 'error');
    if (this.destroyed) {
      return;
    }
    this.detail = previous;
    this.load$.next({ silent: true });
  }

  private nameOf(user: UserDetail): string {
    return user.displayName ?? `User ${truncateName(user.userId)}`;
  }
}
