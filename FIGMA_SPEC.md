# ShareConnectSave — Figma Prototype Specification

## How to use this document
Hand this entire document to a Figma designer or paste it into Figma AI / a design tool. It contains every screen, every state, every flow, and every component needed to build a complete clickable prototype.

---

## 1. Design Brief

**App name:** ShareConnectSave  
**Platform:** Responsive web (PWA) — fully usable on laptop browser AND mobile. Design both breakpoints.  
**Future:** Capacitor wraps the mobile layout for native Android/iOS — avoid browser-specific UI patterns.  
**Audience:** Urban travelers, aged 20–40, comfort with apps like Tinder, Ola, WhatsApp.  
**Tone:** Trustworthy, clean, slightly energetic. Not dating-app-flirty. Not corporate. Somewhere between Google Maps and Bumble BFF.

---

## 1a. Responsive Breakpoints

Design **two layouts** for every screen:

| Breakpoint | Width | Layout model |
|---|---|---|
| **Mobile** | 360–767px | Single column, bottom navigation bar |
| **Desktop** | 1024px+ | Two-panel shell with left sidebar navigation |

Tablet (768–1023px): use desktop layout but with narrower sidebar (icon-only, no labels).

### Desktop Shell Layout

All desktop screens live inside this persistent shell:

```
┌─────────────────────────────────────────────────────────┐
│  SIDEBAR (240px fixed left)  │  MAIN CONTENT (flex-1)   │
│                              │                           │
│  [Logo + wordmark]           │  ← screen-specific        │
│                              │    content renders here   │
│  ● Radar          ← active   │                           │
│  ○ Activity                  │                           │
│  ○ Profile                   │                           │
│                              │                           │
│  ─────────────────           │                           │
│  [Avatar 32px] Anmol         │                           │
│  Settings ⚙                  │                           │
└──────────────────────────────┴───────────────────────────┘
```

**Sidebar specs:**
- Width: 240px, fixed, deep indigo (`--color-radar-bg`) background
- Logo area: 64px tall, logo icon + "ShareConnectSave" wordmark in white
- Nav items: icon + label, 48px tall each, `--color-text-inverse` inactive, white active with left violet accent bar (4px, `--color-primary`)
- Bottom: user avatar + name + settings icon
- Mobile: sidebar collapses entirely, replaced by bottom navigation bar

### Desktop Content Area

Max content width: **880px** (centered in the content area with auto margins if viewport is very wide).  
Background: `--color-background` (`#F5F7FA`).

---

---

## 2. Design Tokens

### Philosophy
Single-family violet palette. Every colour in the app comes from the violet-indigo spectrum or a minimal set of semantic colours (amber for warnings, red for danger). No random accent colours. This gives the app a cohesive, premium, peaceful feel — the same way Notion and Linear feel visually unified.

---

### Violet Shade Scale (base palette — reference these everywhere)

| Shade | Hex | Visual |
|---|---|---|
| Violet-50 | `#F5F3FF` | Nearly white, barely tinted — page backgrounds |
| Violet-100 | `#EDE9FE` | Light tint — input backgrounds, inactive chips |
| Violet-200 | `#DDD6FE` | Soft — borders, dividers |
| Violet-300 | `#C4B5FD` | Medium-light — placeholder text, disabled |
| Violet-400 | `#A78BFA` | Medium — secondary actions, icons |
| Violet-500 | `#8B5CF6` | Vivid — hover states |
| Violet-600 | `#7C3AED` | **Primary** — main CTA, active nav |
| Violet-700 | `#6D28D9` | **Primary Dark** — pressed buttons |
| Violet-800 | `#5B21B6` | Deep — sidebar background gradient end |
| Violet-900 | `#4C1D95` | Very deep — sidebar background gradient start |
| Violet-950 | `#2E1065` | Near-black violet — primary text |
| Indigo-950 | `#1E1B4B` | **Radar background** — deep, immersive dark |

---

### Semantic Colour Tokens

| Token | Value | Usage |
|---|---|---|
| `--color-primary` | Violet-600 `#7C3AED` | CTA buttons, active states, links, progress |
| `--color-primary-dark` | Violet-700 `#6D28D9` | Pressed / hover button state |
| `--color-primary-light` | Violet-100 `#EDE9FE` | Tag backgrounds, subtle highlights |
| `--color-accent` | Violet-400 `#A78BFA` | Trusted badge, verified badge, GPS mode dot |
| `--color-surface` | `#FFFFFF` | Cards, bottom sheets, inputs |
| `--color-background` | Violet-50 `#F5F3FF` | Page background throughout |
| `--color-border` | Violet-200 `#DDD6FE` | Card borders, dividers, input outlines |
| `--color-text-primary` | Violet-950 `#2E1065` | All body text, headings |
| `--color-text-secondary` | Violet-400 `#A78BFA` | Subtext, captions, labels, placeholder |
| `--color-text-inverse` | `#FFFFFF` | Text on dark/violet backgrounds |
| `--color-warning` | Amber `#F59E0B` | BLE mode indicator, pending states |
| `--color-warning-light` | `#FEF3C7` | Warning tag backgrounds |
| `--color-danger` | Red `#EF4444` | Destructive actions, negative tags, block/report |
| `--color-danger-light` | `#FEE2E2` | Negative tag backgrounds |
| `--color-success` | `#10B981` | Met successfully, positive confirmation |
| `--color-overlay` | `rgba(46,16,101,0.5)` | Bottom sheet backdrop (violet-tinted, not grey) |

---

### Radar-Specific Tokens

