// Wire DTOs for the three /users/me* endpoints this feature calls, named and
// cased (snake_case) to match UserProfileDto / UpdateProfileRequest exactly
// as user-service serializes them -- same discipline as auth/models/auth.models.ts:
// the wire shape is written down explicitly so a backend contract drift shows
// up as a TypeScript mismatch, not a silently-undefined field at runtime.

// Interface Segregation (I): this is its own small contract file for the
// profile domain, separate from auth's models -- a consumer of profile DTOs
// never has to pull in (or know about) GoogleAuthApiResponse/OtpVerifyApiResponse,
// and vice versa.
export type Gender = 'female' | 'male' | 'unspecified';
export type AvailabilityStatus = 'looking' | 'unavailable';

export interface UserProfileDto {
  id: number;
  name: string;
  email: string | null;
  phone: string | null;
  photo_url: string | null;
  gender: Gender;
  preferred_language: string;
  status: AvailabilityStatus;
  phone_verified: boolean;
  onboarding_complete: boolean;
  identity_badge: boolean;
  created_at: string;
}

// PATCH semantics (backend: "all fields optional -- only send what you're
// setting") -- every field here is optional so a caller can send a partial
// object instead of resending the whole profile.
export interface UpdateProfileRequest {
  name?: string;
  preferred_language?: string;
  gender?: Gender;
  status?: AvailabilityStatus;
}

// Reissue-on-claim-change (named in the ticket's "Patterns demonstrated"):
// access_token is present in the JSON only when a JWT-carried claim
// (onboarding_complete) actually changed as a result of this call -- both
// PATCH /users/me and POST /users/me/photo share this exact response shape,
// so one type covers both.
export interface ProfileMutationApiResponse {
  profile: UserProfileDto;
  access_token?: string;
}
