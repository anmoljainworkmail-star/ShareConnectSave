import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject, combineLatest, merge, of, timer } from 'rxjs';
import { catchError, map, scan, startWith, switchMap } from 'rxjs/operators';

import { DashboardStats, TopReportedUserSummary } from '../../models/admin.models';
import { AdminService } from '../../services/admin.service';
import { describeAdminError } from '../../utils/admin-error';
import { avatarToneOf } from '../../utils/avatar';
import { describeAgeSeconds } from '../../utils/relative-time';

// FIGMA_SPEC.md Screen 22: "Auto-refreshes every 60 seconds (visible 'Last
// updated: X seconds ago' caption)". A fixed product requirement (also this
// ticket's acceptance criterion), not an environment-varying setting -- same
// status as the toast lifetimes in ToastService.
const STATS_REFRESH_INTERVAL_MS = 60_000;
// How often the "X seconds ago" caption re-renders.
const CLOCK_TICK_MS = 1_000;

interface MetricCard {
  key: string;
  label: string;
  value: number | null;
  // True when the backend flagged the metric unavailable (or sent no number).
  // The template renders "Unavailable", never a 0 -- see admin.models.ts.
  unavailable: boolean;
}

// What one fetch attempt produced. A 'loaded' event already carries the reduced
// state: the work that can choke on a malformed payload happens inside the inner
// pipe, where its failure becomes a 'failed' event instead of killing polling.
type FetchEvent =
  | { kind: 'loaded'; next: StatsState }
  | { kind: 'failed'; message: string };

// Accumulated knowledge across fetches. The LAST GOOD stats survive a failed
// refresh, so one blip does not blank a dashboard the admin is reading.
interface StatsState {
  cards: MetricCard[];
  topUser: TopReportedUserSummary | null;
  topUserUnavailable: boolean;
  hasStats: boolean;
  updatedAtMs: number | null;
  error: string | null;
}

export interface StatsView extends StatsState {
  phase: 'loading' | 'error' | 'ready';
  secondsAgo: number | null;
}

const INITIAL_STATE: StatsState = {
  cards: [],
  topUser: null,
  topUserUnavailable: false,
  hasStats: false,
  updatedAtMs: null,
  error: null
};

// FIGMA_SPEC.md Screen 22 -- Admin: Stats Dashboard (data = T060's GET
// /admin/stats). 5 metric cards: active users, scans, connections, reports today,
// and the top reported user.
//
// Polling pattern (ticket: "re-fetches on a fixed interval rather than requiring
// a manual refresh"). Plain English: instead of the admin pressing reload, the
// page asks the server again every 60 seconds by itself, like a wall clock that
// keeps ticking. Three details make that safe:
//  1. timer(0, 60000) fires immediately (first load) and then every 60s.
//  2. switchMap cancels a still-running request when the next tick or a manual
//     retry arrives, so responses can never arrive out of order and overwrite
//     newer data with older data.
//  3. catchError sits INSIDE the switchMap. An error that escaped to the outer
//     stream would terminate it and polling would silently stop forever after one
//     failed request; caught inside, a failure becomes just another event and the
//     next tick fires normally.
// Teardown: the template consumes vm$ through the async pipe, which unsubscribes
// when the component is destroyed -- that cancels the timers and any in-flight
// request, so no interval leaks after the admin navigates away. No manual
// subscribe, no ngOnDestroy bookkeeping.
@Component({
  selector: 'app-admin-stats-dashboard',
  templateUrl: './stats-dashboard.component.html',
  styleUrl: './stats-dashboard.component.css',
  // Project rule: module-based only (Angular 19 defaults to standalone).
  standalone: false
})
export class StatsDashboardComponent {
  readonly describeAge = describeAgeSeconds;

  // Manual "Retry now" after a failure, merged into the same fetch pipeline.
  private readonly retryNow$ = new Subject<void>();

  readonly vm$: Observable<StatsView>;

  constructor(private readonly adminService: AdminService, private readonly router: Router) {
    const fetchEvents$: Observable<FetchEvent> = merge(timer(0, STATS_REFRESH_INTERVAL_MS), this.retryNow$).pipe(
      switchMap(() =>
        this.adminService.getStats().pipe(
          // Error isolation (inner pipe): if reducing a malformed payload throws,
          // catchError below turns it into a 'failed' event, and the outer timer
          // keeps ticking. An error that escaped to the outer stream would end
          // polling for good.
          map((stats): FetchEvent => ({ kind: 'loaded', next: buildLoadedState(stats, Date.now()) })),
          catchError(error => of<FetchEvent>({ kind: 'failed', message: describeAdminError(error) }))
        )
      )
    );

    // scan = fold over time (reduce, but emitting every step): keeps the last
    // good stats in the accumulator while recording the latest failure.
    // startWith supplies the "loading" state before the first fetch settles.
    const state$ = fetchEvents$.pipe(scan(reduceState, INITIAL_STATE), startWith(INITIAL_STATE));

    // The clock is a separate stream joined by combineLatest, so the caption
    // ticks every second WITHOUT re-fetching anything.
    // startWith: first paint must not wait for timer(0)'s first (async) emission.
    const clock$ = timer(0, CLOCK_TICK_MS).pipe(
      map(() => Date.now()),
      startWith(Date.now())
    );

    this.vm$ = combineLatest([state$, clock$]).pipe(map(([state, nowMs]) => toView(state, nowMs)));
  }

  retryNow(): void {
    this.retryNow$.next();
  }

  tone(userId: string): number {
    return avatarToneOf(userId);
  }

  trackByKey(_index: number, card: MetricCard): string {
    return card.key;
  }

  goBack(): void {
    void this.router.navigate(['/admin']);
  }
}

function reduceState(state: StatsState, event: FetchEvent): StatsState {
  // Guard clause: a failure keeps everything we had and only records the error.
  if (event.kind === 'failed') {
    return { ...state, error: event.message };
  }
  return event.next;
}

function buildLoadedState(stats: DashboardStats, atMs: number): StatsState {
  return {
    cards: buildCards(stats),
    // Largest first, defensively: the order the backend sends is not part of its
    // contract, and "top reported user" must be the max whatever the order.
    // A nulled-out list from a broken upstream is treated as "no users".
    topUser: [...(stats.topReportedUsers ?? [])].sort((a, b) => b.reportCount - a.reportCount)[0] ?? null,
    topUserUnavailable: stats.topReportedUsersUnavailable,
    hasStats: true,
    updatedAtMs: atMs,
    error: null
  };
}

function toView(state: StatsState, nowMs: number): StatsView {
  const phase = state.hasStats ? 'ready' : state.error !== null ? 'error' : 'loading';
  return {
    ...state,
    phase,
    secondsAgo: state.updatedAtMs === null ? null : Math.max(0, Math.floor((nowMs - state.updatedAtMs) / 1000))
  };
}

// Open/Closed (O): a new metric is one more entry in this list, not a new
// template block. Order is the spec's.
function buildCards(stats: DashboardStats): MetricCard[] {
  return [
    card('active-users', 'Active users today', stats.totalActiveUsers, stats.totalActiveUsersUnavailable),
    card('scans', 'Scans today', stats.totalScansToday, stats.totalScansTodayUnavailable),
    card('connections', 'Connections today', stats.totalConnectionsAcceptedToday, stats.totalConnectionsAcceptedTodayUnavailable),
    card('reports', 'Reports today', stats.totalReportsToday, stats.totalReportsTodayUnavailable)
  ];
}

function card(key: string, label: string, value: number | null, unavailableFlag: boolean): MetricCard {
  return { key, label, value, unavailable: unavailableFlag || value === null };
}