| Token | Value | Notes |
|---|---|---|
| `--color-radar-bg` | Indigo-950 `#1E1B4B` | Full-screen background — deep, immersive |
| `--color-radar-ring` | `#312E81` (indigo-900) | Concentric ring lines — barely visible |
| `--color-radar-sweep` | Violet-500 `#8B5CF6` at 35% opacity | Rotating sweep wedge |
| `--color-radar-dot` | Violet-400 `#A78BFA` | Discovered user dots (pulsing) |
| `--color-radar-dot-ble` | Violet-300 `#C4B5FD` at 60% opacity | Unresolved BLE user dots (dimmer) |
| `--color-radar-center-ring` | Violet-600 `#7C3AED` at 40% | Soft glow ring around the user's own avatar |

---

### Global Background Texture

Every screen (except the radar screens which have their own background) must have this subtle texture layered over `--color-background` (Violet-50). It should feel like the app has a personality even on plain list screens.

**Texture: Slow radar ripple**
- A single large SVG circle (the "ripple"), centered at a random off-screen point (e.g., top-right corner), very faintly visible
- Properties: stroke only (no fill), Violet-300 at 8% opacity, stroke-width 1px
- Animation: the circle slowly expands (`r` from 200px → 800px) and fades out (opacity 8% → 0%) over 8 seconds, then immediately restarts from r=200
- Stack 2–3 of these ripples at staggered animation offsets (0s, 2.7s, 5.3s) so there's always one visible
- The ripple origin point is fixed per screen session — different screens can place it top-right, bottom-left, etc.
- On screens with white cards, the texture shows only in the background margin — the card itself is plain white

**In Figma:** add the ripple as a background frame behind every screen's content, set to `--color-background`. Use a semi-transparent violet circle with a slow scale animation to represent the texture. Designers should mark this layer "background-texture" and lock it.

**Result:** Even the Settings screen or Block List screen feels like it's softly breathing — cohesive with the radar theme without being distracting.

---

### Gradients

Use these exactly — no improvising gradient directions.

| Name | Value | Used on |
|---|---|---|
| `--gradient-sidebar` | `linear-gradient(180deg, #4C1D95 0%, #2E1065 100%)` | Desktop sidebar background |
| `--gradient-auth-bg` | `linear-gradient(135deg, #4C1D95 0%, #7C3AED 50%, #A78BFA 100%)` | Login, OTP, Profile Setup full-page background |
| `--gradient-primary-btn` | `linear-gradient(135deg, #7C3AED 0%, #6D28D9 100%)` | Primary buttons (gives depth vs flat colour) |
| `--gradient-radar-sweep` | `conic-gradient from sweep angle, #8B5CF6 → transparent` | Radar sweep animation |
| `--gradient-profile-header` | `linear-gradient(180deg, #6D28D9 0%, #4C1D95 100%)` | Profile screen header band |
| `--gradient-card-hover` | `linear-gradient(135deg, #F5F3FF, #EDE9FE)` | Card hover state on desktop |

### Typography

| Token | Font | Size | Weight | Usage |
|---|---|---|---|---|
| `--text-heading-xl` | Inter | 28px | 700 | Screen titles |
| `--text-heading-lg` | Inter | 22px | 700 | Card headings |
| `--text-heading-md` | Inter | 18px | 600 | Section headings |
| `--text-body-lg` | Inter | 16px | 400 | Body text |
| `--text-body-md` | Inter | 14px | 400 | Secondary body |
| `--text-body-sm` | Inter | 12px | 400 | Captions, labels |
| `--text-label` | Inter | 11px | 500 | Tags, badges (uppercase) |

### Spacing Scale
4px base unit. Use: 4, 8, 12, 16, 20, 24, 32, 40, 48px.

### Border Radius
- Cards: 16px  
- Buttons: 12px  
- Tags/pills: 20px (fully rounded)  
- Bottom sheets: 24px top corners only  
- Avatar: 50% (circle)

### Shadows
- `--shadow-card`: `0 2px 12px rgba(0,0,0,0.08)`  
- `--shadow-bottom-sheet`: `0 -4px 24px rgba(0,0,0,0.12)`  
- `--shadow-fab`: `0 4px 16px rgba(124,58,237,0.45)` (violet glow shadow, matches primary)

---

## 3. Component Library

### 3.1 Buttons

**Primary Button**  
- Background: `--color-primary`, text white, height 52px, full width, radius 12px  
- States: default / pressed (darker) / loading (spinner replaces text) / disabled (40% opacity)

**Secondary Button**  
- Border: 1.5px `--color-primary`, text `--color-primary`, transparent background  
- Same sizes as primary

**Danger Button**  
- Background: `--color-danger`, text white

**Ghost Button**  
- No border, no background. Text only. Used for "Skip", "Cancel".

**FAB (Floating Action Button)**  
- Circle 64px, `--color-primary`, center icon, `--shadow-fab`  
- Used for "Start Scan" on radar screen

### 3.2 Avatar
- Sizes: 32px (list), 48px (card), 72px (profile header), 96px (profile edit)
- Circle crop, border: 2px white
- Fallback: initials on Violet-600 `#7C3AED` background if no photo

### 3.3 Rating Stars
- 5 stars, filled `#F5A623`, unfilled `--color-border`
- Sizes: small (12px), medium (16px), large (20px)
- Display the numeric value next to stars: `4.8 ★`

### 3.4 Badge/Tag Pills
- **Trusted badge**: Violet-600 background, white star icon + "Trusted", 11px uppercase
- **Verified badge**: Violet-100 background + Violet-600 text, shield icon + "Verified"
- **GPS mode**: Violet-400 dot + "GPS" text (violet-toned, calm)
- **BLE mode**: Amber `#F59E0B` dot + "BLE · Nearby only" text
- **Positive tag**: Violet-100 background `#EDE9FE`, Violet-700 text
- **Negative tag**: `--color-danger-light` background `#FEE2E2`, `--color-danger` text
- **Status pill**: "Looking" (Violet-100 background, Violet-700 text), "Unavailable" (grey-100 background, grey-500 text)

