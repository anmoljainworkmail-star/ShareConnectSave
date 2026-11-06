import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { EMPTY, Observable, Subject } from 'rxjs';
import { catchError, map, switchMap, takeUntil } from 'rxjs/operators';

import { ErrorEnvelope } from '../auth/models/auth.models';
import { Gender, UpdateProfileRequest, UserProfileDto } from '../profile-setup/models/profile-setup.models';
import { UserProfileService } from '../profile-setup/user-profile.service';
import { ToastService } from '../shared/toast/toast.service';
import { GENDER_OPTIONS, LANGUAGE_OPTIONS } from './models/profile-options';

type ViewState = 'loading' | 'error' | 'ready';

// Screens 15/16 (profile header + Edit Profile), merged per the ticket: one
// component edits name, photo, preferred language and shows gender.
//
// Encapsulation of a cross-cutting concern (ticket pattern): this component
// never builds an Authorization header or a blob itself -- it asks
// UserProfileService.resolvePhotoUrl, the one authenticated-photo helper
// shared with Profile Setup/User Card/Chat/Rating. Every photo display point
// therefore gets the same auth treatment, and a bare <img src="{{photo_url}}">
// (which 401s at the gateway) never appears here.
@Component({
  selector: 'app-profile',
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.css',
  // Project rule: module-based only. Angular 19 defaults to standalone, so
  // it must be switched off explicitly on every hand-written component.
  standalone: false
})
export class ProfileComponent implements OnInit, OnDestroy {
  readonly genderOptions = GENDER_OPTIONS;
  readonly languageOptions = LANGUAGE_OPTIONS;

  viewState: ViewState = 'loading';
  loadError: string | null = null;

  isSaving = false;
  errorMessage: string | null = null;

  // Form state (draft). Server truth is `loaded`; these are what the user is editing.
  name = '';
  selectedLanguage = 'en';
  selectedGender: Gender = 'unspecified';

  identityBadge = false;
  photoUrl: string | null = null;

  // The profile as last confirmed by the server -- the baseline used to send
  // only fields that actually changed (PATCH semantics) and to know whether
  // gender is locked.
  private loaded: UserProfileDto | null = null;
  private selectedPhotoFile: File | null = null;
  private objectUrlToRevoke: string | null = null;

  // Latest-wins stream (switchMap): every profile apply pushes its photo here,
  // and switchMap cancels the previous in-flight photo fetch. Without it, two
  // quick applies race and an OLDER photo can land after a newer one.
  private readonly photoResolve$ = new Subject<string | null>();
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly userProfileService: UserProfileService,
    private readonly toastService: ToastService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.photoResolve$
      .pipe(
        switchMap(photoUrl =>
          this.userProfileService.resolvePhotoUrl(photoUrl).pipe(
            map(resolvedUrl => ({ photoUrl, resolvedUrl })),
            // A photo that fails to load must not block the form -- fall back to initials.
            catchError(() => {
              this.revokeObjectUrlIfAny();
              this.photoUrl = null;
              return EMPTY;
            })
          )
        ),
        takeUntil(this.destroy$)
      )
      .subscribe(({ photoUrl, resolvedUrl }) => this.adoptResolvedPhoto(photoUrl, resolvedUrl));

