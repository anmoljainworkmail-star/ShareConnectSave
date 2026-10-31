import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { Observable } from 'rxjs';
import { switchMap } from 'rxjs/operators';

import { ErrorEnvelope } from '../auth/models/auth.models';
import { Gender, UserProfileDto } from './models/profile-setup.models';
import { UserProfileService } from './user-profile.service';

type WizardStep = 'photo' | 'basic-info' | 'ready';

// Wizard / multi-step form state (named in the ticket): Steps 1-3 share one
// component's `step` field for the same structural reason OtpVerifyComponent
// models phone-entry/code-entry as one component rather than two routed
// ones -- every step here reads and writes the same in-progress profile, and
// none of the three is independently bookmarkable/linkable, so a route per
// step would only add indirection without buying anything.
const STEP_ORDER: WizardStep[] = ['photo', 'basic-info', 'ready'];

const GENDER_OPTIONS: ReadonlyArray<{ value: Gender; label: string }> = [
  { value: 'male', label: 'Male' },
  { value: 'female', label: 'Female' },
  { value: 'unspecified', label: 'Prefer not to say' }
];

// Not Figma-specified (Screen 04 shows "a dropdown" with no option list) --
// a flagged implementation decision, kept small and explicit rather than
// invented as a long guessed list. preferred_language is a free-form string
// to the backend (UserProfileDto.preferred_language), so adding a language
// later is a config change here, never a validator change (Open/Closed,
// same shape as the Rating-tags example in CLAUDE.md).
const LANGUAGE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'en', label: 'English' },
  { code: 'hi', label: 'Hindi' },
  { code: 'es', label: 'Spanish' },
  { code: 'fr', label: 'French' },
  { code: 'ar', label: 'Arabic' },
  { code: 'pt', label: 'Portuguese' },
  { code: 'zh', label: 'Mandarin Chinese' }
];

@Component({
  selector: 'app-profile-setup',
  templateUrl: './profile-setup.component.html',
  styleUrl: './profile-setup.component.css',
  // Angular 19 defaults new components to standalone: true. This project is
  // module-based only (no standalone components, per project-wide Angular
  // rule), so that default must be overridden explicitly on every
  // hand-written component.
  standalone: false
})
export class ProfileSetupComponent implements OnInit, OnDestroy {
  readonly genderOptions = GENDER_OPTIONS;
  readonly languageOptions = LANGUAGE_OPTIONS;
  readonly stepOrder = STEP_ORDER;

  step: WizardStep = 'photo';
  isLoadingProfile = true;
  loadError: string | null = null;

  isSubmitting = false;
  errorMessage: string | null = null;

  // Step 1 state
  photoPreviewUrl: string | null = null;
  private selectedPhotoFile: File | null = null;
  private objectUrlToRevoke: string | null = null;

  // Step 2 state
  name = '';
  selectedGender: Gender = 'unspecified';
  selectedLanguage = 'en';

  // Step 3 state -- set only once the PATCH response comes back, never
  // assumed. Step 3's copy reads this instead of unconditionally claiming
  // success, per the ticket's "do not claim You're all set if onboarding did
  // not actually complete server-side" instruction.
  onboardingComplete = false;

  constructor(private readonly userProfileService: UserProfileService, private readonly router: Router) {}

  ngOnInit(): void {
    this.loadProfile();
  }

  ngOnDestroy(): void {
    this.revokeObjectUrlIfAny();
  }

  get stepIndex(): number {
    return this.stepOrder.indexOf(this.step);
  }

  get hasPhoto(): boolean {
    return this.photoPreviewUrl !== null;
  }

  // Figma: "surface the consequence... before letting them continue" -- this
  // is read directly by the template so the inline note appears the instant
  // "Prefer not to say" is selected, not only after a failed submit attempt.
  get genderMeansIncompleteProfile(): boolean {
    return this.selectedGender === 'unspecified';
  }

  loadProfile(): void {
    this.isLoadingProfile = true;
    this.loadError = null;

    this.userProfileService.getMyProfile().subscribe({
      next: profile => {
        this.isLoadingProfile = false;
        this.applyProfileToForm(profile);
      },
      error: (error: ErrorEnvelope) => {
        this.isLoadingProfile = false;
        this.loadError = error.message;
      }
    });
  }

  triggerFilePicker(inputEl: HTMLInputElement): void {
    inputEl.click();
  }

