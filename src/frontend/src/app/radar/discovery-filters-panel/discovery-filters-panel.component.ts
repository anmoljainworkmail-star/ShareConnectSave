import { Component, EventEmitter, HostListener, Input, OnDestroy, OnInit, Output } from '@angular/core';

import {
  DepartureWindowMinutes,
  DiscoveryFilters,
  MAX_RADIUS_KM,
  MIN_RADIUS_KM,
  RADIUS_STEP_KM
} from '../models/radar.models';

// FIGMA_SPEC.md Screen 17's segmented control: [+/-15] [+/-30] [+/-60].
const DEPARTURE_WINDOW_OPTIONS: ReadonlyArray<DepartureWindowMinutes> = [15, 30, 60];

// Presentational ("dumb") component -- Single Responsibility (S). Analogy: a
// car's dashboard dial. It shows a value and lets you turn it, but the dial
// itself is not where the engine's real setting is stored. This component
// only renders the controls and reports "the user chose X" through its
// outputs; ScanSessionService owns the real filter state, and the parent
// (RadarComponent) is the one that carries the choice from here to there.
// Without this split, the panel would need to know about scan sessions and
// HTTP, and could not be reused or reasoned about on its own.
//
// Module-based only (project-wide Angular rule): declared in RadarModule,
// standalone explicitly false because Angular 19 defaults new components to
// standalone.
@Component({
  selector: 'app-discovery-filters-panel',
  templateUrl: './discovery-filters-panel.component.html',
  styleUrl: './discovery-filters-panel.component.css',
  standalone: false
})
export class DiscoveryFiltersPanelComponent implements OnInit, OnDestroy {
  // The currently STORED filters (from ScanSessionService, via the parent).
  @Input({ required: true }) filters!: DiscoveryFilters;

  // The single boolean the panel is allowed to know about gender: "does the
  // women-only toggle apply to this user". The template guards on it with
  // *ngIf, so for everyone else the control is never created at all -- not
  // merely hidden -- see the template's comment for why that distinction
  // matters.
  @Input() showWomenOnly = false;

  @Output() applied = new EventEmitter<DiscoveryFilters>();
  @Output() dismissed = new EventEmitter<void>();

  readonly minRadiusKm = MIN_RADIUS_KM;
  readonly maxRadiusKm = MAX_RADIUS_KM;
  readonly radiusStepKm = RADIUS_STEP_KM;
  readonly departureWindowOptions = DEPARTURE_WINDOW_OPTIONS;

  // Draft copy, edited freely and only emitted on "Apply Filters". Working on
  // a copy is what makes closing the sheet (backdrop tap / Escape) a true
  // cancel: the stored state was never touched, so there is nothing to undo.
  radiusKm = MIN_RADIUS_KM;
  departureWindowMinutes: DepartureWindowMinutes = 30;
  womenOnly = false;

  ngOnInit(): void {
    // Seeds the draft from stored state each time the sheet opens (the parent
    // renders this component under *ngIf, so it is re-created per open).
    this.radiusKm = this.filters.radiusKm;
    this.departureWindowMinutes = this.filters.departureWindowMinutes;
    this.womenOnly = this.filters.womenOnly;
  }

  selectDepartureWindow(minutes: DepartureWindowMinutes): void {
    this.departureWindowMinutes = minutes;
  }

  apply(): void {
    this.applied.emit({
      radiusKm: this.radiusKm,
      departureWindowMinutes: this.departureWindowMinutes,
      // Guard clause: a user the toggle does not apply to can never emit
      // true, even if the stored value somehow said otherwise.
      womenOnly: this.showWomenOnly && this.womenOnly
    });
  }

  // --- Drag-to-dismiss ------------------------------------------------------
  // Pointer Events (one code path for finger, mouse and pen), same mechanism as
  // the Radar's "Where are you heading?" sheet. Unlike that one, this sheet is
  // MODAL (it sits on an overlay), and the platform convention for a modal
  // bottom sheet is "drag it down to dismiss", not "tuck it away" -- so it has
  // one resting position (open) and a release past a threshold closes it.
  // Dismissing goes through the same `dismissed` output as the backdrop tap and
  // Escape, so the draft is discarded identically on every close path.
  sheetOffsetPx = 0;
  isDragging = false;
  private dragStartY = 0;
  private dragStartTime = 0;
  private sheetHeightPx = 0;
  private closeTimer: ReturnType<typeof setTimeout> | null = null;

  onDragStart(event: PointerEvent): void {
    // Guard clause: ignore a second pointer / a drag that is already closing.
    if (this.isDragging || this.closeTimer !== null) {
      return;
    }
    const zone = event.currentTarget as HTMLElement;
    this.sheetHeightPx = zone.parentElement?.offsetHeight ?? 0;
    this.dragStartY = event.clientY;
    this.dragStartTime = performance.now();
    this.isDragging = true;
    // Capture keeps move/up events flowing here even when the finger slides
    // off the grab strip -- otherwise a fast drag "drops" the sheet.
    zone.setPointerCapture(event.pointerId);
  }

  onDragMove(event: PointerEvent): void {
    if (!this.isDragging) {
      return;
    }
    // Downward only: dragging up past the resting position does nothing.
    this.sheetOffsetPx = Math.max(event.clientY - this.dragStartY, 0);
  }

  onDragEnd(event: PointerEvent): void {
    if (!this.isDragging) {
      return;
    }
    const zone = event.currentTarget as HTMLElement;
    if (zone.hasPointerCapture(event.pointerId)) {
      zone.releasePointerCapture(event.pointerId);
    }
    this.isDragging = false;

    // Dismiss on a long drag OR a quick flick -- a short, fast swipe should
    // close the sheet even though it did not travel far.
    const elapsedMs = Math.max(performance.now() - this.dragStartTime, 1);
    const isFlick = this.sheetOffsetPx > 40 && this.sheetOffsetPx / elapsedMs > 0.5;
    const isFarEnough = this.sheetOffsetPx > this.sheetHeightPx * 0.25;
    if (!isFlick && !isFarEnough) {
      // Snap back (the CSS transition animates this).
      this.sheetOffsetPx = 0;
      return;
    }
    // Slide the rest of the way off-screen first, THEN tell the parent to
    // remove the component -- removing it immediately would cut the animation.
    this.sheetOffsetPx = this.sheetHeightPx;
    this.closeTimer = setTimeout(() => this.dismissed.emit(), 220);
  }

  ngOnDestroy(): void {
    if (this.closeTimer !== null) {
      clearTimeout(this.closeTimer);
    }
  }

  // Listens on document, not the overlay div: a non-focusable div never
  // receives key events, so a template-level (keydown.escape) would silently
  // do nothing.
  @HostListener('document:keydown.escape')
  dismiss(): void {
    this.dismissed.emit();
  }
}