### 3.5 Bottom Sheet
- Slides up from bottom over dark overlay
- Handle bar at top center (40px wide, 4px tall, `--color-border`)
- Rounded top corners 24px
- White background
- Can be: half-height (filters, confirmations) or full-height (user card, chat)

### 3.6 Toast / Snackbar
- Appears at bottom above navigation bar
- Dark background (Violet-950 `#2E1065`), white text, auto-dismiss 3s
- Variants: neutral / success (left accent bar green) / error (left accent bar red)

### 3.7 Input Field
- Height 52px, border `--color-border`, radius 12px
- Label floats above on focus
- Error state: red border + error message below
- OTP input: 6 individual boxes side by side

### 3.8 Navigation Bar (Bottom)
- Height 64px + safe area inset, white background, top border `--color-border`
- 3 tabs: Radar (compass icon) / Activity (bell icon) / Profile (person icon)
- Active tab: `--color-primary` icon + label. Inactive: `--color-text-secondary`.
- Tab badge: red dot for unread notifications on Activity tab

### 3.9 Top App Bar
- Height 56px, white background (or transparent on radar screen)
- Left: back arrow OR hamburger (context-dependent)
- Center: screen title (heading-md)
- Right: action icon (settings gear, or nothing)

### 3.10 User Card (Discovery)
Reusable card used both as bottom sheet and as list item.

```
┌─────────────────────────────┐
│ [Avatar 72px]  Name         │
│                ★ 4.8  (32)  │
│                [Trusted] [Verified] │
│─────────────────────────────│
│ 📍 Destination: Connaught Place    │
│ 🕐 Leaving in 12 minutes           │
│ 🛤  Route match: 87%               │
│ 🌐 Language: English               │
│─────────────────────────────│
│      [Send Request]         │
└─────────────────────────────┘
```

---

## 4. Screen Inventory & Specifications

### SCREEN 01 — Splash / Launch

**Purpose:** App loads, checks auth state.  
**Duration:** 1.5 seconds, then auto-navigate.  
**Layout:**
- Full screen deep indigo (`--color-radar-bg`)
- Center: app logo (compass/radar icon in Violet-400 `#A78BFA`, softly glowing) + "ShareConnectSave" wordmark in white
- Tagline below: "Find your travel companion" in Violet-300 `#C4B5FD`
- Bottom: animated loading dots in Violet-500

**Navigation:**
- If logged in + profile complete → Screen 06 (Radar Home)
- If logged in + profile incomplete → Screen 04 (Profile Setup)
- If not logged in → Screen 02 (Login)

---

### SCREEN 02 — Login

**Purpose:** Google Sign-In entry point.  
**Layout:**
- Full-page background: `--gradient-auth-bg` (`linear-gradient(135deg, #4C1D95 0%, #7C3AED 50%, #A78BFA 100%)`) — same vibrant gradient as Profile Setup. Feels alive, not dark.
- Floating decorative elements on the gradient (purely visual, not interactive):
  - 3–4 large soft-edged circles (white at 6% opacity), slightly blurred — gives a bokeh/depth feel
  - 2 thin concentric arc lines (white at 10% opacity) positioned asymmetrically — nod to the radar without being heavy
  - Subtle slow drift animation on the circles (translate X/Y by 8px over 6 seconds, ease-in-out loop) — makes the background feel alive
- Centered white card (border-radius 24px, `--shadow-bottom-sheet`):
  - App logo + wordmark at the top of the card
  - Heading: "Find someone going your way"
  - Subtext: "Discover verified travel companions heading in your direction."
  - Google Sign-In button (standard Google branding)
  - Fine print: "By continuing you agree to our Terms and Privacy Policy."
- Card sits vertically centered (not bottom-anchored) — gives breathing room

**States:** default / loading (button spinner while Google OAuth runs)

**Why this feels better than the old dark version:** The dark radar background had no life — just a static dark canvas. This gradient is the same one Profile Setup uses, which you already liked. The drifting bokeh circles make it breathe without being distracting.

---

### SCREEN 03 — Phone Verification (OTP)

**Purpose:** Verify phone number after Google sign-in.  
**Background:** `--gradient-auth-bg` — same as Login (135deg, Violet-900 → Violet-600 → Violet-400). Bokeh circles apply here too (onboarding flow stays consistent). Input card is white with 24px radius.  
**Layout:**
- Top App Bar: back arrow + "Verify Phone"
- Illustration: phone with signal waves (friendly, not technical)
- Heading: "Enter your phone number"
- Subtext: "We'll send a one-time code to verify it's you."
- Phone input field with country code selector (+91 dropdown)
- Primary button: "Send Code"

**State 2 — OTP Input:**
- Heading changes to: "Enter the 6-digit code"
- Subtext: "Sent to +91 98765 43210 · [Change]"
- 6 OTP input boxes side by side, auto-focus, auto-advance
- Timer: "Resend in 45s" (countdown), then "Resend Code" link
- Primary button: "Verify"

**States:**
- Default
- Loading (after Send Code)
- OTP entry
- OTP incorrect: boxes turn red, shake animation, "Incorrect code. 4 attempts remaining."
- Locked: "Too many attempts. Try again in 15 minutes." Countdown timer visible.

---

### SCREEN 04 — Profile Setup

**Purpose:** First-time profile completion. Required before using the app.  
**Layout:** Multi-step flow with a progress indicator (3 dots at top, current step filled).

**Step 1 — Photo**
- Heading: "Add a profile photo"
- Subtext: "Your photo helps others recognize you. It's also used for identity verification."
- Large circle avatar placeholder (120px) with camera icon overlay, tap to open picker
- Two options below the avatar: "Take Photo" / "Upload from Gallery"
- Note below: "If your Google account had a photo, it's already loaded. You can change it here."
- Pre-filled if Google provided one (avatar shows the Google photo)
- Primary button: "Continue" (disabled until photo is set)

