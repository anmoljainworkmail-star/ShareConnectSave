import { AfterViewInit, Component, ElementRef, NgZone, OnDestroy } from '@angular/core';

// Shared UI (SharedModule, per CLAUDE.md's Angular Rules): the app's
// FINALIZED ambient background -- a static "classic atlas map" illustration
// (ocean, three landmasses with elevation/contour shading, a plane on a fixed
// curve, a car looping one landmass's own contour ring, a boat crossing open
// water, a compass rose, decorative lat/long labels) dropped into every
// screen that wants the same finished background (Login, OTP, Profile Setup,
// Radar) instead of each screen carrying its own copy.
// Approved design: https://claude.ai/artifact/Fa8U6UyKV2daPnf18c93NV
// ("Option 2: Classic atlas map"). Single Responsibility (S): this
// component only knows how to render and animate the background; it has no
// idea what screen or what real content is stacked on top of it.
//
// This REPLACES an earlier, interactive version (drifting orbs/star field +
// a mouse-reactive map pin) -- that pointer-tracking logic is gone. The one
// thing the class does now is drive the four trains, because:
//   - two "wanderers" take a path that must be DIFFERENT on every pass, which
//     a fixed CSS @keyframes block cannot express, and
//   - two "rail" trains hug the edge of the scene, whose position depends on
//     real pixel sizes (decor size, train size) that CSS percentages cannot
//     express.
// Because ONE class owns all four, it can also guarantee they never overlap --
// see the "collision avoidance" notes on Track/collides() below.
//
// Usage: drop <app-ambient-background></app-ambient-background> as the
// FIRST child of any page wrapper that is itself `position: relative` with
// `overflow: hidden` (every current screen's page wrapper already is, for
// other reasons -- clipping the decor layer and centering the content on
// top of it). This component's own :host fills that wrapper AND paints the
// page's full background itself -- callers no longer declare their own page
// background (see radar-shared.css/auth-shared.css's own comments on why
// theirs was removed) -- and sits behind everything else purely through DOM
// order + z-index, no wrapper-specific markup required.

interface Point {
  x: number;
  y: number;
}

// A point on the rail loop also carries the exact heading there, so the train
// turns smoothly through corners instead of heading being re-derived (and
// slightly blurred) from neighbouring samples.
interface RailPoint extends Point {
  heading: number;
}

interface Bounds {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
}

// Collision avoidance (Strategy-free, plain geometry): every moving train
// publishes its future route as a Track -- "where will I be at time T?". A new
// route is only accepted if, sampled along its whole duration, it never comes
// near any other train's Track at the same moment. Deterministic routes (the
// rail loops) make that prediction exact; random routes are checked once, when
// they are generated, and then stay fixed, so the prediction stays exact too.
interface Track {
  path: Point[];
  // cumulative[i] = distance travelled along `path` to reach path[i].
  cumulative: number[];
  total: number;
  durationMs: number;
  // performance.now() at which progress was 0 (for a loop, shifted back by its
  // phase so two trains on the same loop sit at different points).
  originMs: number;
  loop: boolean;
}

type Side = 'left' | 'right' | 'top' | 'bottom';

const OPPOSITE: Record<Side, Side> = { left: 'right', right: 'left', top: 'bottom', bottom: 'top' };
const ADJACENT: Record<Side, Side[]> = {
  left: ['top', 'bottom'],
  right: ['top', 'bottom'],
  top: ['left', 'right'],
  bottom: ['left', 'right']
};
const SIDES: Side[] = ['left', 'right', 'top', 'bottom'];

// Spline samples per waypoint-to-waypoint segment. Enough that the heading
// changes in small steps (a smooth curve), few enough that the keyframe list
// stays tiny.
const SAMPLES_PER_SEGMENT = 14;
// Train ground speed, px/second. Duration is derived from path length so a
// long zigzag and a short pass travel at the same pace instead of the short
// one looking like it is sprinting.
const MIN_SPEED_PX_S = 45;
const MAX_SPEED_PX_S = 75;
// Rail trains all move at ONE fixed speed. Same speed + same direction + same
// lane is what makes two rail trains provably unable to catch each other up.
const RAIL_SPEED_PX_S = 60;
// Arc samples per rounded corner of the rail loop.
const RAIL_ARC_STEPS = 10;
// Distance from the train's centre line to the bottom of its wheels, as a
// fraction of the train element's height. Measured from the artwork: viewBox
// y runs -16..12 (28 units) with the centre at -2, and the wheels bottom out
// at y=11, so 13/28.
const WHEEL_DROP_RATIO = 13 / 28;
// Gap between the wheels and the very edge of the scene, px.
const RAIL_EDGE_GAP_PX = 3;
// Two trains must keep their centres at least this fraction of one train
// element's width apart. The drawn train is ~0.84 of its element width, so
// 0.9 leaves a clear margin whatever angle either one is at.
const MIN_SEPARATION_RATIO = 0.9;
// How finely a candidate route is tested against the other trains. Trains move
// at most ~75px/s, so a 250ms step is ~19px -- far below the separation
// distance, so a collision cannot slip between two samples.
const COLLISION_STEP_MS = 250;
// How many random routes to try before giving up and resting a moment.
const MAX_ROUTE_ATTEMPTS = 8;

// ---- Play (touch / click) tuning ----
// A press that moves less than this is a tap (scare); more is a drag (pick up).
const DRAG_START_PX = 6;
// A tap/click within this distance of a train's centre scares it.
const SCARE_RADIUS_PX = 170;
// Forgiveness around a train's box when deciding "did the finger land on it".
const GRAB_PAD_PX = 8;
// Controls the user can legitimately tap: never hijacked by the trains.
const CONTROL_SELECTOR = 'button, a, input, textarea, select, label, [role="button"], [contenteditable="true"]';
// Read by PullToRefreshComponent so a train drag is never mistaken for a pull.
const TRAIN_DRAG_FLAG = 'trainDrag';
// Drop-shadow while held, so the train looks lifted off the map.
const LIFT_FILTER = 'drop-shadow(0 8px 8px rgba(0, 0, 0, 0.35))';
const LIFT_SCALE = 1.1;

// Physics (units: px, seconds). Tuned by feel: gentle accelerations, no snaps.
// Scared wanderer: top speed, and how quickly velocity eases toward it.
const FLEE_SPEED_PX_S = 380;
const FLEE_RESPONSE_PER_S = 4.5;
// Released wanderer: how slowly it decays from a throw back to cruising pace.
const EXIT_RESPONSE_PER_S = 1.6;
// A release faster than this counts as a throw and sets the exit direction.
const FLING_MIN_SPEED_PX_S = 140;
// Held train: damped spring to the finger. damping = 2 * sqrt(stiffness) is
// critical damping -- quickest approach with no wobble.
const FOLLOW_STIFFNESS = 70;
const FOLLOW_DAMPING = 2 * Math.sqrt(FOLLOW_STIFFNESS);
// How fast the "where I grabbed it" offset melts so the train centres on the finger.
const OFFSET_DECAY_PER_S = 1.5;
// Rail train returning to its loop slot: a softer spring (slow, graceful).
const HOME_STIFFNESS = 25;
const HOME_DAMPING = 2 * Math.sqrt(HOME_STIFFNESS);
// Initial shove given to a scared rail train, away from the tap.
const RAIL_SHOVE_PX_S = 400;
// Safety cap on a held train's speed.
const MAX_SPEED_PX_S_HELD = 1600;
// Heading smoothing: exponential approach, with a hard cap on turn rate.
const HEADING_RESPONSE_PER_S = 8;
const MAX_TURN_DEG_S = 300;
// Below this speed the heading is left alone (direction is meaningless at rest).
const HEADING_MIN_SPEED_PX_S = 20;
// Longest physics step; protects against a huge dt after a tab was in the background.
const MAX_STEP_S = 1 / 30;

