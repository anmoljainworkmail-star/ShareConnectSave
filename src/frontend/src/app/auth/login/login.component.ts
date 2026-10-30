import { AfterViewInit, Component, ElementRef, NgZone, OnDestroy, ViewChild } from '@angular/core';
import { Router } from '@angular/router';

import { environment } from '../../../environments/environment';
import { AuthService } from '../auth.service';
import { ErrorEnvelope } from '../models/auth.models';

// Narrow local typing for the one slice of the Google Identity Services
// (GSI) global API this component touches. GSI has no Angular/npm package --
// it attaches `google` to `window` once its <script> tag loads -- so this
// interface exists only to keep strict-mode TypeScript honest about an
// object the compiler never saw the source of, not to model the full API.
interface GoogleIdentityServices {
  accounts: {
    id: {
      initialize(config: {
        client_id: string;
        callback: (response: { credential: string }) => void;
      }): void;
      renderButton(
        parent: HTMLElement,
        options: { theme: string; size: string; width?: number }
      ): void;
    };
  };
}

declare global {
  interface Window {
    google?: GoogleIdentityServices;
  }
}

const GSI_SCRIPT_SRC = 'https://accounts.google.com/gsi/client';

// This component owns all DOM/script interaction with GSI (loading the
// script, initializing it, rendering its button). AuthService stays free of
// any DOM concern and only ever sees a plain idToken string -- Single
// Responsibility: "talk to Google's widget" and "talk to our auth API" are
// two different reasons to change, so they live in two different classes.
@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
  // Angular 19 defaults new components to standalone: true. This project is
  // module-based only (no standalone components, per project-wide Angular
  // rule), so that default must be overridden explicitly on every
  // hand-written component -- `ng generate`'s schematics default would do
  // this automatically, but these files are hand-authored.
  standalone: false
})
export class LoginComponent implements AfterViewInit, OnDestroy {
  @ViewChild('googleButton', { static: true }) googleButtonRef!: ElementRef<HTMLDivElement>;

  isSubmitting = false;
  errorMessage: string | null = null;

  private scriptElement: HTMLScriptElement | null = null;

  constructor(
    private readonly authService: AuthService,
    private readonly router: Router,
    private readonly ngZone: NgZone
  ) {}

  ngAfterViewInit(): void {
    this.loadGoogleScript();
  }

  ngOnDestroy(): void {
    this.scriptElement?.remove();
  }

  private loadGoogleScript(): void {
    // Guard clause: a repeat visit to /auth/login (e.g. after logout) should
    // reuse the already-loaded GSI script instead of injecting a duplicate
    // <script> tag every time this component is created.
    if (window.google) {
      this.initializeGoogleButton();
      return;
    }

    const script = document.createElement('script');
    script.src = GSI_SCRIPT_SRC;
    script.async = true;
    script.defer = true;
    script.onload = () => this.initializeGoogleButton();
    document.head.appendChild(script);
    this.scriptElement = script;
  }

  private initializeGoogleButton(): void {
    // Guard clause: onload fired but the global still isn't there (blocked
    // script, flaky network) -- nothing more this method can safely do.
    if (!window.google) {
      return;
    }

    window.google.accounts.id.initialize({
      client_id: environment.googleClientId,
      // Pattern: NgZone.run -- GSI invokes this callback from a plain
      // <script> tag Angular never instrumented, i.e. outside Angular's
      // zone. Without NgZone.run, the isSubmitting/errorMessage writes below
      // would never trigger change detection and the template would appear
      // frozen until some unrelated Angular event nudged it.
      callback: response => this.ngZone.run(() => this.handleCredentialResponse(response.credential))
    });

    window.google.accounts.id.renderButton(this.googleButtonRef.nativeElement, {
      theme: 'outline',
      size: 'large',
      width: 300
    });
  }

  private handleCredentialResponse(idToken: string): void {
    this.isSubmitting = true;
    this.errorMessage = null;

    this.authService.signInWithGoogle(idToken).subscribe({
      next: () => {
        this.isSubmitting = false;
        // Google sign-in alone is not "fully authenticated" for this
        // platform -- phone OTP verification is the mandatory second step
        // (see ticket: "two-step verification screen"), so the next stop is
        // always the OTP screen, never a protected feature route.
        this.router.navigate(['/auth/otp']);
      },
      error: (error: ErrorEnvelope) => {
        this.isSubmitting = false;
        this.errorMessage = error.message;
      }
    });
  }
}
