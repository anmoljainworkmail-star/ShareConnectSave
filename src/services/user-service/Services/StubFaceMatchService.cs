namespace user_service.Services;

using user_service.Services.Interfaces;

// Strategy (classic pattern) - dev-only implementation of IFaceMatchService.
// Wired in by Program.cs ONLY when IDENTITY_VERIFY_STUB=true, so local
// development and CI can exercise every branch of the verify-identity flow
// (guard clause, persistence, badge flip) without a real Azure Face API
// subscription - same reasoning as this service having no real Twilio
// account provisioned for T017's local dev/testing.
//
// Ticket rule: always returns verified (IsMatch: true), unconditionally - no
// network call is made, which is the entire point (Azure Face API is a
// paid, network-bound external call; the stub exists specifically so dev
// work never pays that cost or needs real credentials).
public class StubFaceMatchService : IFaceMatchService
{
    public Task<FaceMatchOutcome> CompareAsync(byte[] basePhotoBytes, byte[] selfieBytes, CancellationToken cancellationToken) =>
        Task.FromResult(new FaceMatchOutcome(IsMatch: true));
}
