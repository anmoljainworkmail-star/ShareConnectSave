# Figma Compliance Skill — ShareConnectSave Frontend

## Why this exists

T063 (Auth Flow) shipped with Login/OTP screens that were bare, unstyled HTML —
hardcoded hex colors, no `FIGMA_SPEC.md` tokens, no gradient/layout from the spec at
all — because nothing in the implementation step forced anyone to actually open
`FIGMA_SPEC.md` before writing a template. It was only caught after the ticket was
already marked done, requiring a rework pass. This skill exists so Figma conformance
is a required step of *implementing* a screen ticket, not something a reviewer has to
catch after the fact.

## Before writing any template or component CSS

1. **Identify the screen(s).** Find this ticket's screen(s) in `FIGMA_SPEC.md` §4
   ("SCREEN NN — ..."). Read that screen's full section — layout, states,
   navigation — before writing a line of HTML.
2. **Use the existing tokens, extend if genuinely missing.** Read
   `src/frontend/src/styles/tokens.css` (added in T063) — it already transcribes
   `FIGMA_SPEC.md` §2's full token set (violet scale, semantic colors, gradients,
   base typography, spacing scale, border radius, shadows). Every color, spacing,
   radius, shadow, or gradient value this screen needs should already be a token
   there. If one genuinely isn't, add it to `tokens.css` with the *exact* value
   `FIGMA_SPEC.md` gives — never invent one, and never hardcode a literal hex/px
   value directly in a component's `.css` file.
3. **Reuse shared chrome, don't re-declare it.** Check whether a shared CSS
   partial already exists for this screen's visual family — e.g.
   `src/frontend/src/app/auth/auth-shared.css` covers every `--gradient-auth-bg`
   screen (Login, OTP, and Profile Setup per `FIGMA_SPEC.md`'s "these screens
   render as a centered card ... on `--gradient-auth-bg`" note). Pull it in via
   `@import` rather than copy-pasting the same gradient/bokeh/card CSS into a new
   component stylesheet. If this ticket is the *first* screen in a new visual
   family (first Radar-family screen, first Profile-family screen, etc.), create
   that family's own shared partial the same way, for the next ticket in that
   family to reuse.
4. **Pixel-perfect screens are a special case.** Two screens have an actual
   rendered mockup and must be matched pixel-for-pixel, not just by written spec:
   Screen 07 (Radar Active, GPS Mode) and Screen 12 (Chat) —
   https://claude.ai/artifact/CAu6pBZqysAqa8yKct6jrw. If this ticket builds either
   one, open that artifact and compare against it directly. Every other screen
   (including Radar's own non-"Active" states) follows `FIGMA_SPEC.md`'s written
   spec only — there is no additional mockup to match for those.
5. **Build both breakpoints.** `FIGMA_SPEC.md` §1a requires two layouts per
   screen: Mobile (360–767px, single column, bottom nav bar) and Desktop
   (1024px+, two-panel shell with a 240px fixed sidebar). Tablet (768–1023px)
   reuses the desktop layout with an icon-only sidebar. Build both, not just
   whichever is easiest to eyeball in a wide browser window.
6. **Build the shell once, if this ticket needs it.** If this screen needs the
   persistent app shell (sidebar nav on desktop / bottom nav bar on mobile, per
   §1a) and the shell doesn't exist in the codebase yet, build it now as part of
   this ticket — don't duplicate shell markup inside a feature screen component.

## Self-check before reporting IMPLEMENTATION COMPLETE

For every screen this ticket touches, go section-by-section through that screen's
`FIGMA_SPEC.md` entry and confirm each listed element has a corresponding
implementation — not just the ones that were functionally convenient to build.
Specifically check for:

- Every named layout element, including decorative/background ones (bokeh circles,
  the global ripple texture, illustrations) — these are explicitly spec'd, not
  optional polish.
- Every listed state (default / loading / error / locked / etc.) — not just the
  happy path.
- Every listed animation, with its actual duration/easing — `FIGMA_SPEC.md` gives
  exact timings (e.g. "translate X/Y by 8px over 6 seconds, ease-in-out loop");
  don't approximate.

Report any token file or shared-partial CSS file you created or extended the same
way you report any other file change, in the IMPLEMENTATION COMPLETE file list.

## What NOT to do

- Do not hardcode a hex color, pixel spacing/radius value, or gradient string
  directly in a component's `.css` file — it belongs in `tokens.css` (or a new,
  equally-shared token file) as a CSS custom property, referenced via
  `var(--token-name)`.
- Do not treat "the ticket's acceptance criteria don't mention styling" as license
  to skip it. Acceptance criteria in this project describe *functional* behavior;
  `FIGMA_SPEC.md` is a separate, always-applicable contract for every screen's
  visual layer, the same way CLAUDE.md's architecture rules apply even when a
  ticket's AC list doesn't restate them.
- Do not invent a design decision `FIGMA_SPEC.md` doesn't make. If the spec is
  genuinely silent on something this ticket needs (an interaction detail, a
  missing breakpoint rule), say so explicitly in your implementation notes rather
  than guessing silently — the same "flag assumptions, don't hide them" standard
  this project already applies to backend contract gaps.
