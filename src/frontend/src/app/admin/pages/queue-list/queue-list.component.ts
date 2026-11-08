import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import { ReviewQueueItem } from '../../models/admin.models';
import { AdminService } from '../../services/admin.service';
import { describeAdminError } from '../../utils/admin-error';
import { avatarToneOf, initialsOf, truncateName } from '../../utils/avatar';
import { relativeTime } from '../../utils/relative-time';

// 'loading' = first load with nothing cached; 'error' = first load failed with
// nothing to show; 'ready' = we have a queue (fresh, or a cached one that is
// being revalidated).
type ViewState = 'loading' | 'error' | 'ready';

// FIGMA_SPEC.md Screen 20 -- Admin: Review Queue. The rows come from
// admin-service's GET /admin/queue (suspended accounts, enriched per row with
// trust score and report count). This component only renders and navigates; the
// HTTP call and the shared queue snapshot live in AdminService.
//
// Stale-while-revalidate: if AdminService already holds a snapshot (the admin
// just came back from a suspend/reinstate), the page renders it at once and
// refetches in the background, instead of flashing a skeleton. The async pipe in
// the template reads queue$, so the background refresh simply swaps the rows in.
@Component({
  selector: 'app-admin-queue-list',
  templateUrl: './queue-list.component.html',
  styleUrl: './queue-list.component.css',
  // Project rule: module-based only (Angular 19 defaults to standalone).
  standalone: false
})
export class QueueListComponent implements OnInit, OnDestroy {
  // Assigned in the constructor (not a field initializer): with ES2022 class
  // fields an initializer cannot read a constructor parameter property yet.
  readonly queue$: Observable<ReviewQueueItem[] | null>;

  viewState: ViewState = 'loading';
  loadError: string | null = null;
  // Set when a background refresh fails but a cached queue is still on screen:
  // the admin keeps working with the old rows AND is told they may be stale.
  staleWarning: string | null = null;

  // Display "now" for the "Suspended 2h ago" labels. Captured per load, not
  // ticking: a queue row's age does not need second-level accuracy.
  nowMs = Date.now();

  private readonly destroy$ = new Subject<void>();

  constructor(private readonly adminService: AdminService, private readonly router: Router) {
    this.queue$ = adminService.queue$;
  }

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    // takeUntil teardown: the in-flight request is cancelled if the admin leaves
    // before it returns.
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.loadError = null;
    this.staleWarning = null;
    const hasCachedRows = this.adminService.hasQueueSnapshot;
    this.viewState = hasCachedRows ? 'ready' : 'loading';

    this.adminService
      .refreshQueue()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.nowMs = Date.now();
          this.viewState = 'ready';
        },
        error: error => {
          const message = describeAdminError(error);
          // Guard clause: with cached rows on screen, keep them and warn; with
          // nothing to show, this is a full-page error with a Retry button.
          if (hasCachedRows) {
            this.staleWarning = message;
            return;
          }
          this.loadError = message;
          this.viewState = 'error';
        }
      });
  }

  // True when any row carries a degraded slice (trust score or report count
  // could not be loaded). Drives the top-of-list notice so partial data is never
  // mistaken for complete data.
  hasDegradedRows(items: ReviewQueueItem[]): boolean {
    return items.some(item => this.isScoreUnavailable(item) || this.isCountUnavailable(item));
  }

  // Both checks test the backend flag AND the null: a null score with the flag
  // unset would still not be a number we can print as a rating.
  isScoreUnavailable(item: ReviewQueueItem): boolean {
    return item.trustScoreUnavailable || item.trustScore === null;
  }

  isCountUnavailable(item: ReviewQueueItem): boolean {
    return item.reportCountUnavailable || item.reportCount === null;
  }

  suspendedAgo(item: ReviewQueueItem): string | null {
    return relativeTime(item.suspendedAt, this.nowMs);
  }

  initials(item: ReviewQueueItem): string {
    return initialsOf(item.displayName);
  }

  tone(item: ReviewQueueItem): number {
    return avatarToneOf(item.displayName);
  }

  compactName(item: ReviewQueueItem): string {
    return truncateName(item.displayName);
  }

  pendingLabel(count: number): string {
    return count === 1 ? '1 user pending review' : `${count} users pending review`;
  }

  trackByUserId(_index: number, item: ReviewQueueItem): string {
    return item.userId;
  }

  goBack(): void {
    // The admin entry point is a hidden/direct-URL one (spec Screen 20), so there
    // is no meaningful "previous admin screen"; the app's home is the safe exit.
    void this.router.navigate(['/radar']);
  }
}
