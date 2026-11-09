import { Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { Subscription } from 'rxjs';

import { UserProfileService } from '../../profile-setup/user-profile.service';
import { UserCardData } from '../models/radar.models';

// Screen 09 (User Card, Bottom Sheet) button states. 'unavailable' is driven
// by the card's own `user.unavailable` field, not a connection state, so it
// is intentionally NOT one of these -- see `bannerMessage` below. 'sending' (T067)
// is the in-flight POST /connections window: it disables the button so a
// double-tap cannot fire two requests.
export type ConnectionState = 'default' | 'sending' | 'sent' | 'connected';

// Interface Segregation (I), applied to UI reuse (named in the ticket's
// "Patterns demonstrated"): this component is shaped around "show one
// discovered user's data, optionally with a Send Request action" -- nothing
// here knows Radar exists. That is exactly what lets T067's
// InboundRequestComponent (a different feature module entirely) import and
// reuse this component for the sender's card by simply setting
// [showSendRequest]="false" and projecting its own Accept/Decline buttons
// alongside it, instead of copy-pasting this template.
@Component({
  selector: 'app-user-card',
  templateUrl: './user-card.component.html',
  styleUrl: './user-card.component.css',
  // Angular 19 defaults new components to standalone: true. This project is
  // module-based only (no standalone components, per project-wide Angular
  // rule), so that default must be overridden explicitly on every
  // hand-written component.
  standalone: false
})
export class UserCardComponent implements OnChanges, OnDestroy {
  @Input() user: UserCardData | null = null;
  @Input() connectionState: ConnectionState = 'default';
  // T067's InboundRequestComponent reuses this component for the SENDER's
  // card alongside its own Accept/Decline buttons -- it has no use for a
  // second "Send Request" action on the same person, hence this flag rather
  // than a second, Radar-specific component.
  @Input() showSendRequest = true;

  @Output() sendRequest = new EventEmitter<number>();
  @Output() reportUser = new EventEmitter<number>();
  @Output() dismissed = new EventEmitter<void>();

  readonly starIndexes = [1, 2, 3, 4, 5] as const;

  // Authenticated-photo-fetch result (REQUIREMENTS.md §2) -- see
  // resolvePhoto() below for why this delegates to UserProfileService
  // instead of re-implementing the fetch here.
  photoUrl: string | null = null;

  private photoSubscription?: Subscription;
  // Only a blob: URL THIS component minted (via resolvePhotoUrl's
  // HttpClient-fetch-to-blob path) is ours to revoke -- a Google-hosted URL
  // passed back unchanged was never ours, same distinction
  // ProfileSetupComponent.applyProfileToForm already draws.
  private ownedBlobUrl: string | null = null;

  constructor(private readonly userProfileService: UserProfileService) {}

  ngOnChanges(changes: SimpleChanges): void {
    // Guard clause: only re-fetch when the `user` input itself changed (a
    // new card, or the same card with a new photo_url) -- connectionState/
    // showSendRequest changes alone have no reason to re-trigger a network
    // fetch.
    if (!changes['user']) {
      return;
    }
    this.resolvePhoto();
  }

  ngOnDestroy(): void {
    this.photoSubscription?.unsubscribe();
    this.revokeOwnedBlobIfAny();
  }

  get starRatingRounded(): number {
    return Math.round(this.user?.rating_average ?? 0);
  }

  get hasRating(): boolean {
    return this.user?.rating_average !== undefined;
  }

  // Screen 09: "User unavailable: banner 'This user is not looking for a
  // companion right now.'" -- distinct from connectionState, which only
  // describes THIS viewer's own request history with this person.
  get bannerMessage(): string | null {
    if (!this.user?.unavailable) {
      return null;
    }
    return 'This user is not looking for a companion right now.';
  }

  // The API sends distance_km as a raw double (e.g. 0.48372915), which read as
  // "0.48372915 km away". Under 1 km a person thinks in metres ("350 m"), at
  // or above 1 km in one-decimal km ("1.2 km"). Rounded to the nearest 10 m:
  // GPS accuracy is tens of metres, so finer precision would be false
  // precision. Principle: format at the presentation edge, keep the DTO raw.
  distanceLabel(distanceKm: number): string {
    if (distanceKm < 1) {
      const metres = Math.max(10, Math.round((distanceKm * 1000) / 10) * 10);
      return `${metres} m away`;
    }
    return `${distanceKm.toFixed(1)} km away`;
  }

  onSendRequest(): void {
    if (!this.user) {
      return;
    }
    this.sendRequest.emit(this.user.id);
  }

  onReport(): void {
    if (!this.user) {
      return;
    }
    this.reportUser.emit(this.user.id);
  }

  onDismiss(): void {
    this.dismissed.emit();
  }

  // Authenticated photo fetch (REQUIREMENTS.md §2, DRY pattern named in the
  // ticket): delegates straight to UserProfileService.resolvePhotoUrl rather
  // than a second, Radar-local "authenticated-photo.service.ts" -- that
  // method (added for ProfileSetupComponent) already IS the one
  // authenticated-fetch-to-blob-URL path every photo in the app is supposed
  // to share. A second implementation here would just be the exact
  // DRY/single-path violation this ticket's own "Patterns demonstrated"
  // section warns against.
  private resolvePhoto(): void {
    this.photoSubscription?.unsubscribe();
    this.revokeOwnedBlobIfAny();
    this.photoUrl = null;

    const sourceUrl = this.user?.photo_url ?? null;
    this.photoSubscription = this.userProfileService.resolvePhotoUrl(sourceUrl).subscribe({
      next: resolvedUrl => {
        this.photoUrl = resolvedUrl;
        if (resolvedUrl && resolvedUrl !== sourceUrl) {
          this.ownedBlobUrl = resolvedUrl;
        }
      },
      error: () => {
        // Guard clause: a photo that fails to load shouldn't block the rest
        // of the card -- fall back to the initials placeholder the template
        // already renders when photoUrl is null.
        this.photoUrl = null;
      }
    });
  }

  private revokeOwnedBlobIfAny(): void {
    // Without this, opening many different discovered-user cards in one
    // session (exactly what a radar screen does) leaks one more in-memory
    // blob per card for the life of the tab -- the ticket's own "What NOT to
    // do" calls this out explicitly.
    if (!this.ownedBlobUrl) {
      return;
    }
    URL.revokeObjectURL(this.ownedBlobUrl);
    this.ownedBlobUrl = null;
  }
}
