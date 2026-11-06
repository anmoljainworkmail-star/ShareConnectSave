// Open/Closed (O): the rating vocabulary is DATA, not branching logic.
//
// Plain English first: think of a restaurant feedback card where the printed
// checkboxes are the "menu". The waiter who collects the cards does not need
// retraining when the owner prints a new checkbox; only the menu changes.
// Here the menu is RATING_TAG_GROUPS. The template loops over it, the colour
// of every chip comes from WHICH GROUP the tag sits in, and the submit
// validation only asks "is the selection empty?" -- none of that code has an
// `if (tag === 'Abusive')`. Adding "Great conversation" is one new string in
// the positive list, nothing else.
//
// Without this, every new tag would mean touching the template (a new chip),
// the colour logic (a new `if` branch) and the validator -- three places that
// can silently disagree about whether a tag is "good" or "bad".
//
// The strings are the WIRE values: rating-service's RatingTag enum accepts
// exactly these Title Case strings (RatingTag.fromWireValue) and rejects
// anything else with 400 INVALID_RATING_TAG, and FIGMA_SPEC.md Screen 14 shows
// the same text as the chip labels, so label and wire value are one string.
// If rating-service adds a tag, add it here (the contract's single source is
// contracts/openapi/rating-service.yaml's RatingTag schema).

export type RatingSentiment = 'positive' | 'negative';

export interface RatingTagGroup {
  readonly sentiment: RatingSentiment;
  readonly tags: readonly string[];
}

// Order matters: FIGMA_SPEC.md Screen 14 lists positives first, then negatives.
export const RATING_TAG_GROUPS: readonly RatingTagGroup[] = [
  {
    sentiment: 'positive',
    tags: ['Friendly', 'Respectful', 'On time', 'Good communication', 'Would travel again']
  },
  {
    sentiment: 'negative',
    tags: ["Didn't show up", 'Fake destination', 'Abusive', 'Spam', 'Asked for money']
  }
];

// Membership lookup derived from the config above -- the ONLY place that
// answers "is this tag negative?". Used for the chip colour and for the
// "Negative reports may affect this user's account." warning.
const NEGATIVE_TAGS: ReadonlySet<string> = new Set(
  RATING_TAG_GROUPS.filter(group => group.sentiment === 'negative').flatMap(group => group.tags)
);

export function isNegativeTag(tag: string): boolean {
  return NEGATIVE_TAGS.has(tag);
}