  onPhotoFileSelected(event: Event): void {
    const inputEl = event.target as HTMLInputElement;
    const file = inputEl.files?.[0] ?? null;
    // Reset the input's value so re-picking the exact same file still fires
    // a change event next time (browsers don't re-fire `change` for an
    // unchanged file list otherwise).
    inputEl.value = '';

    // Guard clause: the picker can be dismissed with no file chosen -- leave
    // whatever photo (Google's or a prior pick) already shown untouched.
    if (!file) {
      return;
    }

    this.selectedPhotoFile = file;
    this.revokeObjectUrlIfAny();
    const previewUrl = URL.createObjectURL(file);
    this.objectUrlToRevoke = previewUrl;
    this.photoPreviewUrl = previewUrl;
  }

  goToBasicInfo(): void {
    // Guard clause: this is the client-side half of the backend's
    // NO_BASE_PHOTO precondition (see ticket) -- a profile with no Google
    // photo and no manual upload must not be allowed to pass this point,
    // the same gate the server would otherwise have to enforce alone.
    if (!this.hasPhoto) {
      return;
    }
    this.step = 'basic-info';
  }

  backToPhoto(): void {
    this.step = 'photo';
  }

  selectGender(gender: Gender): void {
    this.selectedGender = gender;
  }

  submitBasicInfo(): void {
    this.errorMessage = null;
    const trimmedName = this.name.trim();

    // Guard clause: reject locally before spending a network round trip the
    // backend's own INVALID_REQUEST check would reject anyway.
    if (!trimmedName) {
      this.errorMessage = 'Enter your name to continue.';
      return;
    }

    this.isSubmitting = true;
    const patch = {
      name: trimmedName,
      gender: this.selectedGender,
      preferred_language: this.selectedLanguage
    };

    // Sequence the photo upload before the profile PATCH (not in parallel):
    // PATCH's response is treated as the final word on onboarding_complete
    // for Step 3, so it must be the LAST write the server sees -- if the two
    // raced, a photo-upload response arriving after the PATCH response could
    // overwrite that final truth with a stale intermediate one.
    const submission$: Observable<UserProfileDto> = this.selectedPhotoFile
      ? this.userProfileService
          .uploadPhoto(this.selectedPhotoFile)
          .pipe(switchMap(() => this.userProfileService.updateProfile(patch)))
      : this.userProfileService.updateProfile(patch);

    submission$.subscribe({
      next: profile => {
        this.isSubmitting = false;
        this.onboardingComplete = profile.onboarding_complete;
        this.step = 'ready';
      },
      error: (error: ErrorEnvelope) => {
        this.isSubmitting = false;
        this.errorMessage = error.message;
      }
    });
  }

  startExploring(): void {
    // No protected home screen exists yet (see CLAUDE.md's microservices
    // list -- Radar/Activity/Chat ship in later tickets), so this is the
    // only sensible destination regardless of onboardingComplete.
    this.router.navigate(['/']);
  }

  trackByStep(_index: number, step: WizardStep): WizardStep {
    return step;
  }

  private applyProfileToForm(profile: UserProfileDto): void {
    this.name = profile.name ?? '';
    this.selectedGender = profile.gender;
    this.selectedLanguage = profile.preferred_language || 'en';

    // Guard clause: only adopt the server's photo when nothing has been
    // locally picked yet -- a profile reload must never clobber a photo the
    // user already chose in this same session.
    if (this.selectedPhotoFile) {
      return;
    }

    const sourcePhotoUrl = profile.photo_url;
    // REQUIREMENTS.md §2: a bare <img src="{{photo_url}}"> 401s once the
    // photo is self-hosted (uploaded via POST /users/me/photo) -- every
    // gateway route requires a JWT, static photo files included. See
    // UserProfileService.resolvePhotoUrl's comment for why a Google-provided
    // photo_url is handled differently (never routed through HttpClient).
    this.userProfileService.resolvePhotoUrl(sourcePhotoUrl).subscribe({
      next: resolvedUrl => {
        this.revokeObjectUrlIfAny();
        this.photoPreviewUrl = resolvedUrl;
        // Only a URL this component itself created (the self-hosted,
        // blob: case) is ours to revoke later -- an external URL returned
        // unchanged was never ours to begin with.
        if (resolvedUrl && resolvedUrl !== sourcePhotoUrl) {
          this.objectUrlToRevoke = resolvedUrl;
        }
      },
      error: () => {
        // Guard clause: a photo that fails to load shouldn't block the rest
        // of the form -- fall back to "no photo shown", which Step 1's
        // existing hasPhoto gate already treats as "needs a fresh upload."
        this.photoPreviewUrl = null;
      }
    });
  }

  private revokeObjectUrlIfAny(): void {
    // Releasing the blob: URL this component created -- without this, every
    // photo re-pick during a long session leaks one more in-memory blob for
    // the life of the tab.
    if (!this.objectUrlToRevoke) {
      return;
    }
    URL.revokeObjectURL(this.objectUrlToRevoke);
    this.objectUrlToRevoke = null;
  }
}
