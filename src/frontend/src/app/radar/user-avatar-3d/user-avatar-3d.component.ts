import { Component, ElementRef, Input, NgZone, OnChanges, OnDestroy, OnInit, SimpleChanges, ViewChild } from '@angular/core';
// Named imports, not `import * as THREE` -- the standard way to import
// three so esbuild's tree-shaking has a chance to drop unused parts of the
// library from this lazy chunk (RadarModule's), instead of guaranteeing the
// whole package is retained via one catch-all namespace import.
import {
  AmbientLight,
  BoxGeometry,
  BufferGeometry,
  CapsuleGeometry,
  CircleGeometry,
  ConeGeometry,
  DirectionalLight,
  Group,
  Material,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  Object3D,
  PerspectiveCamera,
  Scene,
  SphereGeometry,
  WebGLRenderer
} from 'three';

// Two purely DECORATIVE body-shape presets -- NOT derived from any real
// gender field. discovery-service's UserCardResponse has no gender field at
// all, and CLAUDE.md's own domain rule is explicit: "gender is never exposed
// in API responses to other users." Assigning a preset by `userId % 2` gives
// the radar visual variety (not every discovered user rendered as an
// identical clone) without claiming to represent that person's real gender --
// the distinction is a skirt-shaped lower body vs. separate legs, the same
// minimal, abstract convention restroom pictograms use, not a skin-tone or
// other identity marker. If a future ticket wants this to reflect a real
// attribute, closing that gap means deliberately changing the backend
// contract AND the privacy rule above -- a product decision, not a frontend
// styling change -- not quietly repurposing this preset index to mean
// something it doesn't.
type AvatarPreset = 'a' | 'b';

function presetFor(userId: number): AvatarPreset {
  return userId % 2 === 0 ? 'a' : 'b';
}

const WAVE_DURATION_MS = 1300;
// Resting arm angle (radians) away from a dead-straight vertical drop -- see
// buildFigure's leftArmPivot/rightArmPivot setup and updateWave below.
const IDLE_ARM_TILT_Z = 0.12;

// Single Responsibility (S): this component only knows how to render one
// small standing figure into its own canvas and wave once on appear -- it
// has no idea it's sitting on a radar dot, inside a *ngFor, or what
// "discovery" even means. RadarComponent's template is the only thing that
// wires a user id to a position on screen.
//
// One Three.js scene/renderer PER instance (not a single shared canvas for
// every discovered user) -- deliberately simple for a realistic discovered-
// user count (a handful of nearby people, not hundreds), which keeps
// positioning, click-through, and this component's own lifecycle exactly as
// simple as any other *ngFor'd Angular component instead of needing manual
// screen-to-3D projection math. See ngOnDestroy for why that simplicity
// still requires explicit WebGL cleanup.
@Component({
  selector: 'app-user-avatar-3d',
  templateUrl: './user-avatar-3d.component.html',
  styleUrl: './user-avatar-3d.component.css',
  // Angular 19 defaults new components to standalone: true. This project is
  // module-based only (no standalone components, per project-wide Angular
  // rule), so that default must be overridden explicitly on every
  // hand-written component.
  standalone: false
})
export class UserAvatar3dComponent implements OnInit, OnChanges, OnDestroy {
  @Input() userId = 0;

  @ViewChild('canvas', { static: true }) private readonly canvasRef!: ElementRef<HTMLCanvasElement>;

  private renderer: WebGLRenderer | null = null;
  private scene: Scene | null = null;
  private camera: PerspectiveCamera | null = null;
  private rightArmPivot: Object3D | null = null;
  private readonly disposables: Array<BufferGeometry | Material> = [];

  private animationFrameId: number | null = null;
  private waveStartedAt = 0;

  constructor(private readonly ngZone: NgZone) {}

  ngOnInit(): void {
    this.buildScene();
    this.playWave();
  }

  ngOnChanges(changes: SimpleChanges): void {
    // Guard clause: only userId changing (a different person now occupies
    // this *ngFor slot, via trackByUserId) warrants rebuilding the figure --
    // re-running this for every unrelated change-detection pass would churn
    // through WebGL resources for nothing.
    if (!changes['userId'] || changes['userId'].isFirstChange()) {
      return;
    }
    this.disposeScene();
    this.buildScene();
    this.playWave();
  }

  ngOnDestroy(): void {
    this.stopAnimationLoop();
    this.disposeScene();
  }

