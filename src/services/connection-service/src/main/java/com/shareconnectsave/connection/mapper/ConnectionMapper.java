package com.shareconnectsave.connection.mapper;

import com.shareconnectsave.connection.domain.ConnectionRequest;
import com.shareconnectsave.connection.domain.ConnectionResponse;
import org.mapstruct.Mapper;

// Pattern: MapStruct generates this implementation at compile time — zero
// reflection overhead, and it closes two classic hand-rolled-mapper bugs on
// the TARGET (ConnectionResponse) side: (1) add a same-named field to both
// ConnectionRequest and ConnectionResponse but forget the matching
// assignment line in a hand-written toResponse() — compiles fine, field
// silently stays null forever; MapStruct wires same-named properties on its
// own, so there's no line to forget. (2) add a field to ConnectionResponse
// alone, with nothing matching on ConnectionRequest — a hand-rolled mapper
// still compiles and silently defaults it to null, but MapStruct fails the
// build on this unmapped target property until a @Mapping supplies it (or
// explicitly ignores it). An unmapped SOURCE property, like
// ConnectionRequest.updatedAt below, is never an error either way.
// componentModel = "spring" registers the generated class as a Spring bean,
// so ConnectionServiceImpl injects this interface like any other
// collaborator (Dependency Inversion) without ever referencing the
// generated ConnectionMapperImpl class by name.
@Mapper(componentModel = "spring")
public interface ConnectionMapper {

    // No @Mapping annotations needed: ConnectionResponse's component names
    // (id, requesterId, recipientId, status, createdAt, expiresAt) match
    // ConnectionRequest's property names exactly, so MapStruct wires each
    // one by name on its own. ConnectionRequest.updatedAt has no
    // corresponding target field — an unmapped SOURCE property is fine,
    // MapStruct only warns/fails on an unmapped TARGET property.
    ConnectionResponse toResponse(ConnectionRequest connectionRequest);
}
