package com.shareconnectsave.connection;

import com.shareconnectsave.connection.domain.ConnectionCreatedResponse;
import com.shareconnectsave.connection.domain.ConnectionResponse;
import com.shareconnectsave.connection.domain.CreateConnectionDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Pattern: thin controller — every method only translates HTTP in/out and
// delegates to ConnectionService, matching the user's own "every controller
// delegates to a service layer" preference (already applied in User
// Service's UserProfileController and discovery-service's ScanController).
//
// Depends on the ConnectionService interface, not ConnectionServiceImpl —
// Dependency Inversion (SOLID-D), same shape as ScanController's dependency
// on ScanSessionService.
//
// JWT Identity / API Gateway (trust boundary): X-User-Id is read as a plain
// Long header, already validated and injected by YARP at the gateway — this
// controller never decodes a JWT itself.
@RestController
@RequestMapping("/connections")
@RequiredArgsConstructor
public class ConnectionController {

    private final ConnectionService connectionService;

    @PostMapping
    public ResponseEntity<ConnectionCreatedResponse> createConnection(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CreateConnectionDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(connectionService.createConnection(userId, dto));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<ConnectionResponse> acceptConnection(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id) {
        return ResponseEntity.ok(connectionService.acceptConnection(id, userId));
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<ConnectionResponse> declineConnection(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id) {
        return ResponseEntity.ok(connectionService.declineConnection(id, userId));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<ConnectionResponse>> getPendingConnections(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(connectionService.getPendingInbound(userId));
    }

    // 200 with a body when an active connection exists, 204 when it
    // doesn't — "may not exist" GET, same choice discovery-service's
    // ScanController makes for /scan/stop-shaped "nothing to return"
    // outcomes (there noContent() on the mutating call; here on the read,
    // since there is genuinely no resource to describe rather than an
    // error).
    @GetMapping("/active")
    public ResponseEntity<ConnectionResponse> getActiveConnection(
            @RequestHeader("X-User-Id") Long userId) {
        return connectionService.getActiveConnection(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
