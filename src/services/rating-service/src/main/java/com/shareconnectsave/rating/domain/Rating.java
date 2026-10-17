package com.shareconnectsave.rating.domain;

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

// Maps onto the ratings table from V001__rating_service_initial_schema.sql
// (T006/T041). Lombok @Getter + @Builder + @NoArgsConstructor +
// @AllArgsConstructor, never @Data, per the java-spring-boot skill: @Data
// would generate equals()/hashCode() over every field, which breaks Hibernate
// lazy-loading/identity semantics on an @Entity.
//
// Database per Service: raterId/ratedId are logical references to
// UserServiceDb's own `users` table, and connectionId is a logical reference
// to ConnectionServiceDb's `connection_requests` table — both are plain
// BIGINT columns here, never a JPA @ManyToOne/physical FK, because Rating
// Service's own database connection has no access to either of those other
// databases. Discovery/Connection/User services never learn about a rating
// through a join; the only legitimate channel is the rating.submitted Kafka
// event a later ticket publishes.
@Entity
@Table(name = "ratings")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rater_id", nullable = false)
    private Long raterId;

    @Column(name = "rated_id", nullable = false)
    private Long ratedId;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    // Open/Closed Principle (SOLID-O): tags is a JSON array stored as plain
    // text, not one boolean column per tag or a normalized rating_tags join
    // table. Adding a new tag to the RatingTag list (contracts/openapi/
    // rating-service.yaml) is an application-level config change — zero
    // schema migrations required. Deliberately typed as a plain String here,
    // not a List<String>: entity classes in this project stay simple data
    // containers (per this ticket's "What NOT to do" list, no validation at
    // the entity level) — a later ticket's mapper/service layer is
    // responsible for serializing/deserializing this column to/from a real
    // List<RatingTag>, not this class.
    @Column(name = "tags", columnDefinition = "NVARCHAR(MAX)")
    private String tags;

    // insertable/updatable = false: the DDL already defines
    // `created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()` — letting SQL
    // Server's own default fire means this timestamp reflects the moment the
    // row actually committed, not the moment this app server built the
    // object in memory (same reasoning as ScanSession.startedAt in
    // discovery-service).
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
