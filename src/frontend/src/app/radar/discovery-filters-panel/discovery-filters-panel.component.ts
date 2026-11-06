import { Component, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';

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
export class DiscoveryFiltersPanelComponent implements OnInit {
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

  // Listens on document, not the overlay div: a non-focusable div never
  // receives key events, so a template-level (keydown.escape) would silently
  // do nothing.
  @HostListener('document:keydown.escape')
  dismiss(): void {
    this.dismissed.emit();
  }
}