type SimMode = 'drag' | 'flee' | 'exit' | 'home';

// One touched train, moved by the physics loop instead of a keyframe animation.
interface Sim {
  train: HTMLElement;
  isRail: boolean;
  mode: SimMode;
  // Centre, in the train's own (decor) coordinate space, and velocity.
  x: number;
  y: number;
  vx: number;
  vy: number;
  // Direction of travel in degrees, unwrapped (see stepSim).
  heading: number;
  scale: number;
  // Unit direction used by flee / exit, and the pace exit settles to.
  dirX: number;
  dirY: number;
  cruise: number;
  // Where the train was grabbed relative to the finger (fades to zero).
  offX: number;
  offY: number;
}

interface DragState {
  train: HTMLElement;
  pointerId: number;
  startX: number;
  startY: number;
  // Latest pointer position; the physics loop reads it every frame.
  lastX: number;
  lastY: number;
  active: boolean;
}

interface RailPlayback {
  keyframes: Keyframe[];
  durationMs: number;
}

const rand = (min: number, max: number): number => min + Math.random() * (max - min);
const pick = <T>(items: readonly T[]): T => items[Math.floor(Math.random() * items.length)];

@Component({
  selector: 'app-ambient-background',
  templateUrl: './ambient-background.component.html',
  styleUrl: './ambient-background.component.css',
  // Angular 19 defaults new components to standalone: true. This project is
  // module-based only (no standalone components, per project-wide Angular
  // rule), so that default must be overridden explicitly on every
  // hand-written component.
  standalone: false
})
export class AmbientBackgroundComponent implements AfterViewInit, OnDestroy {
  private readonly animations = new Set<Animation>();
  private readonly railAnimations: Animation[] = [];
  private readonly timers = new Set<ReturnType<typeof setTimeout>>();
  // Every train currently on screen, and where it will be over time.
  private readonly tracks = new Map<HTMLElement, Track>();
  private resizeObserver?: ResizeObserver;
  private railLayoutKey = '';
  private destroyed = false;

  // Play state. `current` / `railAnimationByTrain` let a pointer handler find
  // the animation that is moving a given train, so it can pause or replace it.
  private readonly current = new Map<HTMLElement, Animation>();
  private readonly railAnimationByTrain = new Map<HTMLElement, Animation>();
  private readonly sims = new Map<HTMLElement, Sim>();
  private railPlayback: RailPlayback | null = null;
  private simFrameId: number | null = null;
  private simLastMs = 0;
  private drag: DragState | null = null;
  private teardownPointerPlay: () => void = () => undefined;

  constructor(
    private readonly host: ElementRef<HTMLElement>,
    private readonly ngZone: NgZone
  ) {}

  ngAfterViewInit(): void {
    // Guard clauses: honour the OS "reduce motion" setting, and bail where the
    // Web Animations API is missing (e.g. a jsdom unit-test environment) --
    // the trains simply stay hidden, nothing else on the page depends on them.
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      return;
    }
    const root = this.host.nativeElement;
    const wanderers = Array.from(root.querySelectorAll<HTMLElement>('.train-sticker.wanderer'));
    const rails = Array.from(root.querySelectorAll<HTMLElement>('.train-sticker.rail'));
    const sample = wanderers[0] ?? rails[0];
    if (!sample || typeof sample.animate !== 'function') {
      return;
    }

