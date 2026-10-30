import { Component, ElementRef, OnDestroy, QueryList, ViewChildren } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService } from '../auth.service';
import { ErrorEnvelope } from '../models/auth.models';

type OtpStep = 'enter-phone' | 'enter-code';

// India-only for now, mirroring OtpController's PhoneNumberPattern exactly
// (user-service: ^\+91[6-9]\d{9}$) -- a fixed "+91" prefix in the template,
// not a real country-code dropdown, since there is nothing else to select
// while the platform has only one supported country. Loosen this back to a
// real selector when that changes.
const LOCAL_PHONE_PATTERN = /^[6-9]\d{9}$/;
const OTP_LENGTH = 6;
const RESEND_COOLDOWN_SECONDS = 45;
const SHAKE_ANIMATION_MS = 400;

// Two-step screen (phone -> code) modeled as one component with a `step`
// field rather than two routed components, because the two steps share all
// of their state (phone number) and neither step is independently
// bookmarkable/linkable -- a route per step would buy nothing here.
@Component({
  selector: 'app-otp-verify',
  templateUrl: './otp-verify.component.html',
  styleUrl: './otp-verify.component.css',
  // Angular 19 defaults new components to standalone: true. This project is
  // module-based only (no standalone components, per project-wide Angular
  // rule), so that default must be overridden explicitly on every
  // hand-written component.
  standalone: false
})
export class OtpVerifyComponent implements OnDestroy {
  // Template reference variables repeat per *ngFor iteration, so this
  // resolves to one entry per rendered OTP box, in DOM order -- that order
  // is what focusOtpBox(index) relies on to move focus forward/back.
  @ViewChildren('otpBox') private otpBoxRefs!: QueryList<ElementRef<HTMLInputElement>>;

  step: OtpStep = 'enter-phone';
  localPhoneDigits = '';
  otpDigits: string[] = new Array(OTP_LENGTH).fill('');
  isSubmitting = false;
  errorMessage: string | null = null;
  shakeOtp = false;

  resendCountdown = 0;
  private resendTimer: ReturnType<typeof setInterval> | null = null;

