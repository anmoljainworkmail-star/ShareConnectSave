package com.shareconnectsave.rating;

import com.shareconnectsave.rating.domain.RatingResponse;
import com.shareconnectsave.rating.domain.RatingSubmissionDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Pattern: thin controller - this method only translates HTTP in/out and
// delegates to RatingService, matching the user's own "every controller
// delegates to a service layer" preference (already applied in Connection
// Service's ConnectionController).
//
// Depends on the RatingService interface, not RatingServiceImpl -
// Dependency Inversion (SOLID-D).
//
// API Gateway (trust boundary): X-User-Id is read as a plain Long header,
// already validated and injected by YARP at the gateway after JWT
// verification - this controller never decodes a JWT itself.
@RestController
@RequestMapping("/ratings")
@RequiredArgsConstructor
public class RatingController {

    private final RatingService ratingService;

    @PostMapping
    public ResponseEntity<RatingResponse> submitRating(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody RatingSubmissionDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ratingService.submitRating(userId, dto));
    }
}
