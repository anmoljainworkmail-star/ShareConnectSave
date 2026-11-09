import { Component, ElementRef, NgZone, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { Router } from '@angular/router';

// How far the finger must travel (after resistance) before letting go refreshes.
const REFRESH_THRESHOLD_PX = 64;
// The indicator never follows the finger further than this.
const MAX_PULL_PX = 96;
// Finger travel is damped so the pull feels elastic rather than 1:1.
const RESISTANCE = 0.5;
// Ignore tiny drags so an ordinary tap or a slight scroll never shows the arrow.
const START_PULL_PX = 8;
// The gesture must BEGIN in the top band of the screen. Without this, a drag
// that starts anywhere (the bottom sheet, a button near the bottom edge) counts
// as "pull from the top" whenever the page itself is not scrolled, which is
// exactly how pulling from the bottom ended up refreshing the app.
const PULL_START_ZONE_PX = 120;

// Routes where a surprise reload would destroy something. Chat messages are
// ephemeral by design (no history endpoint), so a reload there loses them for
// good; the others are mid-flow screens with unsaved input.
const DISABLED_ROUTE_PREFIXES = ['/chat', '/rating', '/profile-setup', '/auth'];

// The Problem: an installed PWA (display: standalone) has no browser chrome, so
// there is no reload button, and not every mobile browser provides the native
// "pull down to refresh" gesture in that mode. A user whose screen is stale has
// no way to refresh it.
//
// Why it matters: the radar and the connection inbox are live views. A user who
// cannot refresh them assumes the app is broken.
//
// How this solves it: listen for a downward drag that starts while the page is
// already scrolled to the very top, show a small indicator that follows the
// finger, and reload the app if the drag passes a threshold. It mounts once in
// AppComponent, so every screen gets it without each one implementing it.
//
// Performance: the touch handlers run OUTSIDE Angular's zone. touchmove fires
// dozens of times a second, and inside the zone each event would trigger a
// full change-detection pass for a purely visual effect that needs none.
// The indicator is moved by writing its style directly. The listeners are
// `passive` so the browser never waits on this code before scrolling.
@Component({
  selector: 'app-pull-to-refresh',
  templateUrl: './pull-to-refresh.component.html',
  styleUrl: './pull-to-refresh.component.css',
  // Module-based project (no standalone components) -- see UserCardComponent.
  standalone: false
})
export class PullToRefreshComponent implements OnInit, OnDestroy {
  @ViewChild('indicator', { static: true }) private indicator!: ElementRef<HTMLElement>;

  private startY: number | null = null;
  private pullDistance = 0;
  private refreshing = false;

  private readonly onTouchStart = (event: TouchEvent): void => this.handleTouchStart(event);
  private readonly onTouchMove = (event: TouchEvent): void => this.handleTouchMove(event);
  private readonly onTouchEnd = (): void => this.handleTouchEnd();

  constructor(
    private readonly zone: NgZone,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.zone.runOutsideAngular(() => {
      document.addEventListener('touchstart', this.onTouchStart, { passive: true });
      document.addEventListener('touchmove', this.onTouchMove, { passive: true });
      document.addEventListener('touchend', this.onTouchEnd, { passive: true });
      document.addEventListener('touchcancel', this.onTouchEnd, { passive: true });
    });
  }

  ngOnDestroy(): void {
    document.removeEventListener('touchstart', this.onTouchStart);
    document.removeEventListener('touchmove', this.onTouchMove);
    document.removeEventListener('touchend', this.onTouchEnd);
    document.removeEventListener('touchcancel', this.onTouchEnd);
  }

  private handleTouchStart(event: TouchEvent): void {
    // Guard clauses: only a single finger, only when idle, only on screens where
    // a reload is safe, and only when nothing under the finger can still scroll up.
    if (this.refreshing || event.touches.length !== 1 || this.isDisabledRoute() || this.isHoldingTrain()) {
      this.startY = null;
      return;
    }
    if (event.touches[0].clientY > PULL_START_ZONE_PX) {
      this.startY = null;
      return;
    }
    if (this.isEditableTarget(event.target) || this.hasScrolledAncestor(event.target)) {
      this.startY = null;
      return;
    }
    this.startY = event.touches[0].clientY;
    this.pullDistance = 0;
  }

  private handleTouchMove(event: TouchEvent): void {
    if (this.startY === null || this.refreshing) {
      return;
    }
    // A finger that picked up a background train is playing, not pulling.
    if (this.isHoldingTrain()) {
      this.startY = null;
      this.render(0, false);
      return;
    }
    const delta = event.touches[0].clientY - this.startY;
    // Dragging up (or not yet past the dead zone) is an ordinary scroll: stop
    // tracking so a later downward move in the same gesture cannot trigger.
    if (delta < START_PULL_PX) {
      this.render(0, false);
      this.pullDistance = 0;
      return;
    }
    this.pullDistance = Math.min(delta * RESISTANCE, MAX_PULL_PX);
    this.render(this.pullDistance, this.pullDistance >= REFRESH_THRESHOLD_PX);
  }

  private handleTouchEnd(): void {
    if (this.startY === null) {
      return;
    }
    const shouldRefresh = this.pullDistance >= REFRESH_THRESHOLD_PX;
    this.startY = null;

    if (!shouldRefresh) {
      this.render(0, false);
      return;
    }

    this.refreshing = true;
    this.indicator.nativeElement.classList.add('refreshing');
    // A full reload is the honest "refresh" for this app: it re-runs auth,
    // reconnects both SignalR hubs and re-fetches every screen's data from the
    // server, the same recovery a user would get from a browser reload.
    window.location.reload();
  }

  // Direct DOM writes, not Angular bindings: see the performance note above.
  private render(distance: number, armed: boolean): void {
    const element = this.indicator.nativeElement;
    element.style.setProperty('--pull-distance', `${distance}px`);
    element.style.opacity = distance > 0 ? '1' : '0';
    element.classList.toggle('armed', armed);
  }

  // AmbientBackgroundComponent flags the document while a train is held (a
  // plain data attribute: the two components stay decoupled, neither imports
  // the other). Pointer events fire before touch events for the same finger, so
  // the flag is already set by the time touchstart gets here.
  private isHoldingTrain(): boolean {
    return document.documentElement.dataset['trainDrag'] !== undefined;
  }

  private isDisabledRoute(): boolean {
    const url = this.router.url;
    return DISABLED_ROUTE_PREFIXES.some(prefix => url.startsWith(prefix));
  }

  // Never hijack a gesture that starts inside a text field: the user is
  // selecting text or moving the caret, not asking for a refresh.
  private isEditableTarget(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) {
      return false;
    }
    return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
  }

  // Walk from the touched element up to the document. If ANY scrollable
  // container on the way is not at its top, the drag means "scroll that
  // container back up", never "refresh". Without this, pulling down inside a
  // scrolled list would reload the page instead of scrolling the list.
  private hasScrolledAncestor(target: EventTarget | null): boolean {
    let node = target instanceof HTMLElement ? target : null;
    while (node && node !== document.body) {
      const overflowY = getComputedStyle(node).overflowY;
      const scrollable = overflowY === 'auto' || overflowY === 'scroll';
      if (scrollable && node.scrollTop > 0) {
        return true;
      }
      node = node.parentElement;
    }
    return window.scrollY > 0;
  }
}