  private buildScene(): void {
    const canvas = this.canvasRef.nativeElement;
    const size = canvas.clientWidth || 56;

    const scene = new Scene();
    const camera = new PerspectiveCamera(36, 1, 0.1, 10);
    // A slight three-quarter angle (not dead-on from the front) -- a camera
    // looking straight at a figure's front reads as comparatively flat even
    // in a real 3D scene, since none of the depth along the view axis is
    // visible. Looking from slightly above and to the side is what actually
    // sells "this is a 3D object," the same reason product renders and game
    // character-select screens almost never use a perfectly front-on camera.
    // Framed with real margin above the head and below the feet (full figure
    // top-to-bottom is roughly y=0 to y=1.8) -- the previous, tighter framing
    // cropped the legs out of frame entirely, which read as a floating
    // half-figure/bust rather than something actually standing.
    camera.position.set(1.1, 1.6, 3.4);
    camera.lookAt(0, 0.85, 0);

    // alpha: true -- the canvas stays transparent so it blends directly into
    // the radar's own background instead of painting an opaque square;
    // RadarComponent's .dot-pulse ring (a separate, sibling element) is the
    // only decoration left around the figure now -- see radar.component.css
    // for the "no circular platform behind the avatar" change this pairs
    // with.
    const renderer = new WebGLRenderer({ canvas, alpha: true, antialias: true });
    renderer.setSize(size, size, false);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));

    scene.add(new AmbientLight(0xffffff, 0.7));
    const keyLight = new DirectionalLight(0xffffff, 0.95);
    keyLight.position.set(1.4, 2.2, 1.8);
    scene.add(keyLight);
    // A dim fill light from the opposite side -- without it, the side of the
    // figure away from keyLight falls to near-black, which at this small a
    // render size reads as "a flat dark shape" rather than a lit 3D body.
    const fillLight = new DirectionalLight(0xffffff, 0.3);
    fillLight.position.set(-1.5, 1, -1);
    scene.add(fillLight);

    const figure = this.buildFigure(presetFor(this.userId));
    scene.add(figure);

    this.scene = scene;
    this.camera = camera;
    this.renderer = renderer;
    renderer.render(scene, camera);
  }

  // Builds one small standing humanoid from primitive geometries -- boxes/
  // capsules/cones/spheres, nothing downloaded or pre-modeled (see the
  // "procedural, not a downloaded asset" decision this ticket was scoped
  // to). A face (eyes), neck, hands, feet and a ground shadow are what turn
  // "a sphere on a capsule" into something that actually reads as a person
  // standing on the map rather than an abstract shape. Grouped with a
  // shoulder pivot for the right arm specifically, so the wave animation can
  // rotate just that pivot rather than reaching into the mesh.
  private buildFigure(preset: AvatarPreset): Group {
    const isPresetB = preset === 'b';
    // violet-700 / violet-800 -- close enough to read as "the same family of
    // figure" but distinct enough that two different discovered users don't
    // look like identical clones standing next to each other.
    const clothingColor = isPresetB ? 0x6d28d9 : 0x5b21b6;
    // violet-50 -- a light, clearly-stylized tone kept deliberately far from
    // clothingColor in both hue and lightness so head/hands read as a
    // distinct body part at a glance even at this small a render size. Not a
    // skin-tone choice meant to represent any real person (see this file's
    // header comment on the privacy reasoning behind both presets).
    const skinColor = 0xf5f3ff;
    const eyeColor = 0x1e1b4b; // indigo-950
    const group = new Group();

    // metalness: 0 on every body material, explicitly -- MeshStandardMaterial
    // defaults to metalness 0.5, which under a single directional light
    // produces the shiny, chrome-like highlight that was the single biggest
    // reason this read as "a robot" rather than "a person": metal reflects
    // its environment in hard specular highlights, skin/fabric scatter light
    // softly. A fully non-metallic (matte) material is what any stylized/toy-
    // like character render uses instead.
    const clothingMaterial = this.track(new MeshStandardMaterial({ color: clothingColor, roughness: 0.8, metalness: 0 }));
    const skinMaterial = this.track(new MeshStandardMaterial({ color: skinColor, roughness: 0.75, metalness: 0 }));
    const eyeMaterial = this.track(new MeshBasicMaterial({ color: eyeColor }));
    const hairMaterial = this.track(new MeshStandardMaterial({ color: 0x2e1065, roughness: 0.85, metalness: 0 })); // violet-950
    const faceLineMaterial = this.track(new MeshBasicMaterial({ color: eyeColor }));

    // Segment counts well above the geometric minimum (e.g. 32 vs the
    // previous 20 on the head, 12/20 vs 4/8 on the capsules) -- a visibly
    // faceted, low-poly surface is a second strong "mechanical" cue; a
    // smoothly curved one reads as organic even though it's still a simple
    // primitive underneath.
    const head = new Mesh(this.track(new SphereGeometry(0.25, 32, 32)), skinMaterial);
    head.position.y = 1.54;
    group.add(head);

    // A full face, not just eyes -- two dot eyes on an otherwise bare,
    // perfectly smooth white sphere is a near-universal ROBOT design trope
    // (think of any simple sci-fi droid head); eyebrows and a mouth are what
    // push the same head toward "person" instead. All on the sphere's front
    // hemisphere (positive Z, toward camera.lookAt's side of the figure).
    const eyeGeometry = this.track(new SphereGeometry(0.032, 10, 10));
    const leftEye = new Mesh(eyeGeometry, eyeMaterial);
    leftEye.position.set(-0.095, 1.57, 0.215);
    group.add(leftEye);
    const rightEye = new Mesh(eyeGeometry, eyeMaterial);
    rightEye.position.set(0.095, 1.57, 0.215);
    group.add(rightEye);

    const eyebrowGeometry = this.track(new BoxGeometry(0.09, 0.018, 0.02));
    const leftEyebrow = new Mesh(eyebrowGeometry, faceLineMaterial);
    leftEyebrow.position.set(-0.095, 1.625, 0.21);
    group.add(leftEyebrow);
    const rightEyebrow = new Mesh(eyebrowGeometry, faceLineMaterial);
    rightEyebrow.position.set(0.095, 1.625, 0.21);
    group.add(rightEyebrow);

    const mouth = new Mesh(this.track(new BoxGeometry(0.1, 0.022, 0.02)), faceLineMaterial);
    mouth.position.set(0, 1.47, 0.225);
    group.add(mouth);

    // Hair on BOTH presets, in two different cuts -- a bald, bare-scalp head
    // was the other half of the "robot head" read; giving everyone some hair
    // (not just preset 'b') also avoids implying hair itself is the gender
    // signal, with the skirt/legs split below doing that decorative work
    // instead (see this file's header comment).
    if (isPresetB) {
      // Fuller/longer coverage.
      const hair = new Mesh(
        this.track(new SphereGeometry(0.27, 24, 18, 0, Math.PI * 2, 0, Math.PI / 1.7)),
        hairMaterial
      );
      hair.position.y = 1.57;
      group.add(hair);
    } else {
      // Short-cropped coverage -- just a cap over the crown.
      const hair = new Mesh(
        this.track(new SphereGeometry(0.255, 24, 18, 0, Math.PI * 2, 0, Math.PI / 2.6)),
        hairMaterial
      );
      hair.position.y = 1.6;
      group.add(hair);
    }

    // No separate neck cylinder -- a distinct, straight-edged cylindrical
    // "joint" between two rounded shapes is exactly the kind of hard
    // mechanical seam that reads as robotic. Overlapping the head directly
    // into the torso's top (negative gap) blends the two into one
    // continuous silhouette instead, the low-poly-character equivalent of
    // not drawing a visible hinge where a real neck would be.
    const torso = new Mesh(this.track(new CapsuleGeometry(0.25, 0.46, 8, 16)), clothingMaterial);
    torso.position.y = 1.03;
    group.add(torso);

    // Small rounded shoulder joints -- without these, each arm capsule meets
    // the torso in a visible hard seam (another classic "action figure"
    // tell); a sphere the same radius as the arm, centered on the shoulder
    // pivot, fills that seam into a smooth, continuous curve instead.
    const shoulderGeometry = this.track(new SphereGeometry(0.1, 16, 16));
    const leftShoulder = new Mesh(shoulderGeometry, clothingMaterial);
    leftShoulder.position.set(-0.3, 1.24, 0);
    group.add(leftShoulder);
    const rightShoulder = new Mesh(shoulderGeometry, clothingMaterial);
    rightShoulder.position.set(0.3, 1.24, 0);
    group.add(rightShoulder);

    // Preset 'b': a cone-shaped lower body (skirt silhouette) + bare feet.
    // Preset 'a': two separate legs ending in small rounded shoes. See this
    // file's header comment -- purely a decorative shape difference, not a
    // real-data-derived one. Shoes are a squashed SPHERE on both presets, not
    // a box -- a flat-faced cube is the other shape (besides a plain
    // cylinder) that reads most as "machine part" rather than "foot."
    const shoeMaterial = this.track(new MeshStandardMaterial({ color: 0x1e1b4b, roughness: 0.8, metalness: 0 })); // indigo-950
    if (isPresetB) {
      const skirt = new Mesh(this.track(new ConeGeometry(0.33, 0.48, 24)), clothingMaterial);
      skirt.position.y = 0.47;
      group.add(skirt);

      const footGeometry = this.track(new SphereGeometry(0.075, 14, 14));
      const leftFoot = new Mesh(footGeometry, skinMaterial);
      leftFoot.scale.set(1, 0.7, 1.3);
      leftFoot.position.set(-0.1, 0.06, 0.04);
      group.add(leftFoot);
      const rightFoot = new Mesh(footGeometry, skinMaterial);
      rightFoot.scale.set(1, 0.7, 1.3);
      rightFoot.position.set(0.1, 0.06, 0.04);
      group.add(rightFoot);
    } else {
      const legGeometry = this.track(new CapsuleGeometry(0.095, 0.42, 8, 16));
      const leftLeg = new Mesh(legGeometry, clothingMaterial);
      leftLeg.position.set(-0.13, 0.43, 0);
      group.add(leftLeg);
      const rightLeg = new Mesh(legGeometry, clothingMaterial);
      rightLeg.position.set(0.13, 0.43, 0);
      group.add(rightLeg);

      // Hip joints -- same reasoning as the shoulder spheres above, blending
      // the leg-into-torso seam.
      const hipGeometry = this.track(new SphereGeometry(0.1, 16, 16));
      const leftHip = new Mesh(hipGeometry, clothingMaterial);
      leftHip.position.set(-0.13, 0.72, 0);
      group.add(leftHip);
      const rightHip = new Mesh(hipGeometry, clothingMaterial);
      rightHip.position.set(0.13, 0.72, 0);
      group.add(rightHip);

      // Squashed, elongated spheres -- a rounded shoe shape instead of a
      // flat-sided box.
      const footGeometry = this.track(new SphereGeometry(0.09, 14, 14));
      const leftFoot = new Mesh(footGeometry, shoeMaterial);
      leftFoot.scale.set(1, 0.55, 1.4);
      leftFoot.position.set(-0.13, 0.05, 0.05);
      group.add(leftFoot);
      const rightFoot = new Mesh(footGeometry, shoeMaterial);
      rightFoot.scale.set(1, 0.55, 1.4);
      rightFoot.position.set(0.13, 0.05, 0.05);
      group.add(rightFoot);
    }

    const armGeometry = this.track(new CapsuleGeometry(0.08, 0.36, 8, 16));
    const handGeometry = this.track(new SphereGeometry(0.085, 14, 14));

    const leftArmPivot = new Group();
    leftArmPivot.position.set(-0.3, 1.24, 0);
    // A small resting tilt, not a perfectly straight vertical drop -- arms
    // held dead straight down at the sides is a "soldier at attention"/
    // mannequin pose; a slight outward angle reads as a relaxed, natural
    // stance instead. IDLE_ARM_TILT_Z is reused below for the right arm's
    // own rest pose (see updateWave).
    leftArmPivot.rotation.z = -IDLE_ARM_TILT_Z;
    const leftArm = new Mesh(armGeometry, clothingMaterial);
    leftArm.position.y = -0.19;
    leftArmPivot.add(leftArm);
    const leftHand = new Mesh(handGeometry, skinMaterial);
    leftHand.position.y = -0.4;
    leftArmPivot.add(leftHand);
    group.add(leftArmPivot);

    // The arm that waves -- rotated by playWave()/updateWave() below, not
    // posed here. Positioned at the shoulder (not the arm's own center) so
    // rotating this pivot swings the whole arm like a real shoulder joint,
    // the same hierarchical-transform idea any skeletal rig uses, just with
    // one bone instead of a full skeleton.
    const rightArmPivot = new Group();
    rightArmPivot.position.set(0.3, 1.24, 0);
    rightArmPivot.rotation.z = IDLE_ARM_TILT_Z;
    const rightArm = new Mesh(armGeometry, clothingMaterial);
    rightArm.position.y = -0.19;
    rightArmPivot.add(rightArm);
    const rightHand = new Mesh(handGeometry, skinMaterial);
    rightHand.position.y = -0.4;
    rightArmPivot.add(rightHand);
    group.add(rightArmPivot);
    this.rightArmPivot = rightArmPivot;

    // A soft contact shadow directly beneath the figure's own feet -- a
    // real (if simple) 3D grounding cue that lives IN the scene, not a flat
    // CSS ring wrapped around the whole dot the way the previous version
    // did (exactly what read as "inside a circle" rather than "standing").
    const shadow = new Mesh(
      this.track(new CircleGeometry(0.34, 24)),
      this.track(new MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.28 }))
    );
    shadow.rotation.x = -Math.PI / 2;
    shadow.position.y = 0.01;
    group.add(shadow);

    return group;
  }

  // Plays once when the figure first appears (component creation = the
  // moment this discovered user's dot first renders on the radar --
  // satisfies "wave when we open scan") and then stops for good -- a
  // continuous per-frame render loop for every discovered user on screen
  // would mean N simultaneous requestAnimationFrame loops for N people
  // nearby, which is wasteful for an animation that only needs to run once.
  // "Render on demand" (render only when something actually changed, then
  // stop) rather than "render every frame forever" is the standard Three.js
  // performance pattern for a scene that isn't a continuously-moving game.
  private playWave(): void {
    this.waveStartedAt = performance.now();
    // requestAnimationFrame runs on every repaint -- outside Angular's zone
    // for the same reason AmbientBackgroundComponent's mousemove listener
    // is: this loop drives WebGL rendering directly, Angular's template
    // binds nothing from it, so a change-detection pass on every frame would
    // be pure overhead.
    this.ngZone.runOutsideAngular(() => {
      const tick = (): void => {
        const elapsed = performance.now() - this.waveStartedAt;
        this.updateWave(elapsed);
        this.renderFrame();
        if (elapsed < WAVE_DURATION_MS) {
          this.animationFrameId = requestAnimationFrame(tick);
        } else {
          this.animationFrameId = null;
        }
      };
      this.animationFrameId = requestAnimationFrame(tick);
    });
  }

  private updateWave(elapsedMs: number): void {
    const pivot = this.rightArmPivot;
    if (!pivot) {
      return;
    }
    const progress = Math.min(elapsedMs / WAVE_DURATION_MS, 1);
    // Ramp the arm up over the first 20% of the duration, hold it raised
    // while it swings side to side through the middle, then ramp back down
    // over the last 20% -- a simple sine-driven swing rather than a full
    // keyframe/AnimationMixer setup, which this one-bone, one-shot gesture
    // doesn't need.
    const raisedAmount = Math.min(progress / 0.2, (1 - progress) / 0.2, 1);
    const swing = Math.sin(elapsedMs / 90) * 0.35;
    // Interpolates from the relaxed IDLE_ARM_TILT_Z rest pose up to the
    // raised wave angle and back, rather than from/to a hard 0 -- so the arm
    // settles into the same natural-looking rest position both before the
    // wave starts and after it ends.
    pivot.rotation.z = IDLE_ARM_TILT_Z + (-(1.9 + swing) - IDLE_ARM_TILT_Z) * raisedAmount;
  }

  private renderFrame(): void {
    if (this.renderer && this.scene && this.camera) {
      this.renderer.render(this.scene, this.camera);
    }
  }

  private stopAnimationLoop(): void {
    if (this.animationFrameId !== null) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = null;
    }
  }

  private track<T extends BufferGeometry | Material>(resource: T): T {
    this.disposables.push(resource);
    return resource;
  }

  // Unlike regular JS objects, WebGL geometries/materials/the renderer's own
  // GL context are NOT reclaimed by garbage collection just because nothing
  // references them anymore -- each holds a real GPU-side resource that
  // stays allocated until .dispose() is called explicitly. With several
  // discovered-user dots appearing and disappearing as /scan/nearby's poll
  // cycles (RadarComponent's beginPolling), skipping this would leak one
  // real WebGL context + its buffers per removed dot for the rest of the
  // tab's life -- the same "don't leak" discipline UserCardComponent's own
  // blob-URL cleanup already follows, just for a different resource type.
  private disposeScene(): void {
    this.stopAnimationLoop();
    for (const resource of this.disposables) {
      resource.dispose();
    }
    this.disposables.length = 0;
    this.renderer?.dispose();
    this.renderer = null;
    this.scene = null;
    this.camera = null;
    this.rightArmPivot = null;
  }
}