**Step 2 — Basic Info**
- Heading: "Tell us about yourself"
- Fields:
  - Name (pre-filled from Google, editable)
  - Gender selector: 3 pills — "Male" / "Female" / "Prefer not to say" (tap to select, required)
  - Preferred language dropdown
- Note below gender: "Your gender is never shown to other users. It's only used for filtering."
- Primary button: "Continue"

**Step 3 — Ready!**
- Heading: "You're all set!"
- Large checkmark animation (Lottie or CSS)
- Summary: "Profile created. Start scanning to find travel companions nearby."
- Primary button: "Start Exploring"

---

### SCREEN 05 — Identity Verification (Optional)

**Purpose:** Let verified users earn a Verified badge.  
**Entry point:** Settings screen or post-onboarding prompt.  
**Layout:**
- Top App Bar: back arrow + "Verify Identity"
- Illustration: face scan graphic
- Heading: "Earn a Verified badge"
- Bullet list explaining what it means and that the selfie is not stored permanently
- If no profile photo: amber banner at top — "You need a profile photo first. [Add Photo]" — verification blocked.
- If profile photo exists: "Take a selfie" instruction, camera preview or upload option
- Primary button: "Take Selfie"

**States:**
- Checking (spinner)
- Success: green checkmark, "Verified badge added to your profile!"
- Mismatch: "Photo didn't match. Make sure your profile photo is a clear, recent photo of your face."
- Error: generic error + retry

---

### SCREEN 06 — Radar Home (Idle / Not Scanning)

**Purpose:** Main screen. User has not started a scan yet.  
**Layout:**
- Background: NOT flat dark. Use a rich radial gradient: `radial-gradient(ellipse at center, #3B1F7A 0%, #1E1B4B 60%, #12102E 100%)` — deep violet-indigo at center fading to near-black at edges. Much warmer and more alive than flat dark navy.
- **Idle radar visual (even when not scanning, this should feel alive):**
  - 4 concentric rings, each with a faint violet glow (`box-shadow: 0 0 12px rgba(139,92,246,0.3)`) — rings feel lit from within, not just drawn lines
  - Rings slowly breathe: subtle scale pulse (1.0 → 1.01 → 1.0) over 4 seconds, staggered per ring — looks like gentle sonar waves even when idle
  - Thin radial grid lines (like compass directions N/S/E/W), white at 8% opacity
  - User avatar at dead center with a soft violet glow ring (Violet-600 at 40% opacity, blur radius 16px)
  - Distance labels on rings: "0.5km", "1km", "1.5km" in Violet-300, tiny (10px), positioned at the top of each ring
- Top bar (transparent): profile avatar (left), city name (center, white), filter icon (right)
- Bottom card (white, 280px, border-radius 24px top corners, `--shadow-bottom-sheet`):
  - Heading: "Where are you heading?"
  - Destination input with map pin icon
  - Departure time selector
  - "Start Scan" FAB (full-width primary button inside the card, not floating)

**States:**
- Destination empty: button disabled, grey
- Destination set: button enabled, `--gradient-primary-btn`

---

### SCREEN 07 — Radar Active (Scanning — GPS Mode)