    this.loadProfile();
  }

  ngOnDestroy(): void {
    // Stop the photo stream first so nothing resolves (and creates a new blob)
    // after the revoke below has already run.
    this.destroy$.next();
    this.destroy$.complete();
    this.revokeObjectUrlIfAny();
  }

  // Immutable-after-first-use field (ticket pattern): once gender has been
  // set to Male/Female it is one-way, because women-only mode eligibility
  // hangs off it. Locking is a safety guard against post-hoc tampering, so it
  // fails CLOSED: any value other than the 'unspecified' default is locked.
  //
  // CONTRACT GAP (reported with this ticket): the spec says "locked after the
  // first scan session", but user-service exposes no first-scan / gender-lock
  // flag on GET /users/me and does not reject a gender PATCH after a scan.
  // The frontend cannot know about scans, so this is the strictest rule it
  // can enforce honestly. The save path below also never sends `gender` when
  // locked, so this screen can't be the one that changes it.
  get genderLocked(): boolean {
    return this.loaded !== null && this.loaded.gender !== 'unspecified';
  }

  get genderLabel(): string {
    return this.genderOptions.find(option => option.value === this.selectedGender)?.label ?? '';
  }

  get initials(): string {
    const trimmed = this.name.trim();
    return trimmed ? trimmed.charAt(0).toUpperCase() : '?';
  }

  get hasChanges(): boolean {
    if (!this.loaded) {
      return false;
    }
    return (
      this.selectedPhotoFile !== null ||
      this.name.trim() !== this.loaded.name ||
      this.selectedLanguage !== this.loaded.preferred_language ||
      this.genderChange() !== null
    );
  }

  loadProfile(): void {
    this.viewState = 'loading';
    this.loadError = null;

    this.userProfileService.getMyProfile().subscribe({
      next: profile => {
        this.applyProfile(profile);
        this.viewState = 'ready';
      },
      error: (error: ErrorEnvelope) => {
        this.loadError = error.message;
        this.viewState = 'error';
      }
    });
  }

  triggerFilePicker(inputEl: HTMLInputElement): void {
    inputEl.click();
  }

  onPhotoFileSelected(event: Event): void {
    const inputEl = event.target as HTMLInputElement;
    const file = inputEl.files?.[0] ?? null;
    // Reset so re-picking the same file still fires `change`.
    inputEl.value = '';

    // Guard clause: picker dismissed -- keep whatever is shown.
    if (!file) {
      return;
    }

    // Guard clause: fail fast locally on a non-image instead of a round trip
    // the server would reject anyway.
    if (!file.type.startsWith('image/')) {
      this.errorMessage = 'Please choose an image file.';
      return;
    }

    this.errorMessage = null;
    this.selectedPhotoFile = file;
    this.revokeObjectUrlIfAny();
    // Local preview of a file the user just picked -- a blob: URL of their own
    // file, no network and no auth involved.
    this.objectUrlToRevoke = URL.createObjectURL(file);
    this.photoUrl = this.objectUrlToRevoke;
  }

  setGender(gender: Gender): void {
    // Guard clause: the lock is enforced in the handler, not only by
    // [disabled] in the template.
    if (this.genderLocked) {
      return;
    }
    this.selectedGender = gender;
  }

  save(): void {
    // Guard clauses: nothing to save / already saving.
    if (!this.loaded || this.isSaving || !this.hasChanges) {
      return;
    }

    const trimmedName = this.name.trim();
    if (!trimmedName) {
      this.errorMessage = 'Enter your name to save.';
      return;
    }

    this.errorMessage = null;
    this.isSaving = true;

    const patch = this.buildPatch(trimmedName);
    const hasPatch = Object.keys(patch).length > 0;

    // Sequence photo upload BEFORE the PATCH (same ordering Profile Setup
    // uses): the PATCH response is the last write the server sees, so it is
    // the final word on the profile this screen then displays.
    let submission$: Observable<UserProfileDto>;
    if (this.selectedPhotoFile && hasPatch) {
      submission$ = this.userProfileService
        .uploadPhoto(this.selectedPhotoFile)
        .pipe(switchMap(() => this.userProfileService.updateProfile(patch)));
    } else if (this.selectedPhotoFile) {
      submission$ = this.userProfileService.uploadPhoto(this.selectedPhotoFile);
    } else {
      submission$ = this.userProfileService.updateProfile(patch);
    }

    submission$.subscribe({
      next: profile => {
        this.isSaving = false;
        this.selectedPhotoFile = null;
        // Re-adopt the server's response as truth (including the new
        // self-hosted photo_url) rather than trusting local draft values.
        this.applyProfile(profile);
        this.toastService.show('Profile saved', 'success');
      },
      error: (error: ErrorEnvelope) => {
        this.isSaving = false;
        this.errorMessage = error.message;
      }
    });
  }

  goBack(): void {
    this.router.navigate(['/radar']);
  }

  private buildPatch(trimmedName: string): UpdateProfileRequest {
    const patch: UpdateProfileRequest = {};
    if (this.loaded && trimmedName !== this.loaded.name) {
      patch.name = trimmedName;
    }
    if (this.loaded && this.selectedLanguage !== this.loaded.preferred_language) {
      patch.preferred_language = this.selectedLanguage;
    }
    const gender = this.genderChange();
    if (gender !== null) {
      patch.gender = gender;
    }
    return patch;
  }

  // The gender value to send, or null when there is nothing to send.
  // Always null while locked -- see genderLocked.
  private genderChange(): Gender | null {
    if (!this.loaded || this.genderLocked) {
      return null;
    }
    return this.selectedGender !== this.loaded.gender ? this.selectedGender : null;
  }

  private applyProfile(profile: UserProfileDto): void {
    this.loaded = profile;
    this.name = profile.name ?? '';
    this.selectedLanguage = profile.preferred_language || 'en';
    this.selectedGender = profile.gender;
    this.identityBadge = profile.identity_badge;

    this.photoResolve$.next(profile.photo_url);
  }

  private adoptResolvedPhoto(photoUrl: string | null, resolvedUrl: string | null): void {
    // A pending local pick wins over the server photo. The fetched blob is
    // discarded here, so it must be revoked now or it leaks for the tab's life.
    if (this.selectedPhotoFile) {
      if (resolvedUrl && resolvedUrl !== photoUrl) {
        URL.revokeObjectURL(resolvedUrl);
      }
      return;
    }
    this.revokeObjectUrlIfAny();
    this.photoUrl = resolvedUrl;
    // Only a blob: URL this component's fetch created is ours to revoke;
    // an external (Google CDN) URL returned unchanged never was.
    if (resolvedUrl && resolvedUrl !== photoUrl) {
      this.objectUrlToRevoke = resolvedUrl;
    }
  }

  private revokeObjectUrlIfAny(): void {
    // Without this each photo pick/refresh leaks one in-memory blob for the
    // life of the tab.
    if (!this.objectUrlToRevoke) {
      return;
    }
    URL.revokeObjectURL(this.objectUrlToRevoke);
    this.objectUrlToRevoke = null;
  }
}