    // Outside Angular's zone on purpose: every Animation event/timer below
    // would otherwise trigger a full change-detection pass for an animation
    // that never touches any bound template state. Leaving the zone is the
    // standard way to keep a purely visual loop from taxing the rest of the app.
    this.ngZone.runOutsideAngular(() => {
      // Rails first: wanderers check their routes against the rail trains'
      // tracks, so those must already be registered.
      if (rails.length > 0) {
        this.layoutRails(rails);
        this.watchRailGeometry(rails);
      }
      wanderers.forEach((train, index) => this.schedule(train, 600 + index * rand(2200, 4200)));
      this.setupPointerPlay();
    });
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.teardownPointerPlay();
    this.abortDrag();
    this.current.clear();
    this.railAnimationByTrain.clear();
    this.sims.clear();
    this.resizeObserver?.disconnect();
    this.timers.forEach(timer => clearTimeout(timer));
    this.timers.clear();
    this.animations.forEach(animation => animation.cancel());
    this.animations.clear();
    this.railAnimations.forEach(animation => animation.cancel());
    this.railAnimations.length = 0;
    this.tracks.clear();
  }

  private later(callback: () => void, delayMs: number): void {
    const timer = setTimeout(() => {
      this.timers.delete(timer);
      callback();
    }, delayMs);
    this.timers.add(timer);
  }

  private schedule(train: HTMLElement, delayMs: number): void {
    this.later(() => this.run(train), delayMs);
  }

  // ---- Rail trains -------------------------------------------------------

  // Builds (or rebuilds, after a resize) the edge loop and starts both rail
  // trains on it. Both ride the SAME loop, in the SAME direction, at the SAME
  // speed, half a lap apart. Two trains moving identically can never close the
  // gap between them -- that is a proof, not a probability, which is why this
  // is preferred over the earlier clockwise + counter-clockwise pair: opposite
  // directions on one lane have to meet head-on twice per lap.
  private layoutRails(rails: HTMLElement[]): void {
    if (this.destroyed) {
      return;
    }
    const decor = rails[0].offsetParent as HTMLElement | null;
    // Layout not ready yet (no size) -- try again shortly.
    if (!decor || decor.offsetWidth === 0 || decor.offsetHeight === 0) {
      this.later(() => this.layoutRails(rails), 500);
      return;
    }
    // Skip a rebuild when nothing that affects the loop has changed (the
    // ResizeObserver fires once straight away on observe()).
    const key = `${decor.offsetWidth}x${decor.offsetHeight}:${rails[0].offsetWidth}x${rails[0].offsetHeight}`;
    if (key === this.railLayoutKey) {
      return;
    }
    this.railLayoutKey = key;

    // The loop is being rebuilt under any rail train the user has hold of or
    // that is easing back to its slot: let go of them, the new loop takes over.
    for (const [train, sim] of Array.from(this.sims)) {
      if (sim.isRail) {
        if (this.drag?.train === train) {
          this.clearDragState();
        }
        this.dropSim(train);
      }
    }
    this.railAnimations.forEach(animation => animation.cancel());
    this.railAnimations.length = 0;
    this.railAnimationByTrain.clear();

    const trainWidth = rails[0].offsetWidth;
    const trainHeight = rails[0].offsetHeight;
    const loop = this.buildRailLoop(decor.offsetWidth, decor.offsetHeight, trainWidth, trainHeight);
    const cumulative = this.cumulativeLengths(loop);
    const total = cumulative[cumulative.length - 1];
    const durationMs = (total / RAIL_SPEED_PX_S) * 1000;
    const keyframes = loop.map((point, index) => ({
      offset: cumulative[index] / total,
      // +180 for the same reason as toKeyframes(): the artwork's nose faces -x.
      transform: this.transformFor(point, point.heading + 180)
    }));

    // Kept so a rail train that was touched can be put back on this same loop.
    this.railPlayback = { keyframes, durationMs };

    const now = performance.now();
    rails.forEach((train, index) => {
      const phase = index / rails.length;
      const animation = train.animate(keyframes, {
        duration: durationMs,
        easing: 'linear',
        iterations: Infinity
      });
      // Start part-way round the lap so the trains are evenly spaced.
      animation.currentTime = phase * durationMs;
      this.railAnimations.push(animation);
      this.railAnimationByTrain.set(train, animation);
      train.style.visibility = 'visible';
      this.tracks.set(train, {
        path: loop,
        cumulative,
        total,
        durationMs,
        originMs: now - phase * durationMs,
        loop: true
      });
    });
  }

  // The rail geometry depends on real pixel sizes, so it must be rebuilt when
  // the scene or the train changes size (rotation, window resize, the 768px
  // breakpoint that enlarges the trains). Debounced: a drag-resize fires many
  // events and each rebuild restarts the animations.
  private watchRailGeometry(rails: HTMLElement[]): void {
    if (typeof ResizeObserver === 'undefined') {
      return;
    }
    let pending: ReturnType<typeof setTimeout> | undefined;
    this.resizeObserver = new ResizeObserver(() => {
      if (pending) {
        clearTimeout(pending);
        this.timers.delete(pending);
      }
      pending = setTimeout(() => {
        pending = undefined;
        this.layoutRails(rails);
      }, 150);
      this.timers.add(pending);
    });
    const decor = rails[0].offsetParent;
    if (decor) {
      this.resizeObserver.observe(decor);
    }
    this.resizeObserver.observe(rails[0]);
  }

  // A clockwise rounded rectangle whose sides sit just inside the edge of the
  // scene. The train's wheels are what should touch the edge, so the CENTRE
  // line is inset by (centre-to-wheel distance + a small gap). Clockwise puts
  // each train's wheels on the OUTER side of the loop all the way round
  // (heading + 180 rotates the artwork's "down" to point outward), so it reads
  // as riding the rim, steam rising inward.
  //
  // Corners are arcs, not instant 90 degree snaps: a rigid ~100px train
  // pivoting on the spot at a corner would swing its ends past the edge. The
  // radius is large enough that a tangent-aligned train stays inside it.
  private buildRailLoop(width: number, height: number, trainWidth: number, trainHeight: number): RailPoint[] {
    const inset = trainHeight * WHEEL_DROP_RATIO + RAIL_EDGE_GAP_PX;
    const left = inset;
    const right = width - inset;
    const top = inset;
    const bottom = height - inset;
    const radius = Math.max(0, Math.min(trainWidth * 0.8, (right - left) / 2, (bottom - top) / 2));

    // Corner arc centres and the angle (degrees, screen coordinates, y down)
    // each arc starts at. Increasing angle = clockwise on screen.
    const corners = [
      { cx: right - radius, cy: top + radius, from: -90 },
      { cx: right - radius, cy: bottom - radius, from: 0 },
      { cx: left + radius, cy: bottom - radius, from: 90 },
      { cx: left + radius, cy: top + radius, from: 180 }
    ];

    // Start mid-way along the top side, just after the top-left corner. The
    // straight sides between arcs need no extra samples: keyframes interpolate
    // linearly, and the heading is the same at both ends of a straight.
    const points: RailPoint[] = [{ x: left + radius, y: top, heading: 0 }];
    for (const corner of corners) {
      for (let step = 0; step <= RAIL_ARC_STEPS; step++) {
        const angle = corner.from + (90 * step) / RAIL_ARC_STEPS;
        const radians = (angle * Math.PI) / 180;
        points.push({
          x: corner.cx + radius * Math.cos(radians),
          y: corner.cy + radius * Math.sin(radians),
          // Travelling clockwise, the tangent heading is 90 degrees ahead of
          // the position angle.
          heading: angle + 90
        });
      }
    }
    return points;
  }

  // ---- Wanderer trains ---------------------------------------------------

  // One pass of one train: build a fresh random path, play it once, then
  // schedule the next (different) pass. Regenerating per pass -- instead of
  // looping one fixed keyframe set -- is what makes the movement
  // unpredictable.
  private run(train: HTMLElement): void {
    if (this.destroyed) {
      return;
    }

    const bounds = this.visibleBounds(train);
    // Layout not ready yet (host has no size) -- try again shortly.
    if (!bounds) {
      this.schedule(train, 500);
      return;
    }

    const trainLength = train.offsetWidth || 100;
    const now = performance.now();

    // Rejection sampling: draw a random route, test it, throw it away if it
    // would hit another train, and draw again. Cheaper and far simpler than
    // steering around other trains while moving, and it keeps every route a
    // smooth precomputed curve. Without this test, a wanderer crossing the
    // edge on the way in could land on top of a rail train.
    for (let attempt = 0; attempt < MAX_ROUTE_ATTEMPTS; attempt++) {
      const path = this.buildPath(bounds, trainLength);
      const cumulative = this.cumulativeLengths(path);
      const length = cumulative[cumulative.length - 1];
      const durationMs = (length / rand(MIN_SPEED_PX_S, MAX_SPEED_PX_S)) * 1000;
      const track: Track = { path, cumulative, total: length, durationMs, originMs: now, loop: false };
      if (this.collides(train, track, trainLength * MIN_SEPARATION_RATIO)) {
        continue;
      }
      this.play(train, track);
      return;
    }
    // Every candidate clashed -- rest briefly and try again with fresh dice.
    this.schedule(train, 1500);
  }

  private play(train: HTMLElement, track: Track): void {
    train.style.visibility = 'visible';
    this.tracks.set(train, track);
    const animation = train.animate(this.toKeyframes(track.path, track.total), {
      duration: track.durationMs,
      easing: 'linear',
      fill: 'both'
    });
    this.animations.add(animation);
    this.current.set(train, animation);

    animation.onfinish = () => {
      this.animations.delete(animation);
      this.current.delete(train);
      this.tracks.delete(train);
      animation.cancel();
      train.style.visibility = 'hidden';
      // A random rest off-screen before the next entrance, so the gap between
      // trains is as unpredictable as their route.
      this.schedule(train, rand(800, 4500));
    };
  }

  // ---- Play: scare and pick up ---------------------------------------------

  // The Problem: the trains are a fixed performance -- they follow routes the
  // component generated and ignore the person looking at them. A first attempt
  // at reacting used pre-computed keyframe paths and an instant jump in heading,
  // which reads as a twitch, not as a living thing.
  //
  // Why it matters: motion looks natural when it has INERTIA. Real things speed
  // up and slow down gradually and turn in arcs; they never change direction or
  // speed in a single frame. A keyframe path cannot express that, because it is
  // decided up front and cannot react to a finger that is still moving.
  //
  // How this solves it: once a train is touched it leaves the keyframe animation
  // and is moved by a tiny physics step every frame (position, velocity,
  // heading). Forces change the VELOCITY, never the position directly, so every
  // reaction is a smooth acceleration:
  //  - SCARE: the train is pushed to run away from the tap. Its velocity eases
  //    toward "fast, away" and its heading turns at a limited rate, so it
  //    swerves round in an arc and speeds up -- no snap.
  //  - DRAG: the train is tied to the finger by a damped spring. It lags behind
  //    a quick move, overshoots slightly and settles, like something being
  //    towed rather than glued to the cursor.
  //  - RELEASE: it keeps the momentum it had (a flick carries it on, slowing to
  //    a cruising pace) and leaves the way it was thrown. A rail train is
  //    pulled by a spring back onto its loop and handed back to the animation.
  //
  // The "damped spring" is the standard way to make something chase a moving
  // target without oscillating forever: acceleration = stiffness * distance
  // - damping * velocity. Damping set to 2 * sqrt(stiffness) is "critical":
  // the fastest return with no wobble.
  //
  // Why document-level listeners instead of making the trains clickable: the
  // host is `pointer-events: none` ON PURPOSE so the background can never steal
  // a tap from real content. We keep that guarantee: a train is only grabbed
  // when the tap landed on empty space (the page wrapper itself), and taps on
  // buttons/links/inputs are never touched.
  private setupPointerPlay(): void {
    const onDown = (event: PointerEvent): void => this.onPointerDown(event);
    const onMove = (event: PointerEvent): void => this.onPointerMove(event);
    const onUp = (event: PointerEvent): void => this.endDrag(event, false);
    const onCancel = (event: PointerEvent): void => this.endDrag(event, true);
    // Why this exists: on a phone the browser decides at the START of a touch
    // whether it will pan/scroll the page. If it does, it sends `pointercancel`
    // and the drag dies before it begins -- a mouse has no such gesture, which is
    // why this worked on a laptop but not on a phone. Calling preventDefault on
    // touchstart (non-passive) tells the browser "this touch is mine", but ONLY
    // when the finger landed on a train over empty space, so scrolling and every
    // control elsewhere are untouched.
    const onTouchStart = (event: TouchEvent): void => {
      if (event.touches.length !== 1 || !event.cancelable) {
        return;
      }
      if (this.isControlTarget(event.target)) {
        return;
      }
      const touch = event.touches[0];
      if (this.trainAt(touch.clientX, touch.clientY) && this.isEmptySpaceTarget(event.target)) {
        event.preventDefault();
      }
    };
    // Belt and braces: while a drag is active the page must not scroll either.
    const onTouchMove = (event: TouchEvent): void => {
      if (this.drag?.active && event.cancelable) {
        event.preventDefault();
      }
    };

    document.addEventListener('touchstart', onTouchStart, { passive: false, capture: true });
    document.addEventListener('pointerdown', onDown, true);
    document.addEventListener('pointermove', onMove, true);
    document.addEventListener('pointerup', onUp, true);
    document.addEventListener('pointercancel', onCancel, true);
    document.addEventListener('touchmove', onTouchMove, { passive: false });
    this.teardownPointerPlay = () => {
      document.removeEventListener('touchstart', onTouchStart, true);
      document.removeEventListener('pointerdown', onDown, true);
      document.removeEventListener('pointermove', onMove, true);
      document.removeEventListener('pointerup', onUp, true);
      document.removeEventListener('pointercancel', onCancel, true);
      document.removeEventListener('touchmove', onTouchMove);
      this.stopSimLoop();
      for (const train of Array.from(this.sims.keys())) {
        this.dropSim(train);
      }
    };
  }

  private allTrains(): HTMLElement[] {
    return Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>('.train-sticker')).filter(
      train => train.style.visibility === 'visible'
    );
  }

  private isControlTarget(target: EventTarget | null): boolean {
    return target instanceof Element && target.closest(CONTROL_SELECTOR) !== null;
  }

  // "Empty space": what the finger touched is the page wrapper, the background,
  // or only transparent layout boxes between them -- nothing the user can SEE
  // or use sits on top of the train.
  //
  // Why not just "target is the wrapper": on a phone a screen's layout blocks
  // (the login hero, a full-height column) stretch across the whole screen, so
  // the touch lands on one of THOSE even where nothing is drawn. Requiring the
  // exact wrapper made trains ungrabbable on phones while working on a wide
  // laptop layout, where most of the page is bare wrapper.
  //
  // So walk from the touched element up to the wrapper and require every layer
  // on the way to be inert (see isInertLayer). A card, text, image or glass
  // panel anywhere in that chain means the train is behind something real.
  private isEmptySpaceTarget(target: EventTarget | null): boolean {
    if (!(target instanceof Element)) {
      return false;
    }
    const root = this.host.nativeElement;
    const wrapper = root.parentElement;
    if (root.contains(target) || target === wrapper) {
      return true;
    }
    let node: Element | null = target;
    while (node && node !== wrapper) {
      if (!this.isInertLayer(node)) {
        return false;
      }
      node = node.parentElement;
    }
    return node === wrapper;
  }

  // A layout box that paints nothing and says nothing: no own text, not a
  // replaced element (img/svg/canvas/video), and no visible background or blur.
  private isInertLayer(element: Element): boolean {
    if (
      element instanceof SVGElement ||
      element instanceof HTMLImageElement ||
      element instanceof HTMLCanvasElement ||
      element instanceof HTMLVideoElement
    ) {
      return false;
    }
    for (const child of Array.from(element.childNodes)) {
      if (child.nodeType === Node.TEXT_NODE && (child.textContent ?? '').trim().length > 0) {
        return false;
      }
    }
    const style = getComputedStyle(element);
    const blur = style.backdropFilter || style.getPropertyValue('-webkit-backdrop-filter');
    if (style.backgroundImage !== 'none' || (blur !== '' && blur !== 'none')) {
      return false;
    }
    const colour = /rgba?(([^)]+))/.exec(style.backgroundColor);
    if (!colour) {
      return true;
    }
    const parts = colour[1].split(/[ ,/]+/).filter(part => part.length > 0);
    const alpha = parts.length >= 4 ? Number.parseFloat(parts[3]) : 1;
    return alpha < 0.05;
  }

  private trainAt(clientX: number, clientY: number): HTMLElement | null {
    let best: HTMLElement | null = null;
    let bestDistance = Infinity;
    for (const train of this.allTrains()) {
      const rect = train.getBoundingClientRect();
      const inside =
        clientX >= rect.left - GRAB_PAD_PX &&
        clientX <= rect.right + GRAB_PAD_PX &&
        clientY >= rect.top - GRAB_PAD_PX &&
        clientY <= rect.bottom + GRAB_PAD_PX;
      if (!inside) {
        continue;
      }
      const distance = Math.hypot(clientX - (rect.left + rect.width / 2), clientY - (rect.top + rect.height / 2));
      if (distance < bestDistance) {
        best = train;
        bestDistance = distance;
      }
    }
    return best;
  }

  private onPointerDown(event: PointerEvent): void {
    // Guard clauses: one finger / the main mouse button, nothing else in
    // progress, and never hijack a real control.
    if (!event.isPrimary || (event.pointerType === 'mouse' && event.button !== 0) || this.drag) {
      return;
    }
    if (this.isControlTarget(event.target)) {
      return;
    }

    const hit = this.trainAt(event.clientX, event.clientY);
    const grabbed = hit && this.isEmptySpaceTarget(event.target) ? hit : null;
    if (!grabbed) {
      this.scareNear(event.clientX, event.clientY);
      return;
    }

    // Pressed ON a train: wait to see whether this becomes a drag (moves) or a
    // tap (does not). preventDefault stops a mouse drag from selecting text.
    event.preventDefault();
    this.drag = {
      train: grabbed,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      lastX: event.clientX,
      lastY: event.clientY,
      active: false
    };
    document.documentElement.dataset[TRAIN_DRAG_FLAG] = '1';
  }

  private onPointerMove(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag) {
      this.updateHoverCursor(event);
      return;
    }
    if (event.pointerId !== drag.pointerId) {
      return;
    }
    // The physics loop reads these every frame; nothing is moved from here.
    drag.lastX = event.clientX;
    drag.lastY = event.clientY;
    if (drag.active) {
      return;
    }
    const moved = Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY);
    if (moved >= DRAG_START_PX) {
      this.beginDrag(drag);
    }
  }

  // Mouse only: show a grab hand while hovering a train over empty space.
  private updateHoverCursor(event: PointerEvent): void {
    if (event.pointerType !== 'mouse') {
      return;
    }
    const wrapper = this.host.nativeElement.parentElement;
    if (!wrapper) {
      return;
    }
    const hovering = this.trainAt(event.clientX, event.clientY) !== null && this.isEmptySpaceTarget(event.target);
    wrapper.style.cursor = hovering ? 'grab' : '';
  }

  private beginDrag(drag: DragState): void {
    const sim = this.sims.get(drag.train) ?? this.takeOver(drag.train);
    // Nothing to hold (the train's pass just ended).
    if (!sim) {
      this.clearDragState();
      return;
    }
    const decor = drag.train.offsetParent as HTMLElement | null;
    if (!decor) {
      this.clearDragState();
      return;
    }
    const decorRect = decor.getBoundingClientRect();
    // Keep the spot you grabbed under your finger; the offset melts away over a
    // moment (see stepSim) so the train ends up centred on the finger instead
    // of jumping there.
    sim.offX = sim.x - (drag.startX - decorRect.left);
    sim.offY = sim.y - (drag.startY - decorRect.top);
    sim.mode = 'drag';
    drag.train.style.filter = LIFT_FILTER;
    drag.active = true;
    const wrapper = this.host.nativeElement.parentElement;
    if (wrapper) {
      wrapper.style.cursor = 'grabbing';
    }
  }

  private endDrag(event: PointerEvent, cancelled: boolean): void {
    const drag = this.drag;
    if (!drag || event.pointerId !== drag.pointerId) {
      return;
    }
    this.clearDragState();

    // Released without moving: it was a tap on the train -- scare it.
    if (!drag.active) {
      if (!cancelled) {
        this.scareNear(event.clientX, event.clientY);
      }
      return;
    }
    const sim = this.sims.get(drag.train);
    if (sim) {
      this.releaseSim(sim);
    }
  }

  private clearDragState(): void {
    this.drag = null;
    delete document.documentElement.dataset[TRAIN_DRAG_FLAG];
    const wrapper = this.host.nativeElement.parentElement;
    if (wrapper) {
      wrapper.style.cursor = '';
    }
  }

  // The thing being held disappeared under the finger (layout rebuild): let go.
  private abortDrag(): void {
    const drag = this.drag;
    if (!drag) {
      return;
    }
    this.clearDragState();
    const sim = this.sims.get(drag.train);
    if (sim && sim.mode === 'drag') {
      this.releaseSim(sim);
    }
  }

  // Let go of a held train. It keeps its current velocity, so a flick carries on.
  private releaseSim(sim: Sim): void {
    sim.train.style.filter = '';
    if (sim.isRail) {
      sim.mode = 'home';
      return;
    }
    // A wanderer leaves the way it was thrown; if barely moving, toward a
    // random edge. Either way it then drifts at an ordinary cruising pace.
    const speed = Math.hypot(sim.vx, sim.vy);
    if (speed >= FLING_MIN_SPEED_PX_S) {
      sim.dirX = sim.vx / speed;
      sim.dirY = sim.vy / speed;
    } else {
      const angle = Math.random() * Math.PI * 2;
      sim.dirX = Math.cos(angle);
      sim.dirY = Math.sin(angle);
    }
    sim.cruise = rand(MIN_SPEED_PX_S, MAX_SPEED_PX_S);
    sim.mode = 'exit';
  }

  // Scare every visible train within reach of the tap.
  private scareNear(clientX: number, clientY: number): void {
    for (const train of this.allTrains()) {
      const rect = train.getBoundingClientRect();
      const distance = Math.hypot(rect.left + rect.width / 2 - clientX, rect.top + rect.height / 2 - clientY);
      if (distance <= SCARE_RADIUS_PX) {
        this.scareTrain(train, clientX, clientY);
      }
    }
  }

  private scareTrain(train: HTMLElement, tapClientX: number, tapClientY: number): void {
    const sim = this.sims.get(train) ?? this.takeOver(train);
    // Guard clauses: not on a route right now, or being held by a finger.
    if (!sim || sim.mode === 'drag') {
      return;
    }
    const decor = train.offsetParent as HTMLElement | null;
    if (!decor) {
      return;
    }
    const decorRect = decor.getBoundingClientRect();
    let awayX = sim.x - (tapClientX - decorRect.left);
    let awayY = sim.y - (tapClientY - decorRect.top);
    const length = Math.hypot(awayX, awayY);
    if (length < 1) {
      const angle = Math.random() * Math.PI * 2;
      awayX = Math.cos(angle);
      awayY = Math.sin(angle);
    } else {
      awayX /= length;
      awayY /= length;
    }

    if (sim.isRail) {
      // Rail trains are bound to the edge loop, so they cannot run off: a shove
      // away from the tap, then the home spring eases them back onto the loop
      // (a recoil, not a twitch -- the spring makes the return gradual).
      sim.mode = 'home';
      sim.vx += awayX * RAIL_SHOVE_PX_S;
      sim.vy += awayY * RAIL_SHOVE_PX_S;
      return;
    }

    // A little sideways variation so repeated scares do not all look identical.
    const bias = rand(-0.25, 0.25);
    const cos = Math.cos(bias);
    const sin = Math.sin(bias);
    sim.dirX = awayX * cos - awayY * sin;
    sim.dirY = awayX * sin + awayY * cos;
    sim.mode = 'flee';
  }

  // Hand a train over from its keyframe animation to the physics loop, starting
  // EXACTLY where it visually is (position, heading, and velocity estimated from
  // its route) so the handover itself cannot be seen.
  private takeOver(train: HTMLElement): Sim | null {
    const isRail = train.classList.contains('rail');
    const animation = isRail ? this.railAnimationByTrain.get(train) : this.current.get(train);
    const decor = train.offsetParent as HTMLElement | null;
    if (!animation || !decor) {
      return null;
    }

    const rect = train.getBoundingClientRect();
    const decorRect = decor.getBoundingClientRect();
    // The animated transform is translate * translate * rotate, so the matrix's
    // (a, b) pair is (cos, sin) of the rotation; the artwork faces -x, so the
    // direction of travel is that angle minus 180 degrees.
    const matrix = new DOMMatrixReadOnly(getComputedStyle(train).transform);
    const heading = (Math.atan2(matrix.b, matrix.a) * 180) / Math.PI - 180;

    let vx = 0;
    let vy = 0;
    const track = this.tracks.get(train);
    if (track) {
      const now = performance.now();
      const here = this.positionAt(track, now);
      const ahead = this.positionAt(track, now + 80);
      if (here && ahead) {
        vx = (ahead.x - here.x) / 0.08;
        vy = (ahead.y - here.y) / 0.08;
      }
    }

    if (isRail) {
      // Keep the track: it is the "home" the spring pulls back to.
      animation.cancel();
      const index = this.railAnimations.indexOf(animation);
      if (index >= 0) {
        this.railAnimations.splice(index, 1);
      }
      this.railAnimationByTrain.delete(train);
    } else {
      this.cancelFlight(train);
    }

    const sim: Sim = {
      train,
      isRail,
      mode: isRail ? 'home' : 'exit',
      x: rect.left + rect.width / 2 - decorRect.left,
      y: rect.top + rect.height / 2 - decorRect.top,
      vx,
      vy,
      heading,
      scale: 1,
      dirX: 1,
      dirY: 0,
      cruise: rand(MIN_SPEED_PX_S, MAX_SPEED_PX_S),
      offX: 0,
      offY: 0
    };
    const speed = Math.hypot(vx, vy);
    if (speed > 0) {
      sim.dirX = vx / speed;
      sim.dirY = vy / speed;
    }
    this.sims.set(train, sim);
    this.startSimLoop();
    return sim;
  }

  // Stop the train's current route WITHOUT triggering its "finished" handler
  // (which would hide the train and schedule another entrance on top of ours).
  private cancelFlight(train: HTMLElement): void {
    const animation = this.current.get(train);
    if (animation) {
      animation.onfinish = null;
      animation.cancel();
      this.animations.delete(animation);
      this.current.delete(train);
    }
    this.tracks.delete(train);
  }

  // Forget a train's physics state and clear what it wrote to the element.
  private dropSim(train: HTMLElement): void {
    this.sims.delete(train);
    train.style.transform = '';
    train.style.filter = '';
  }

  // ---- Physics loop -------------------------------------------------------

  private startSimLoop(): void {
    if (this.simFrameId !== null) {
      return;
    }
    this.simLastMs = performance.now();
    this.simFrameId = requestAnimationFrame(now => this.tick(now));
  }

  private stopSimLoop(): void {
    if (this.simFrameId !== null) {
      cancelAnimationFrame(this.simFrameId);
      this.simFrameId = null;
    }
  }

  private tick(nowMs: number): void {
    this.simFrameId = null;
    if (this.destroyed) {
      return;
    }
    // Clamp the step: a backgrounded tab resumes with a huge gap, and one giant
    // step would fling the train across the screen.
    const dt = Math.min(Math.max((nowMs - this.simLastMs) / 1000, 0), MAX_STEP_S);
    this.simLastMs = nowMs;

    for (const sim of Array.from(this.sims.values())) {
      this.stepSim(sim, dt, nowMs);
    }
    if (this.sims.size > 0) {
      this.simFrameId = requestAnimationFrame(next => this.tick(next));
    }
  }

  private stepSim(sim: Sim, dt: number, nowMs: number): void {
    const train = sim.train;
    const decor = train.offsetParent as HTMLElement | null;
    const bounds = this.visibleBounds(train);
    // Layout vanished under us -- put the train back to a sane state.
    if (!decor || !bounds) {
      this.finishOffscreen(sim);
      return;
    }

    // Where the rail train's loop slot is right now (and how it is moving).
    let homeX = 0;
    let homeY = 0;
    let homeVx = 0;
    let homeVy = 0;
    let railTrack: Track | undefined;
    if (sim.isRail) {
      railTrack = this.tracks.get(train);
      const here = railTrack ? this.positionAt(railTrack, nowMs) : null;
      const ahead = railTrack ? this.positionAt(railTrack, nowMs + 50) : null;
      if (!railTrack || !here || !ahead) {
        this.dropSim(train);
        return;
      }
      homeX = here.x;
      homeY = here.y;
      homeVx = (ahead.x - here.x) / 0.05;
      homeVy = (ahead.y - here.y) / 0.05;
    }

    let ax = 0;
    let ay = 0;
    switch (sim.mode) {
      case 'drag': {
        const drag = this.drag;
        if (!drag) {
          this.releaseSim(sim);
          return;
        }
        const decorRect = decor.getBoundingClientRect();
        sim.offX *= Math.exp(-dt * OFFSET_DECAY_PER_S);
        sim.offY *= Math.exp(-dt * OFFSET_DECAY_PER_S);
        // Aim for the finger (plus the fading grab offset), never off the page.
        const targetX = this.clamp(drag.lastX - decorRect.left + sim.offX, bounds.minX, bounds.maxX);
        const targetY = this.clamp(drag.lastY - decorRect.top + sim.offY, bounds.minY, bounds.maxY);
        ax = FOLLOW_STIFFNESS * (targetX - sim.x) - FOLLOW_DAMPING * sim.vx;
        ay = FOLLOW_STIFFNESS * (targetY - sim.y) - FOLLOW_DAMPING * sim.vy;
        break;
      }
      case 'flee': {
        // First-order response: velocity eases toward "fast, away", so the train
        // accelerates over a few tenths of a second instead of jumping to speed.
        ax = (sim.dirX * FLEE_SPEED_PX_S - sim.vx) * FLEE_RESPONSE_PER_S;
        ay = (sim.dirY * FLEE_SPEED_PX_S - sim.vy) * FLEE_RESPONSE_PER_S;
        break;
      }
      case 'exit': {
        // Slow response: a flicked train coasts and only gradually settles to
        // its cruising pace -- that long glide is the inertia you can feel.
        ax = (sim.dirX * sim.cruise - sim.vx) * EXIT_RESPONSE_PER_S;
        ay = (sim.dirY * sim.cruise - sim.vy) * EXIT_RESPONSE_PER_S;
        break;
      }
      case 'home': {
        // Critically damped spring toward the moving loop slot, plus its own
        // velocity as feed-forward so a train that has caught up keeps pace.
        ax = HOME_STIFFNESS * (homeX - sim.x) + HOME_DAMPING * (homeVx - sim.vx);
        ay = HOME_STIFFNESS * (homeY - sim.y) + HOME_DAMPING * (homeVy - sim.vy);
        break;
      }
    }

    // Semi-implicit Euler: update velocity first, then position with the NEW
    // velocity. More stable for springs than the naive order.
    sim.vx += ax * dt;
    sim.vy += ay * dt;
    const speed = Math.hypot(sim.vx, sim.vy);
    if (speed > MAX_SPEED_PX_S_HELD) {
      sim.vx *= MAX_SPEED_PX_S_HELD / speed;
      sim.vy *= MAX_SPEED_PX_S_HELD / speed;
    }
    sim.x += sim.vx * dt;
    sim.y += sim.vy * dt;

    // Heading: turn TOWARD the direction of travel at a limited rate. The
    // heading is an unwrapped angle (it may exceed 360) so a turn always takes
    // the short way round instead of spinning back a full circle.
    let headingTarget: number | null = null;
    const currentSpeed = Math.hypot(sim.vx, sim.vy);
    if (sim.mode === 'home' && railTrack) {
      // Near its slot a rail train lines up with the loop; far away it points
      // where it is actually going.
      const distance = Math.hypot(homeX - sim.x, homeY - sim.y);
      const weight = this.clamp((distance - 15) / 60, 0, 1);
      const dx = sim.vx * weight + homeVx * (1 - weight);
      const dy = sim.vy * weight + homeVy * (1 - weight);
      if (Math.hypot(dx, dy) > 5) {
        headingTarget = (Math.atan2(dy, dx) * 180) / Math.PI;
      }
    } else if (currentSpeed > HEADING_MIN_SPEED_PX_S) {
      headingTarget = (Math.atan2(sim.vy, sim.vx) * 180) / Math.PI;
    }
    if (headingTarget !== null) {
      const difference = ((((headingTarget - sim.heading) % 360) + 540) % 360) - 180;
      const step = difference * (1 - Math.exp(-dt * HEADING_RESPONSE_PER_S));
      const limit = MAX_TURN_DEG_S * dt;
      sim.heading += this.clamp(step, -limit, limit);
    }

    // "Lifted" while held, eased in and out rather than popping.
    const scaleTarget = sim.mode === 'drag' ? LIFT_SCALE : 1;
    sim.scale += (scaleTarget - sim.scale) * (1 - Math.exp(-dt * 14));

    train.style.transform = this.transformFor({ x: sim.x, y: sim.y }, sim.heading + 180, sim.scale);

    // Endings.
    if (sim.mode === 'flee' || sim.mode === 'exit') {
      const margin = (train.offsetWidth || 100) * 0.65 + 24;
      const gone =
        sim.x < bounds.minX - margin ||
        sim.x > bounds.maxX + margin ||
        sim.y < bounds.minY - margin ||
        sim.y > bounds.maxY + margin;
      if (gone) {
        this.finishOffscreen(sim);
      }
      return;
    }
    if (sim.mode === 'home' && railTrack) {
      const closeEnough = Math.hypot(homeX - sim.x, homeY - sim.y) < 2.5;
      const matchingSpeed = Math.hypot(homeVx - sim.vx, homeVy - sim.vy) < 12;
      if (closeEnough && matchingSpeed) {
        this.handBackToLoop(sim, railTrack, nowMs);
      }
    }
  }

  // A wanderer that has left the screen: hide it and schedule its next entrance,
  // exactly as the end of an ordinary pass does.
  private finishOffscreen(sim: Sim): void {
    const train = sim.train;
    this.dropSim(train);
    this.tracks.delete(train);
    train.style.visibility = 'hidden';
    if (!this.destroyed) {
      this.schedule(train, rand(800, 4500));
    }
  }

  // A rail train that has settled back into its slot: restart the loop
  // animation at the matching point and let it carry on.
  private handBackToLoop(sim: Sim, track: Track, nowMs: number): void {
    const playback = this.railPlayback;
    const train = sim.train;
    this.dropSim(train);
    if (!playback) {
      return;
    }
    const fraction = (((nowMs - track.originMs) / track.durationMs) % 1 + 1) % 1;
    const animation = train.animate(playback.keyframes, {
      duration: playback.durationMs,
      easing: 'linear',
      iterations: Infinity
    });
    animation.currentTime = fraction * playback.durationMs;
    this.railAnimations.push(animation);
    this.railAnimationByTrain.set(train, animation);
  }

  // ---- Collision avoidance -----------------------------------------------

  // Where will this track's train be at absolute time `nowMs`? null when the
  // train is not on its route at that moment (not started yet, or finished).
  private positionAt(track: Track, nowMs: number): Point | null {
    const progress = (nowMs - track.originMs) / track.durationMs;
    const fraction = track.loop ? ((progress % 1) + 1) % 1 : progress;
    if (fraction < 0 || fraction > 1) {
      return null;
    }
    return this.pointAtLength(track.path, track.cumulative, fraction * track.total);
  }

  // True if `candidate`, played from its own origin, ever brings this train
  // within `minSeparation` px (centre to centre) of any other train on screen.
  // Centre distance >= the train's length means the two bodies cannot overlap
  // whatever angle each is rotated to -- a deliberately conservative circle
  // test rather than exact rectangle intersection, because "never touch" is
  // the requirement and a little extra personal space is invisible here.
  private collides(self: HTMLElement, candidate: Track, minSeparation: number): boolean {
    for (let elapsed = 0; elapsed <= candidate.durationMs; elapsed += COLLISION_STEP_MS) {
      const at = candidate.originMs + elapsed;
      const mine = this.positionAt(candidate, at);
      if (!mine) {
        continue;
      }
      for (const [other, track] of this.tracks) {
        if (other === self) {
          continue;
        }
        const theirs = this.positionAt(track, at);
        if (theirs && Math.hypot(mine.x - theirs.x, mine.y - theirs.y) < minSeparation) {
          return true;
        }
      }
    }
    return false;
  }

  private pointAtLength(path: Point[], cumulative: number[], length: number): Point {
    // Binary search for the segment containing `length`.
    let low = 0;
    let high = cumulative.length - 1;
    while (high - low > 1) {
      const mid = (low + high) >> 1;
      if (cumulative[mid] <= length) {
        low = mid;
      } else {
        high = mid;
      }
    }
    const span = cumulative[high] - cumulative[low];
    const t = span === 0 ? 0 : (length - cumulative[low]) / span;
    return {
      x: path[low].x + (path[high].x - path[low].x) * t,
      y: path[low].y + (path[high].y - path[low].y) * t
    };
  }

  private cumulativeLengths(path: Point[]): number[] {
    const out: number[] = [0];
    for (let i = 1; i < path.length; i++) {
      out.push(out[i - 1] + Math.hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y));
    }
    return out;
  }

  // ---- Path generation ---------------------------------------------------

  // The area to roam, in the train's own coordinate space (the capped
  // .ambient-decor box it is positioned against). On a phone that box IS the
  // screen; on a wide desktop window the screen is bigger than the decor, so
  // the roaming bounds come from the HOST (the whole page) -- otherwise a
  // train would appear/vanish in plain view at the decor's edge instead of
  // sliding in from beyond the screen.
  private visibleBounds(train: HTMLElement): Bounds | null {
    const decor = train.offsetParent as HTMLElement | null;
    const hostRect = this.host.nativeElement.getBoundingClientRect();
    if (!decor || hostRect.width === 0 || hostRect.height === 0) {
      return null;
    }
    const decorRect = decor.getBoundingClientRect();
    return {
      minX: hostRect.left - decorRect.left,
      maxX: hostRect.right - decorRect.left,
      minY: hostRect.top - decorRect.top,
      maxY: hostRect.bottom - decorRect.top
    };
  }

  // Random entrance side -> a snaking route -> random exit side (usually the
  // opposite one, sometimes an adjacent one so it can cut a corner), with
  // 2-4 waypoints pushed alternately left/right of the straight line.
  private buildPath(bounds: Bounds, trainLength: number): Point[] {
    const width = bounds.maxX - bounds.minX;
    const height = bounds.maxY - bounds.minY;
    // Far enough outside the screen that the whole (rotated) train is hidden
    // at both ends of the path.
    const overshoot = trainLength * 0.65 + 24;

    const entrySide = pick(SIDES);
    const exitSide = Math.random() < 0.7 ? OPPOSITE[entrySide] : pick(ADJACENT[entrySide]);
    const start = this.pointOnSide(bounds, entrySide, rand(0.15, 0.85), overshoot);
    const end = this.pointOnSide(bounds, exitSide, rand(0.15, 0.85), overshoot);

    // Keeps zigzag waypoints on screen with a margin for the train's body,
    // and out of the bottom ~18% where the bottom sheet sits.
    const safe: Bounds = {
      minX: bounds.minX + width * 0.1,
      maxX: bounds.maxX - width * 0.1,
      minY: bounds.minY + height * 0.08,
      maxY: bounds.minY + height * 0.82
    };

    const dx = end.x - start.x;
    const dy = end.y - start.y;
    const span = Math.hypot(dx, dy) || 1;
    // Unit vector perpendicular to the straight start->end line.
    const nx = -dy / span;
    const ny = dx / span;
    const amplitude = Math.min(width, height) * 0.28;

    const waypointCount = 2 + Math.floor(Math.random() * 3);
    let direction = Math.random() < 0.5 ? 1 : -1;
    const points: Point[] = [start];
    for (let i = 1; i <= waypointCount; i++) {
      const along = i / (waypointCount + 1) + rand(-0.3, 0.3) / (waypointCount + 1);
      const sway = direction * amplitude * rand(0.4, 1);
      points.push({
        x: this.clamp(start.x + dx * along + nx * sway, safe.minX, safe.maxX),
        y: this.clamp(start.y + dy * along + ny * sway, safe.minY, safe.maxY)
      });
      // Alternate sides -- that alternation IS the zigzag.
      direction = -direction;
    }
    points.push(end);

    return this.smooth(points);
  }

  private pointOnSide(bounds: Bounds, side: Side, t: number, overshoot: number): Point {
    const x = bounds.minX + (bounds.maxX - bounds.minX) * t;
    const y = bounds.minY + (bounds.maxY - bounds.minY) * t;
    switch (side) {
      case 'left':
        return { x: bounds.minX - overshoot, y };
      case 'right':
        return { x: bounds.maxX + overshoot, y };
      case 'top':
        return { x, y: bounds.minY - overshoot };
      case 'bottom':
        return { x, y: bounds.maxY + overshoot };
    }
  }

  // Catmull-Rom spline through the waypoints: a curve that passes THROUGH
  // every point (unlike a Bezier, whose control points it only leans toward).
  // Turns a jagged polyline into the smooth snaking line a train would
  // actually follow, and gives a continuously changing heading instead of
  // sharp corners.
  private smooth(points: Point[]): Point[] {
    const out: Point[] = [];
    for (let i = 0; i < points.length - 1; i++) {
      const p0 = points[Math.max(i - 1, 0)];
      const p1 = points[i];
      const p2 = points[i + 1];
      const p3 = points[Math.min(i + 2, points.length - 1)];
      for (let step = 0; step < SAMPLES_PER_SEGMENT; step++) {
        const t = step / SAMPLES_PER_SEGMENT;
        const t2 = t * t;
        const t3 = t2 * t;
        out.push({
          x: 0.5 * (2 * p1.x + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3),
          y: 0.5 * (2 * p1.y + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3)
        });
      }
    }
    out.push(points[points.length - 1]);
    return out;
  }

  private toKeyframes(path: Point[], totalLength: number): Keyframe[] {
    const keyframes: Keyframe[] = [];
    let travelled = 0;
    let previousAngle = 0;
    path.forEach((point, index) => {
      if (index > 0) {
        travelled += Math.hypot(point.x - path[index - 1].x, point.y - path[index - 1].y);
      }
      // Heading from the neighbouring samples. The train artwork's nose faces
      // -x, so +180deg turns "direction of travel" into the rotation that
      // points the nose along it (same convention the old fixed keyframes
      // used: rotate(180deg) while moving right).
      const ahead = path[Math.min(index + 1, path.length - 1)];
      const behind = path[Math.max(index - 1, 0)];
      let angle = (Math.atan2(ahead.y - behind.y, ahead.x - behind.x) * 180) / Math.PI + 180;
      // Unwrap so a heading crossing the +-180 seam interpolates the short
      // way round instead of spinning the train a full turn backwards.
      if (index > 0) {
        while (angle - previousAngle > 180) {
          angle -= 360;
        }
        while (angle - previousAngle < -180) {
          angle += 360;
        }
      }
      previousAngle = angle;

      keyframes.push({
        offset: totalLength === 0 ? 0 : Math.min(travelled / totalLength, 1),
        transform: this.transformFor(point, angle)
      });
    });
    return keyframes;
  }

  // transform (not top/left) so the browser can animate on the compositor
  // without re-running layout every frame. translate(-50%, -50%) centres the
  // train ON its point instead of anchoring the point at its top-left corner.
  private transformFor(point: Point, angleDeg: number, scale = 1): string {
    const lift = scale === 1 ? '' : ` scale(${scale.toFixed(3)})`;
    return `translate(${point.x.toFixed(1)}px, ${point.y.toFixed(1)}px) translate(-50%, -50%) rotate(${angleDeg.toFixed(1)}deg)${lift}`;
  }

  private clamp(value: number, min: number, max: number): number {
    return Math.min(Math.max(value, min), max);
  }
}
