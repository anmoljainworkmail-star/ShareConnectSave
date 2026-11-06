import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { EMPTY, Subject, fromEvent, merge } from 'rxjs';
import { catchError, filter, switchMap, takeUntil, tap } from 'rxjs/operators';

import { ErrorEnvelope } from '../../auth/models/auth.models';
import { AvailabilityStatus, UserProfileDto } from '../../profile-setup/models/profile-setup.models';
import { UserProfileService } from '../../profile-setup/user-profile.service';
import { ScanSessionService } from '../../radar/scan-session.service';
import { ToastService } from '../../shared/toast/toast.service';

type ViewState = 'loading' | 'error' | 'ready';

// Screen 18 (Settings), scoped by the ticket to: availability toggle,
// women-only toggle (female users only) and account info.
//
// Single source of truth (ticket pattern): `status` is backend-owned and has
// TWO independent writers -- this toggle and the radar's Start Scan / exit
// lifecycle (RadarComponent). So this component never treats its own copy as
// authoritative:
//   1. it reads GET /users/me on entry AND whenever the tab/window regains
//      focus (the user may have started/stopped a scan elsewhere meanwhile);
//   2. a toggle is PESSIMISTIC -- the switch only moves once the server's
//      PATCH response confirms it, and then adopts that response's value;
//   3. the write goes through the same UserProfileService.updateProfile call
//      the radar uses, so there is one write path to the field, not two.
@Component({
  selector: 'app-settings',
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
  // Project rule: module-based only -- see ProfileComponent.
  standalone: false
})
export class SettingsComponent implements OnInit, OnDestroy {
  viewState: ViewState = 'loading';
  loadError: string | null = null;

  status: AvailabilityStatus = 'looking';
  isUpdatingStatus = false;
  errorMessage: string | null = null;

  // Least-knowledge exposure (same reasoning as UserProfileService.isFemale):
  // the template needs one boolean -- "does the women-only toggle exist?" --
  // never the gender value itself. Defaults to false so the toggle is absent
  // until the server says otherwise (fails closed).
  isFemale = false;

  account: UserProfileDto | null = null;

  // Observer via Subject: every "go re-read the server" trigger (first load,
  // focus, retry) pushes here, and switchMap guarantees only the NEWEST read
  // can land -- an older, slower response can't overwrite a fresher one.
  private readonly refresh$ = new Subject<void>();
  private readonly destroy$ = new Subject<void>();

  // Incremented every time a write (toggle) settles; see the sequence guard in ngOnInit.
  private writeVersion = 0;

  constructor(
    private readonly userProfileService: UserProfileService,
    private readonly scanSessionService: ScanSessionService,
    private readonly toastService: ToastService,
    private readonly router: Router
  ) {}

  // Women-only state lives in ScanSessionService (the radar's filter state,
  // T066) -- read-through, so this toggle and the Discovery Filters sheet can
  // never disagree. The Discovery Service still enforces the filter
  // server-side; this is only the switch that feeds scan-start.
  get womenOnly(): boolean {
    return this.scanSessionService.filters.womenOnly;
  }

  get isLooking(): boolean {
    return this.status === 'looking';
  }

  ngOnInit(): void {
    this.refresh$
      .pipe(
        // Guard: never let a background re-read race an in-flight toggle;
        // the toggle's own response is about to set the truth.
        filter(() => !this.isUpdatingStatus),
        switchMap(() => {
          // Sequence guard (optimistic-concurrency style, a version counter):
          // a GET is a snapshot taken at request time. If a PATCH finished
          // after this GET started, the snapshot is older than the write and
          // must not overwrite it. switchMap only cancels OLDER GETs, not a GET
          // already in flight when the toggle was clicked -- this check catches that.
          const versionAtStart = this.writeVersion;
          return this.userProfileService.getMyProfile().pipe(
            tap(profile => {
              if (this.isUpdatingStatus || versionAtStart !== this.writeVersion) {
                return;
              }
              this.applyProfile(profile);
            }),
            catchError((error: ErrorEnvelope) => {
              this.handleLoadError(error);
              return EMPTY;
            })
          );
        }),
        takeUntil(this.destroy$)
      )
      .subscribe();

    // Re-sync when the user comes back to this tab/window: the radar may have
    // flipped status on another screen while this one sat open. visibilitychange
    // covers tab switches, window 'focus' covers window switches.
    merge(fromEvent(document, 'visibilitychange'), fromEvent(window, 'focus'))
      .pipe(
        filter(() => document.visibilityState === 'visible' && this.viewState === 'ready'),
        takeUntil(this.destroy$)
      )
      .subscribe(() => this.refresh$.next());

    this.refresh$.next();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  retry(): void {
    this.viewState = 'loading';
    this.loadError = null;
    this.refresh$.next();
  }

  toggleStatus(): void {
    // Guard clause: one write at a time.
    if (this.isUpdatingStatus) {
      return;
    }

    const requested: AvailabilityStatus = this.isLooking ? 'unavailable' : 'looking';
    this.errorMessage = null;
    this.isUpdatingStatus = true;

    this.userProfileService.updateProfile({ status: requested }).subscribe({
      next: profile => {
        this.isUpdatingStatus = false;
        this.writeVersion++;
        // Adopt what the SERVER says it now is, not what we asked for.
        this.applyProfile(profile);
      },
      error: (error: ErrorEnvelope) => {
        this.isUpdatingStatus = false;
        this.writeVersion++;
        // Switch never moved, so there is nothing to roll back.
        this.errorMessage = error.message;
      }
    });
  }

  toggleWomenOnly(): void {
    // Guard clause: the toggle is not rendered for non-female users, but the
    // handler refuses too -- UI hiding is not the enforcement point (the
    // Discovery Service filter is).
    if (!this.isFemale) {
      return;
    }
    const filters = this.scanSessionService.filters;
    this.scanSessionService.updateFilters({ ...filters, womenOnly: !filters.womenOnly });
    this.toastService.show('Women-only mode applies from your next scan');
  }

  goBack(): void {
    this.router.navigate(['/profile']);
  }

  private applyProfile(profile: UserProfileDto): void {
    this.account = profile;
    this.status = profile.status;
    this.isFemale = profile.gender === 'female';
    this.loadError = null;
    this.viewState = 'ready';
  }

  private handleLoadError(error: ErrorEnvelope): void {
    // A failed BACKGROUND re-sync must not blank a screen that already has
    // data -- keep showing it. Only the first load escalates to the error view.
    if (this.viewState === 'ready') {
      return;
    }
    this.loadError = error.message;
    this.viewState = 'error';
  }
}
