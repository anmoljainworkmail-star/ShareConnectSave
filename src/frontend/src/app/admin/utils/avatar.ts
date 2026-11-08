// Initials avatar (FIGMA_SPEC.md section 7 "No photo": first + last initial,
// coloured by a hash of the name for consistency). admin-service returns no photo
// URL, so every admin avatar is this fallback. Pure functions: no Angular, no
// state, trivially unit-testable later (T081/T083).

// How many tone classes admin-shared.css defines (.avatar--tone-0 .. 4). Keep
// in step with that file.
export const AVATAR_TONE_COUNT = 5;

export function initialsOf(name: string | null | undefined): string {
  const words = (name ?? '').trim().split(/\s+/).filter(word => word.length > 0);
  // Guard clauses, shortest case first.
  if (words.length === 0) {
    return '?';
  }
  if (words.length === 1) {
    return words[0].charAt(0).toUpperCase();
  }
  return (words[0].charAt(0) + words[words.length - 1].charAt(0)).toUpperCase();
}

// Same name -> same tone on every screen and every reload, so a person is
// recognisable by colour. A simple string hash is enough: the goal is stable
// spread across five tones, not cryptographic quality.
export function avatarToneOf(seed: string | null | undefined): number {
  const text = seed ?? '';
  let hash = 0;
  for (let index = 0; index < text.length; index++) {
    hash = (hash * 31 + text.charCodeAt(index)) >>> 0;
  }
  return hash % AVATAR_TONE_COUNT;
}

// FIGMA_SPEC.md section 7 "Long names": truncate at 18 chars with an ellipsis in
// compact views (the mobile list); full name everywhere else.
export const COMPACT_NAME_MAX_CHARS = 18;

export function truncateName(name: string | null | undefined, maxChars = COMPACT_NAME_MAX_CHARS): string {
  const text = name ?? '';
  if (text.length <= maxChars) {
    return text;
  }
  return `${text.slice(0, maxChars)}…`;
}
