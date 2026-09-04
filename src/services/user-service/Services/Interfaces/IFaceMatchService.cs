namespace user_service.Services.Interfaces;

// Dependency Inversion (SOLID D) + Strategy (classic pattern, per CLAUDE.md's
// pattern table): IdentityVerificationService depends on this abstraction,
// never on a concrete Azure/stub class. Program.cs is the ONLY place that
// decides which implementation is wired in (Azure-backed vs stub-backed),
// selected once at startup by the IDENTITY_VERIFY_STUB env flag - not an
// "if (isDev)" branch sprinkled through the business logic. Swapping face-
// match providers later (Open/Closed, SOLID O) means adding a new
// IFaceMatchService implementation and changing one Program.cs registration
// line, never editing IdentityVerificationService or the controller.
//
// Deliberately narrow (Interface Segregation, SOLID I): a face-match
// provider only ever needs to answer one question - "do these two images
// show the same person" - so that is the only method on this contract.
public interface IFaceMatchService
{
    Task<FaceMatchOutcome> CompareAsync(byte[] basePhotoBytes, byte[] selfieBytes, CancellationToken cancellationToken);
}

// Result Object (same convention as IOtpService/IUserProfileService's
// outcome types): deliberately does NOT carry the raw confidence score or
// any provider-specific detail (e.g. Azure's isIdentical flag, faceIds) past
// this boundary - IdentityVerificationController must never leak biometric
// matching internals to the client (this ticket's explicit "what NOT to do"
// rule), and the cleanest way to guarantee that is to not let those details
// escape the face-match layer in the first place.
public record FaceMatchOutcome(bool IsMatch);
