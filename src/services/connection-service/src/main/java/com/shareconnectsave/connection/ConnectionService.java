package com.shareconnectsave.connection;

import com.shareconnectsave.connection.domain.ConnectionCreatedResponse;
import com.shareconnectsave.connection.domain.ConnectionResponse;
import com.shareconnectsave.connection.domain.CreateConnectionDto;

import java.util.List;
import java.util.Optional;

// Dependency Inversion (SOLID-D): ConnectionController depends on THIS
// interface, never on ConnectionServiceImpl directly — same shape as
// discovery-service's ScanSessionService / ScanQueryService pair. Whichever
// @Service bean Spring wires in behind it, the controller never has to
// change, and a unit test of the controller can substitute a hand-written
// fake with no Spring context at all.
public interface ConnectionService {

    ConnectionCreatedResponse createConnection(Long requesterId, CreateConnectionDto dto);

    ConnectionResponse acceptConnection(Long connectionId, Long callerId);

    ConnectionResponse declineConnection(Long connectionId, Long callerId);

    List<ConnectionResponse> getPendingInbound(Long userId);

    Optional<ConnectionResponse> getActiveConnection(Long userId);
}
