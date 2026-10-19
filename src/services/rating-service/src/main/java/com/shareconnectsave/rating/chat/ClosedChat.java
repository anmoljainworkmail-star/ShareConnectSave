package com.shareconnectsave.rating.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Maps onto the closed_chats table from V003__rating_service_consumer_tables.sql
// (T044). Single Responsibility (SOLID-S): this table exists for exactly one
// reason - letting ClosedChatBackedChatClosureChecker answer "has this
// connection's chat actually closed?" without Rating Service ever querying
// Chat Service's own MongoDB directly (Database per Service). It is
// deliberately NOT a copy of the chat room itself (no message history, no
// closed_reason) - Rating Service only ever needs the one boolean fact this
// row's existence already represents.
//
// Lombok: @Getter + @Builder + @NoArgsConstructor + @AllArgsConstructor, not
// @Data - same reasoning as every other @Entity in this codebase (Rating,
// TrustScore, OutboxEvent): @Data's generated equals()/hashCode() over every
// field breaks Hibernate's identity/lazy-loading assumptions.
@Entity
@Table(name = "closed_chats")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClosedChat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Database per Service: a logical reference to ConnectionServiceDb's own
    // connection_requests table, stored as a plain BIGINT - never a physical
    // cross-database FOREIGN KEY, same reasoning as Rating.connectionId's own
    // comment. The chat.closed event is the only legitimate channel Rating
    // Service learns this through.
    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "user_a_id", nullable = false)
    private Long userAId;

    @Column(name = "user_b_id", nullable = false)
    private Long userBId;

    // insertable/updatable = false: SQL Server's own DEFAULT SYSUTCDATETIME()
    // is this row's ground truth for "when did Rating Service learn this chat
    // closed" - same convention as Rating.createdAt and OutboxEvent.createdAt.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