**Purpose:** Live radar scan showing nearby users.  
**Layout:**
- Background: same rich radial gradient as Screen 06 (`radial-gradient(ellipse at center, #3B1F7A 0%, #1E1B4B 60%, #12102E 100%)`)
- **Radar animation — make this the most visually impressive screen:**
  - 4 concentric rings, each with a living violet glow:
    - Inner rings: brighter glow (Violet-500 at 50% opacity)
    - Outer rings: dimmer glow (Violet-800 at 30% opacity)
    - Each ring has a faint gradient stroke (top of ring slightly brighter than bottom — like it's lit)
  - **Sweep wedge:** 45° wide conic gradient — Violet-400 at full opacity at the leading edge, fading to fully transparent over 45°. Rotates 360° every 2.5 seconds. The leading edge leaves a brief phosphor trail (Violet-300 at 20%, fades over 0.5s after the sweep passes) — looks like a real sonar sweep.
  - **Sweep glow:** As the sweep rotates, a soft violet glow radiates from the center along the sweep direction — like the beam has energy.
  - User avatar at dead center: Violet-600 pulse ring expands outward and fades every 2s.
  - Radial grid lines (N/S/E/W), Violet-300 at 10% opacity.
  - Distance ring labels: "0.5km" etc. in Violet-300, 10px.
  
- **User dots on radar:**
  - Size: 36×36px circle, avatar image inside (circular crop)
  - Border: 2px white
  - **Verified users**: small white ✓ tick badge (12×12px circle, Violet-600 background, white tick) at bottom-right of the avatar — like a WhatsApp verified/admin badge
  - **Unverified users**: no badge
  - On appear: scale 0 → 1.2 → 1 bounce, 400ms
  - Idle pulse: soft violet glow ring expands from dot every 3s (staggered per dot)
  - Tap: ring highlights bright white momentarily before opening the user card

- Top HUD (floating, pill-shaped dark card with blur backdrop):
  - Left: "→ Connaught Place" in white
  - Right: GPS mode badge (Violet-400 dot + "GPS")
  - Center: live count "3 found" in Violet-300

- Bottom card (white, 200px, rounded top corners):
  - Horizontal scroll of avatar + name chips (small, 48px avatars with verified tick if applicable)
  - "Stop Scan" button — outlined danger style (red border, red text, white bg)

**States:**
- Searching: sweep animates, no dots yet. Center shows pulsing "Searching..." label in Violet-300.
- Results found: dots appear with bounce animation, bottom list populates
- No results: sweep slows, center label changes to "No one nearby · Try wider radius"

---

### SCREEN 08 — Radar Active (BLE Mode — Offline)

**Purpose:** Offline fallback using Bluetooth.  
**Differences from Screen 07:**
- Amber overlay tint on radar background (subtle, not garish)
- Mode badge changes to: "BLE · Nearby only" (orange)
- Top banner: amber bar — "No internet · Bluetooth mode active · Range ~20m"
- Bottom card text: "Showing Bluetooth-detected users. Connect to internet for full discovery."
- Dots are grey instead of green (unresolved profiles)
- Tapping a grey dot: bottom sheet shows "Nearby user (offline) · Connect to internet to view profile and send a request."

**Special state — iOS:**
- No radar animation shown
- Full screen message: phone icon with X, "Bluetooth discovery isn't available on iOS browsers. Connect to mobile data or Wi-Fi to use GPS discovery."

---

### SCREEN 09 — User Card (Bottom Sheet)

**Purpose:** View a discovered user's profile and send a request.  
**Trigger:** Tap a user dot on radar, or tap a user avatar in the bottom list.  
**Layout:** Full-height bottom sheet slides up.

```
─── handle bar ───────────────────────
[Avatar 72px]   Priya Sharma
                ★ 4.8  (47 ratings)
                [Trusted ✓]  [Verified 🛡]

─── divider ───────────────────────────

📍  Heading to: Connaught Place
🕐  Leaving in: 12 minutes
🛤   Route match: 87%
🌐  Language: Hindi, English

─── divider ───────────────────────────

[      Send Request      ]   ← primary button
[ Report ]                   ← ghost, small, right-aligned
```

**States:**
- Default (can send)
- Request already sent: button changes to "Request Sent ✓" (disabled, accent green)
- Already connected: button changes to "Open Chat"
- User unavailable: banner "This user is not looking for a companion right now."

---

### SCREEN 10 — Inbound Request

**Purpose:** You received a connection request from someone.  
**Entry:** Push notification or in-app SignalR event → bottom sheet auto-appears.  
**Layout:** Bottom sheet (half height).

```
─── handle bar ───────────────────────
  New Request

[Avatar 56px]   Rahul Mehta
                ★ 4.6 · Verified
                📍 Heading to: Nehru Place
                🕐 Leaving in: 8 minutes
                🛤  Route match: 79%

[ Decline ]          [ Accept ]
  ghost btn           primary btn
```

**Timer:** Subtle countdown ring around the accept button — "Expires in 8:42" — shrinks as time runs out.

**States:**
- Default
- Expired (before action): both buttons disabled, "This request has expired."
- After Accept → navigate to Screen 11 (Chat)
- After Decline → sheet closes, toast "Request declined."

---

### SCREEN 11 — Activity Feed

**Purpose:** List of all pending/active/past connection activity.  
**Navigation:** Middle tab in bottom nav (bell icon).  
**Background:** `--color-background` (Violet-50 `#F5F3FF`) + global ripple texture (SVG radar circles, Violet-300 at 8% opacity).  
**Layout:**
- Top App Bar: "Activity"
- Segmented control: "Pending" / "Active" / "Past"

**Pending tab:** List of inbound requests with Accept/Decline inline.  
**Active tab:** Your current open chat connection (max 1).  
**Past tab:** List of completed connections with rating given/received.

Each list item:
```
[Avatar]  Name · ★ Rating
          📍 Destination · 🕐 Time ago
          [Status pill]
```

---

### SCREEN 12 — Chat

**Purpose:** Temporary real-time chat between matched users.  
**Entry:** Navigated to automatically after mutual acceptance.  
**Background:** `--color-background` (Violet-50 `#F5F3FF`) for the page shell; chat bubble area uses Violet-50 too so bubbles float on a consistent surface. Ripple texture applies to the shell, not the bubble scroll area (too distracting while reading).  
**Layout:**
- Top App Bar:
  - Left: back arrow
  - Center: [Avatar 32px] "Priya Sharma" — tap to view profile
  - Right: report icon (flag)
- **Offline banner** (shown when SignalR disconnected): amber bar below app bar — "Reconnecting..."
- **Chat bubble area** (scrollable, newest at bottom):
  - Sent messages: right-aligned, `--gradient-primary-btn` bubble, white text, 18px radius
  - Received messages: left-aligned, Violet-100 `#EDE9FE` bubble, Violet-950 text, avatar on left
  - Timestamp: centered between messages if > 5 min gap
  - System message (center-aligned, grey italic): "Chat opened. You have 2 hours."
  - System message (countdown, appears at 30min left): "Chat closes in 30 minutes."
- Bottom input bar:
  - Text input: "Type a message..."
  - Send button (right, Violet-600 arrow icon)
  - Above input: **"Met Successfully"** button — full width, `--color-success` `#10B981` background, white text, prominent. Stays visible at all times. (This is the only green element — intentionally stands out against the violet palette to signal a key action.)

**States:**
- Normal
- Offline: input disabled, offline banner visible, send button disabled
- Met tapped → Screen 13 (Confirmation dialog)
- Chat closing (grace period): system message "Chat closing in 5 minutes..."
- Chat closed → auto-navigate to Screen 14 (Rating)

---

### SCREEN 13 — Met Successfully Confirmation

**Purpose:** Confirm before closing the chat.  
**Type:** Modal dialog (over Screen 12). Backdrop: `--color-overlay` (`rgba(46,16,101,0.5)` — violet-tinted, not black). Modal card: white, 20px radius, 24px padding.

```
┌───────────────────────────┐
│  🤝                       │
│  Did you meet Priya?      │
│                           │
│  This will close the chat │
│  and prompt you both to   │
│  rate each other.         │
│                           │
│  [ Not yet ]  [ Yes, Met! ]│
└───────────────────────────┘
```

---

### SCREEN 14 — Rating

**Purpose:** Rate the person you traveled with.  
**Entry:** After chat closes (either Met Successfully or timeout).  
**Background:** `--color-background` (Violet-50) + ripple texture.  
**Layout:**
- Top App Bar: "Rate your companion" (no back arrow — must complete or skip)
- User avatar + name centered below bar
- Heading: "How was your experience with Priya?"

**Tag grid (two columns):**

Positive tags (Violet-100 background, Violet-700 text, tap to select → Violet-600 background, white text):
- Friendly
- Respectful
- On time
- Good communication
- Would travel again

Negative tags (`--color-danger-light` background, `--color-danger` text, tap to select → danger filled):
- Didn't show up
- Fake destination
- Abusive
- Spam
- Asked for money

- Tags are multi-select. Selecting a negative tag shows a subtle warning: "Negative reports may affect this user's account."
- Submit button: "Submit Rating" — disabled until at least 1 tag selected (if via Met path). Enabled always on timeout path.
- Ghost button: "Skip" — only visible on timeout path (auto-close without meeting).

---

### SCREEN 15 — My Profile

**Purpose:** View and edit own profile.  
**Navigation:** Right tab in bottom nav (person icon).  
**Background:** `--color-background` (Violet-50) below the profile header. Header uses `--gradient-profile-header`. Ripple texture sits behind the white card section.  
**Layout:**
- Top App Bar: "Profile" + settings gear (right)
- Profile header uses `--gradient-profile-header` (`#6D28D9` → `#4C1D95`):
  - Avatar 96px (with camera edit overlay icon)
  - Name, white, heading-lg
  - Badges row: [Trusted] [Verified] (if earned)
  - ★ rating + count, white
- White card below header:
  - Status toggle: "Looking for companion" / "Not available" (switch)
  - List items (tap to edit):
    - Preferred language
    - About (short bio, optional)
  - Section: "Account"
    - Women-only mode toggle (only visible if gender = Female)
    - Verify Identity → Screen 05
    - Edit Profile → Screen 16
  - Section: "More"
    - Help & Support
    - Privacy Policy
    - Sign Out (danger, ghost)

---

### SCREEN 16 — Edit Profile

**Purpose:** Edit name, photo, preferred language.  
**Background:** `--color-background` (Violet-50) + ripple texture.  
**Layout:**
- Top App Bar: back arrow + "Edit Profile" + "Save" (right, text button)
- Avatar 96px centered, camera icon overlay (tap to re-upload photo)
- Form fields:
  - Name (text input, pre-filled)
  - Preferred Language (dropdown)
  - Gender: display only ("Female — cannot be changed after first scan" — grey, locked after first scan with lock icon)
- Save button at bottom (primary, full width)

---

### SCREEN 17 — Discovery Filters (Bottom Sheet)

**Purpose:** Adjust scan filters before or during a scan.  
**Trigger:** Filter icon on radar screen.  
**Type:** Half-height bottom sheet. Backdrop: `--color-overlay` (violet-tinted). Sheet background: white, 24px top radius.  
**Layout:**
```
─── handle bar ──────────────────────
  Discovery Filters

  Radius
  [●────────────────] 1.0 km
  (slider, 0.5–5 km)

  Departure window
  [±15 min] [±30 min ✓] [±60 min]
  (segmented control)

  Women-only mode  [toggle]
  (only shown if gender = Female)

  [Apply Filters]   ← primary button
─────────────────────────────────────
```

---

### SCREEN 18 — Settings

**Purpose:** App-level settings, auth, and account management.  
**Entry:** Gear icon on Profile screen.  
**Background:** `--color-background` (Violet-50) + ripple texture.  
**Layout:**
- Top App Bar: back + "Settings"
- List groups:
  - **Notifications:** Request alerts (toggle), Chat messages (toggle), App sounds (toggle)
  - **Privacy:** Block list → Screen 19, Report history
  - **Account:** Change phone number, Delete account (danger)
  - **About:** App version, Terms, Privacy Policy

---

### SCREEN 19 — Block List

**Purpose:** View and manage blocked users.  
**Background:** `--color-background` (Violet-50) + ripple texture.  
**Layout:**
- Top App Bar: back + "Blocked Users"
- Empty state: "No blocked users."
- List: each blocked user shows avatar + name + "Unblock" button (right-aligned, ghost danger)

---

### SCREEN 20 — Admin: Review Queue

**Purpose:** Admin-only. View escalated user reports.  
**Access:** Only users with `role = admin`. Accessed via hidden Settings option or direct URL.  
**Background:** `--color-background` (Violet-50) + ripple texture. Admin screens use the same palette — no special dark or "serious" colour shift.  
**Layout:**
- Top App Bar: "Admin · Review Queue" + stats icon (right)
- Summary bar: "12 users pending review" (amber background)
- List: each flagged user
  ```
  [Avatar]  Name · ★ 1.2
            3 reports · Last report: 2h ago
            [Review →]
  ```
  Sorted by report count descending.

---

### SCREEN 21 — Admin: User Detail

**Purpose:** Full view of a flagged user for admin action.  
**Background:** `--color-background` (Violet-50) + ripple texture.  
**Layout:**
- Top App Bar: back + user name
- Profile summary card (same as Screen 09 but full info including phone, join date)
- Trust score card: numeric score, badge level, request limit — with override button
- Reports section: timeline of all reports with reasons
- Action bar (bottom):
  - "Reinstate" (green) / "Suspend" (red) — depending on current status

---

### SCREEN 22 — Admin: Stats Dashboard

**Purpose:** Overview metrics.  
**Entry:** Stats icon from Screen 20.  
**Background:** `--color-background` (Violet-50) + ripple texture.  
**Layout:**
- Top App Bar: back + "Stats"
- 5 metric cards (2-column grid):
  - Active users today
  - Scans today
  - Connections today
  - Reports today
  - Top reported user (avatar + count)
- Auto-refreshes every 60 seconds (visible "Last updated: X seconds ago" caption)

---

## 5. User Flow Map

```
ONBOARDING FLOW
───────────────
Splash (01)
  ├── [Not logged in] → Login (02)
  │     └── [Google Sign-In success] → Phone OTP (03)
  │           └── [OTP verified] → Profile Setup (04)
  │                 └── [Setup complete] → Radar Home (06)
  └── [Logged in, profile complete] → Radar Home (06)

MAIN DISCOVERY FLOW
───────────────────
Radar Home (06)
  └── [Set destination + Start Scan]
        ├── [Online] → Radar Active GPS (07)
        │     ├── [Tap user dot] → User Card Sheet (09)
        │     │     └── [Send Request] → request sent state on (09)
        │     └── [Filter icon] → Discovery Filters (17)
        └── [Offline] → Radar Active BLE (08)
              └── [Tap grey dot] → "Connect to internet" message

CONNECTION FLOW
───────────────
Other user receives → Inbound Request (10)
  ├── [Accept] → Chat (12)
  │     ├── [Met Successfully] → Confirmation Dialog (13)
  │     │     └── [Yes, Met!] → Rating (14)
  │     │           └── [Submit] → Radar Home (06) + toast
  │     └── [Auto-close after 2h] → Rating (14)
  └── [Decline] → toast + sheet closes

Requester receives SignalR event:
  ├── [Accepted] → auto-navigate → Chat (12)
  ├── [Declined] → toast "Request declined"
  └── [Expired] → toast "Request expired"

PROFILE FLOW
────────────
Profile (15)
  ├── [Edit] → Edit Profile (16)
  ├── [Verify Identity] → Identity Verification (05)
  └── [Settings] → Settings (18)
        └── [Block list] → Block List (19)

ADMIN FLOW
──────────
Admin Queue (20)
  ├── [Review user] → Admin User Detail (21)
  │     ├── [Suspend] → status updated, back to queue
  │     └── [Reinstate] → status updated, back to queue
  └── [Stats icon] → Admin Stats (22)
```

---

## 6. Animations & Interactions

| Interaction | Animation |
|---|---|
| Radar sweep | Continuous CSS rotation, 3s per revolution, `--color-radar-sweep` wedge shape |
| User dot appears on radar | Scale from 0 → 1.2 → 1.0 (bounce-in), 400ms, ease-out-back |
| User dot pulse | Radial pulse ring expands from dot, fades out, repeats every 2s |
| Bottom sheet open | Slides up from bottom, 300ms, ease-out |
| Bottom sheet close | Slides down, 250ms, ease-in |
| Toast | Slides up from bottom edge, auto-dismiss with fade, 300ms |
| Screen transitions | Slide left/right for forward/back navigation |
| OTP box auto-advance | Instant jump to next box on digit entry |
| Met Successfully button | Subtle green pulse animation every 4s to draw attention |
| Request expiry ring | SVG circle stroke-dashoffset animation, shrinks over TTL duration |
| Radar — no results | Scanning dots pulse (3 dots, wave pattern) |
| Rating tag select | Scale 1 → 1.08 → 1.0 tap feedback + fill color transition |
| Profile setup step transition | Slide left, 280ms |
| Verified badge earn | Confetti burst (5 particles, Lottie), badge scales in |

---

## 6b. Desktop Layout Overrides

For screens that change significantly at desktop width. All other screens simply reflow into a centered single-column card (max-width 600px, auto margin) inside the desktop content area — no special design needed.

---

### DESKTOP — Radar Home + Active Scan

```
┌─── sidebar ───┬──────────────────────────────────────────┐
│               │  RADAR CANVAS (square, fills height)     │
│  ● Radar      │  ┌────────────────────────────────────┐  │
│               │  │       [deep indigo bg]             │  │
│               │  │                                    │  │
│               │  │      ○ ○   ← user dots             │  │
│               │  │        ●   ← you (center)          │  │
│               │  │    ○                               │  │
│               │  └────────────────────────────────────┘  │
│               │                                          │
│               │  ┌─── RIGHT PANEL (320px) ───────────┐   │
│               │  │  Destination: Connaught Place      │   │
│               │  │  Leaving: Now                      │   │
│               │  │  Mode: ● GPS                       │   │
│               │  │  ─────────────────────────────     │   │
│               │  │  3 companions nearby               │   │
│               │  │  [UserCard] ← tapped               │   │
│               │  │  [UserCard]                        │   │
│               │  │  [UserCard]                        │   │
│               │  │  ─────────────────────────────     │   │
│               │  │  [Filters]    [Stop Scan]          │   │
│               │  └────────────────────────────────────┘   │
└───────────────┴──────────────────────────────────────────┘
```

- Radar canvas: square, takes up the left ~60% of the content area, full height
- Right panel: 320px fixed width, white, shows destination input (idle) or live user list (scanning)
- User Card on desktop: clicking a user in the right panel list highlights their dot on the radar AND shows their card inline in the right panel (replaces list) — no bottom sheet needed
- Filters: open as a popover/dropdown from the Filters button, not a bottom sheet

---

### DESKTOP — Chat

```
┌─── sidebar ───┬──────────────────────────────────────────┐
│               │  LEFT PANEL (300px)  │  CHAT PANEL       │
│  ○ Radar      │                      │                   │
│  ● Activity   │  [Avatar 56px]       │  Priya Sharma  ⚑  │
│               │  Priya Sharma        │  ─────────────    │
│               │  ★ 4.8 · Trusted     │                   │
│               │                      │  [messages...]    │
│               │  📍 Connaught Place  │                   │
│               │  🛤 87% route match  │                   │
│               │  🌐 English, Hindi   │                   │
│               │                      │  ─────────────    │
│               │  ─────────────────   │  [Met ✓ button]   │
│               │  Chat closes in:     │  [  Type... → ]   │
│               │  1h 34m remaining    │                   │
│               │                      │                   │
└───────────────┴──────────────────────┴───────────────────┘
```

- Left panel: companion's profile info (read-only) + chat timer
- Right panel: full chat thread + met button + input
- No bottom sheets on desktop — the left panel replaces the sheet behaviour

---

### DESKTOP — Activity Feed

```
┌─── sidebar ───┬──────────────────────────────────────────┐
│               │  Activity                                 │
│  ○ Radar      │  [Pending ▼] [Active] [Past]             │
│  ● Activity   │                                          │
│               │  ┌──────────────────────────────────┐   │
│               │  │ [Avatar] Rahul Mehta              │   │
│               │  │          ★ 4.6 · Verified         │   │
│               │  │          📍 Nehru Place            │   │
│               │  │          Expires in 6:20 ⏱        │   │
│               │  │          [Decline]   [Accept]      │   │
│               │  └──────────────────────────────────┘   │
│               │                                          │
│               │  ┌──────────────────────────────────┐   │
│               │  │  (next request card...)           │   │
│               │  └──────────────────────────────────┘   │
└───────────────┴──────────────────────────────────────────┘
```

Inbound requests on desktop appear as cards in the Activity feed (no auto-popup bottom sheet). User chooses to review them when ready.

---

### DESKTOP — Profile & Settings

Two-column layout inside the content area:

```
┌─── sidebar ───┬──────────────────────────────────────────┐
│               │  LEFT (340px)         │  RIGHT (flex-1)  │
│  ● Profile    │                       │                  │
│               │  [Avatar 96px]        │  Edit form       │
│               │  Anmol Jain           │  (name, lang...) │
│               │  ★ 4.9  [Trusted]     │                  │
│               │  [Verified]           │                  │
│               │                       │                  │
│               │  Status: [toggle]     │  Account section │
│               │  Women-only: [toggle] │                  │
│               │                       │                  │
│               │  [Verify Identity]    │                  │
│               │  [Settings]           │                  │
└───────────────┴───────────────────────┴──────────────────┘
```

---

### DESKTOP — Onboarding / Auth

These screens (Login, OTP, Profile Setup) render as a **centered card** (480px wide, auto height) on `--gradient-auth-bg` full-page background. No sidebar — sidebar only appears after login is complete.

```
Full-page gradient background
         ┌───────────────────────┐
         │    [Logo]             │
         │                       │
         │  "Find someone going  │
         │   your way"           │
         │                       │
         │  [Continue w/ Google] │
         │                       │
         └───────────────────────┘
```

---

### DESKTOP — Admin Screens

Admin screens are data-heavy — desktop layout is a full-width table/list, not cards:

- **Queue (Screen 20):** Data table with columns: Avatar, Name, Rating, Report Count, Last Report, Actions
- **User Detail (Screen 21):** Three-column layout — profile (left), trust score (center), report history (right)
- **Stats (Screen 22):** Dashboard grid — 5 metric cards (3+2 row), no restriction on width

---

## 6c. Figma Frame Sizes to Create

| Frame name | Size | Used for |
|---|---|---|
| Mobile — all screens | 390 × 844 px | Mobile layout |
| Desktop — shell | 1440 × 900 px | Desktop layout |
| Component library | 1440 × auto | All components, all states |
| Flow diagram | 1440 × auto | Prototype connections |

---

## 7. Responsive & Edge Cases

- **Safe area:** Bottom navigation must respect iOS/Android safe area insets (`env(safe-area-inset-bottom)`)
- **Keyboard:** When keyboard opens on chat input, message list scrolls up. Bottom bar stays above keyboard.
- **Long names:** Truncate at 18 chars with ellipsis in compact views. Full name in profile/user card.
- **No photo:** Show initials avatar (first + last initial, coloured by hash of name for consistency).
- **Empty states:**
  - No activity: "Nothing here yet. Start a scan to find companions."
  - No block list: "You haven't blocked anyone."
  - No past connections: "Your completed trips will appear here."
- **Loading states:** Skeleton screens for lists and profile cards (not spinners).
- **Error states:** Illustrated error screen with icon, message, and "Retry" button for full-page failures.

---

## 8. Screens Summary (for Figma page structure)

Organise Figma pages as:

1. **Design Tokens** — colour, typography, spacing swatches
2. **Component Library** — all reusable components with all states
3. **Mobile — Onboarding** — Screens 01–05 at 390px
4. **Mobile — Discovery** — Screens 06–09 + 17 at 390px
5. **Mobile — Connection & Chat** — Screens 10–14 at 390px
6. **Mobile — Profile & Settings** — Screens 15–16, 18–19 at 390px
7. **Mobile — Admin** — Screens 20–22 at 390px
8. **Desktop — Shell + Radar** — desktop radar + idle at 1440px
9. **Desktop — Chat** — two-panel chat at 1440px
10. **Desktop — Activity + Profile + Admin** — at 1440px
11. **Flow Diagram** — clickable prototype connections (use mobile frames for prototype)

Total: **22 mobile screens + 5 desktop layout pages + component library + flow diagram**

---

## 9. Prototype Interactions (Clickable in Figma)

Set up these click targets for the prototype demo flow:

1. Splash → Login
2. Google button → OTP screen
3. Verify OTP → Profile Setup Step 1
4. Continue (photo) → Profile Setup Step 2
5. Continue (info) → Profile Setup Step 3
6. Start Exploring → Radar Home
7. Set destination (tap input) → show destination pre-filled state
8. Start Scan FAB → Radar Active GPS
9. Tap any user dot → User Card bottom sheet
10. Send Request → request sent state
11. Accept (inbound request) → Chat screen
12. Met Successfully → Confirmation dialog
13. Yes Met → Rating screen
14. Submit Rating → Radar Home (with toast)
15. Activity tab → Activity Feed
16. Profile tab → My Profile
17. Settings gear → Settings
18. Verify Identity → Identity Verification screen
