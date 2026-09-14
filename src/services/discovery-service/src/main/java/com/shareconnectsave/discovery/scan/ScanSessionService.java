package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.ScanLocationRequest;
import com.shareconnectsave.discovery.scan.domain.ScanStartRequest;
import com.shareconnectsave.discovery.scan.domain.ScanStartResponse;

// Pattern: Dependency Inversion (SOLID-D) — ScanController depends on this
// abstraction, never on ScanSessionServiceImpl directly, the same shape as
// ITwilioClient in User Service. The concrete bean Spring wires in behind
// this interface can be swapped (a different implementation, a stub for a
// test/dev profile) without touching ScanController at all — see
// ScanSessionServiceImpl's class comment for how that swap would be
// registered via @Profile/@ConditionalOnProperty, mirroring Program.cs's
// TWILIO_STUB-gated ITwilioClient registration.
public interface ScanSessionService {

    // gender is a separate parameter, not a ScanStartRequest field: it comes
    // from the gateway-trusted X-User-Gender header (JWT Identity rule —
    // read from headers only, never let a client assert it in a JSON body).
    ScanStartResponse startScan(Long userId, String gender, ScanStartRequest request);

    void stopScan(Long userId);

    void updateLocation(Long userId, ScanLocationRequest request);
}
