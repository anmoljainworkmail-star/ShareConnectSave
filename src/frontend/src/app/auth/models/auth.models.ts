// Wire DTOs for the four /auth/* endpoints this module calls, named with an
// "Api" suffix and kept snake_case to match the exact JSON user-service
// sends ([JsonPropertyName] on the C# side -- see AuthController.cs /
// OtpController.cs). Same discipline as the backend's own co-located
// request/response records: the wire shape is written down explicitly,
// never inferred from a camelCase guess, so a contract drift is a visible
// interface mismatch instead of a silently-undefined field at runtime.
export interface GoogleAuthApiResponse {
  access_token: string;
  refresh_token: string;
  is_new_user: boolean;
}

export interface OtpSendApiResponse {
  sent: boolean;
}

// access_token is present ONLY when this verification flipped the caller's
// onboarding-complete state (backend: [JsonIgnore(WhenWritingNull)] on
// OtpVerifyResponse.AccessToken) -- most verifications leave the
// already-issued Google-step access token as the current one.
export interface OtpVerifyApiResponse {
  phone_verified: boolean;
  onboarding_complete: boolean;
  access_token?: string;
}

export interface RefreshApiResponse {
  access_token: string;
}

export interface GoogleSignInRequest {
  idToken: string;
}

export interface OtpSendRequest {
  phoneNumber: string;
}

export interface OtpVerifyRequest {
  phoneNumber: string;
  code: string;
}

// Error envelope shape every backend service returns on failure (see
// CLAUDE.md's "Error Envelope" architecture rule: { code, message, traceId }
// on every error, from every service). AuthService normalizes every HTTP
// failure into this one shape so components never need to know which
// microservice answered, or branch on transport vs. domain errors.
export interface ErrorEnvelope {
  code: string;
  message: string;
  traceId: string;
}
