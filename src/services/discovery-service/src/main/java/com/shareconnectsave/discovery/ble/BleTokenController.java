package com.shareconnectsave.discovery.ble;

import com.shareconnectsave.discovery.ble.domain.BleResolveRequest;
import com.shareconnectsave.discovery.ble.domain.BleSeedResponse;
import com.shareconnectsave.discovery.scan.domain.UserCardResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Pattern: thin controller, same shape as ScanController — every method
// only translates HTTP in/out and delegates to BleTokenService. A separate
// controller from ScanController (not a third method bolted onto it):
// BLE token issuance/resolution is a distinct feature concern from the GPS
// scan lifecycle, even though both live under discovery.
@RestController
@RequestMapping("/scan/ble")
@RequiredArgsConstructor
public class BleTokenController {

    private final BleTokenService bleTokenService;

    // v2 (T024 rework): replaces v1's /token. Called once per scan session
    // (or on seed expiry), not every 5 minutes — the client derives every
    // future rotating broadcast value from this one seed entirely offline.
    // JWT Identity: X-User-Id is gateway-injected and trusted, same
    // convention as every other endpoint in this service — this controller
    // never decodes a JWT itself.
    @PostMapping("/seed")
    public ResponseEntity<BleSeedResponse> issueSeed(@RequestHeader("X-User-Id") Long userId) {
        String rawSeed = bleTokenService.issueSeed(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new BleSeedResponse(rawSeed));
    }

    // X-User-Id IS now required here (unlike this method's earlier version)
    // — Tech Lead review of this ticket found that /resolve had no way to
    // filter out blocked users, unlike GPS discovery's /scan/nearby, which
    // excludes any candidate the caller has blocked or who has blocked the
    // caller before ever returning a card. BleTokenServiceImpl.resolveTokens
    // now does the same bidirectional check, which needs the caller's own
    // identity to run at all. JWT Identity: this header is gateway-injected
    // and trusted, same convention as every other endpoint in this service
    // — this controller never decodes a JWT itself.
    @PostMapping("/resolve")
    public ResponseEntity<List<UserCardResponse>> resolveTokens(@RequestHeader("X-User-Id") Long callerUserId, @Valid @RequestBody BleResolveRequest request) {
        return ResponseEntity.ok(bleTokenService.resolveTokens(callerUserId, request.tokens()));
    }
}