  private lockedUntil: Date | null = null;
  lockedCountdownLabel: string | null = null;
  private lockTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private readonly authService: AuthService, private readonly router: Router) {}

  get isLocked(): boolean {
    return this.lockedCountdownLabel !== null;
  }

  // The value actually sent to the backend -- always the unformatted
  // +91XXXXXXXXXX shape OtpController's regex expects, never the
  // space-grouped display version below.
  get fullPhoneNumber(): string {
    return `+91${this.localPhoneDigits}`;
  }

  // Cosmetic grouping only, matching FIGMA_SPEC.md's "Sent to +91 98765
  // 43210" -- this is display text, never a value read back out for an API
  // call.
  get displayPhoneNumber(): string {
    const digits = this.localPhoneDigits;
    return `+91 ${digits.slice(0, 5)} ${digits.slice(5)}`.trim();
  }

  goBackToLogin(): void {
    this.router.navigate(['/auth/login']);
  }

  sendOtp(): void {
    this.errorMessage = null;

    // Guard clause: reject malformed input locally before spending a
    // network round trip the backend would reject anyway.
    if (!LOCAL_PHONE_PATTERN.test(this.localPhoneDigits.trim())) {
      this.errorMessage = 'Enter a valid 10-digit Indian mobile number.';
      return;
    }

    this.isSubmitting = true;
    this.authService.sendOtp(this.fullPhoneNumber).subscribe({
      next: () => {
        this.isSubmitting = false;
        this.step = 'enter-code';
        this.startResendCountdown();
        this.focusOtpBox(0);
      },
      error: (error: ErrorEnvelope) => {
        this.isSubmitting = false;
        this.applyError(error);
      }
    });
  }

  resendOtp(): void {
    // Guard clause: the countdown IS the disabled state for this action --
    // nothing to do while it's still running.
    if (this.resendCountdown > 0 || this.isSubmitting) {
      return;
    }
    this.sendOtp();
  }

  onDigitInput(index: number, event: Event): void {
    const inputEl = event.target as HTMLInputElement;
    // Keep only the last character typed -- handles the rare case of an
    // IME or mobile keyboard inserting more than one character at once,
    // same defensive intent as the backend's own regex-based validation.
    const digit = inputEl.value.replace(/\D/g, '').slice(-1);
    this.otpDigits[index] = digit;
    inputEl.value = digit;

    // Auto-advance (FIGMA_SPEC.md Screen 03): a 6-box OTP input is only as
    // fast as one text field if the user still has to tap each box
    // themselves -- moving focus forward on every digit is what makes it
    // feel like one continuous entry instead of six separate fields.
    if (digit && index < OTP_LENGTH - 1) {
      this.focusOtpBox(index + 1);
    }
  }

  onDigitKeydown(index: number, event: KeyboardEvent): void {
    const inputEl = event.target as HTMLInputElement;
    // Guard clause: only an empty box's Backspace should jump focus back --
    // a non-empty box's Backspace should just clear that box first, the
    // same two-step behaviour every native multi-box OTP input uses.
    if (event.key !== 'Backspace' || inputEl.value || index === 0) {
      return;
    }
    this.focusOtpBox(index - 1);
  }

  verifyOtp(): void {
    this.errorMessage = null;
    const code = this.otpDigits.join('');

    // Guard clause: a code that isn't exactly 6 digits can't be correct --
    // reject before calling the API.
    if (code.length !== OTP_LENGTH) {
      this.errorMessage = 'Enter the 6-digit code exactly as received.';
      return;
    }

    this.isSubmitting = true;
    this.authService.verifyOtp(this.fullPhoneNumber, code).subscribe({
      next: () => {
        this.isSubmitting = false;
        // No protected feature module exists yet at this point in the
        // project (radar/chat/profile ship in later tickets) -- land on the
        // app root rather than a route that doesn't exist. This ticket's
        // scope stops at "JWT stored, user authenticated".
        this.router.navigate(['/']);
      },
      error: (error: ErrorEnvelope) => {
        this.isSubmitting = false;
        this.applyError(error);
      }
    });
  }

  changePhoneNumber(): void {
    this.stopLockCountdown();
    this.stopResendCountdown();
    this.step = 'enter-phone';
    this.resetOtpDigits();
    this.errorMessage = null;
  }

  ngOnDestroy(): void {
    this.stopResendCountdown();
    this.stopLockCountdown();
  }

  private applyError(error: ErrorEnvelope): void {
    this.errorMessage = error.message;

    // Guard clause: INVALID_OTP (OtpController's literal error code for a
    // wrong/expired code) gets the shake-and-retry treatment; anything else
    // falls through to the OTP_LOCKED check below instead.
    if (error.code === 'INVALID_OTP') {
      this.resetOtpDigits();
      this.focusOtpBox(0);
      this.triggerShake();
      return;
    }

    if (error.code === 'OTP_LOCKED') {
      this.startLockCountdown(error.message);
    }
  }

  private triggerShake(): void {
    this.shakeOtp = true;
    // One-shot visual reset, not a conditional branch -- this always fires
    // exactly once per failed attempt, clearing the animation class so it
    // can retrigger identically on the next failure.
    setTimeout(() => (this.shakeOtp = false), SHAKE_ANIMATION_MS);
  }

  private resetOtpDigits(): void {
    this.otpDigits = new Array(OTP_LENGTH).fill('');
  }

  private focusOtpBox(index: number): void {
    // setTimeout(0): on the very first render of 'enter-code', the target
    // box doesn't exist in the DOM yet -- ViewChildren only reflects what
    // Angular's change detection has already rendered, so this defers one
    // macrotask to let that render happen first.
    setTimeout(() => {
      this.otpBoxRefs?.toArray()[index]?.nativeElement.focus();
    });
  }

  private startResendCountdown(): void {
    this.stopResendCountdown();
    this.resendCountdown = RESEND_COOLDOWN_SECONDS;
    this.resendTimer = setInterval(() => {
      this.resendCountdown -= 1;
      if (this.resendCountdown <= 0) {
        this.stopResendCountdown();
      }
    }, 1000);
  }

  private stopResendCountdown(): void {
    if (this.resendTimer) {
      clearInterval(this.resendTimer);
      this.resendTimer = null;
    }
    this.resendCountdown = 0;
  }

  // Parses the lockout instant out of OtpController's OTP_LOCKED message
  // ("Too many failed attempts. Try again after {lockedUntil:O}.") rather
  // than reading a separate structured field, because OtpController has no
  // such field -- the instant is embedded in the message text only (see
  // that controller's OtpLocked() comment). Deliberately tolerant: if the
  // message is ever worded differently server-side, this just shows the
  // raw message with no countdown instead of crashing.
  private startLockCountdown(message: string): void {
    const match = message.match(/Try again after (.+)\.$/);
    const parsed = match ? new Date(match[1]) : null;

    // Guard clause: an unparseable instant means "show the message as-is,
    // no countdown" -- never a broken/garbage timer.
    if (!parsed || Number.isNaN(parsed.getTime())) {
      return;
    }

    this.lockedUntil = parsed;
    this.stopLockCountdown();
    this.updateLockCountdownLabel();
    this.lockTimer = setInterval(() => this.updateLockCountdownLabel(), 1000);
  }

  private updateLockCountdownLabel(): void {
    if (!this.lockedUntil) {
      return;
    }

    const remainingMs = this.lockedUntil.getTime() - Date.now();
    if (remainingMs <= 0) {
      this.stopLockCountdown();
      this.errorMessage = null;
      this.resetOtpDigits();
      return;
    }

    const totalSeconds = Math.ceil(remainingMs / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    this.lockedCountdownLabel = `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }

  private stopLockCountdown(): void {
    if (this.lockTimer) {
      clearInterval(this.lockTimer);
      this.lockTimer = null;
    }
    this.lockedUntil = null;
    this.lockedCountdownLabel = null;
  }

  trackByIndex(index: number): number {
    // Without this, *ngFor's default value-based tracking would see a
    // string at position `i` change on every keystroke and treat it as a
    // removed-and-re-added item -- recreating that box's DOM node and
    // dropping focus the instant auto-advance tries to move into it.
    // Tracking by position instead keeps the same <input> element alive for
    // the life of the OTP step, so focus survives every digit typed.
    return index;
  }
}
