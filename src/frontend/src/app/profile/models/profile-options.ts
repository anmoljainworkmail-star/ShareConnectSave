import { Gender } from '../../profile-setup/models/profile-setup.models';

// Open/Closed (O) + DRY: gender and language choices are config, defined once
// and imported by every screen that renders them (Profile Setup, Edit
// Profile). Adding a language is one entry here -- never a validator or
// template change -- and the two screens can no longer drift apart.
// Moved out of ProfileSetupComponent (T070) when a second consumer appeared.
export const GENDER_OPTIONS: ReadonlyArray<{ value: Gender; label: string }> = [
  { value: 'male', label: 'Male' },
  { value: 'female', label: 'Female' },
  { value: 'unspecified', label: 'Prefer not to say' }
];

// Not Figma-specified (Screens 04/16 show "a dropdown" with no option list) --
// a flagged implementation decision, kept small and explicit.
// preferred_language is a free-form string to the backend.
export const LANGUAGE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'en', label: 'English' },
  { code: 'hi', label: 'Hindi' },
  { code: 'es', label: 'Spanish' },
  { code: 'fr', label: 'French' },
  { code: 'ar', label: 'Arabic' },
  { code: 'pt', label: 'Portuguese' },
  { code: 'zh', label: 'Mandarin Chinese' }
];
