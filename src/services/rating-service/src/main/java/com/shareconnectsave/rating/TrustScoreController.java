package com.shareconnectsave.rating;

import com.shareconnectsave.rating.domain.TrustScoreQueryResponse;
import com.shareconnectsave.rating.trustscore.TrustScoreReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Pattern: thin controller - this method only translates HTTP in/out and
// delegates to TrustScoreReadService, matching the user's own "every
// controller delegates to a service layer" preference, same shape as
// RatingController.
//
// Depends on the TrustScoreReadService interface, not the impl -
// Dependency Inversion (SOLID-D).
//
// Internal, service-to-service endpoint (T045) - unlike RatingController's
// gateway-fronted /ratings routes, GET /trust/:userId carries no X-User-Id
// header requirement and is never routed through YARP. Connection Service's
// RatingServiceClient (T045) calls this directly over the Docker network to
// close the Cache-Aside read gap left by T033's write-only
// InMemoryRequestLimitCache - the same trust-boundary shape as
// discovery-service's UserServiceClient calling User Service directly (see
// that client's own "this call never crosses the API Gateway" comment).
// Admin Service is this endpoint's other documented caller (ticket spec),
// also an internal, Docker-network-only caller.
@RestController
@RequestMapping("/trust")
@RequiredArgsConstructor
public class TrustScoreController {

    private final TrustScoreReadService trustScoreReadService;

    @GetMapping("/{userId}")
    public ResponseEntity<TrustScoreQueryResponse> getTrustScore(@PathVariable Long userId) {
        return ResponseEntity.ok(trustScoreReadService.getTrustData(userId));
    }
}
