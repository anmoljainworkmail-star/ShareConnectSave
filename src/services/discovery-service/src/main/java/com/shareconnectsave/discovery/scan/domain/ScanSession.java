package com.shareconnectsave.discovery.scan.domain;

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

// Maps onto the scan_sessions table created by V001__discovery_service_initial_schema.sql
// (T006). Deliberately does NOT map the `destination` geography column: that
// column is `AS geography::Point(destination_lat, destination_lng, 4326) PERSISTED`
// in the DDL — SQL Server computes and stores it itself from the two lat/lng
// columns below, so mapping it here would only tempt Hibernate into trying to
// write a column the database owns. Hibernate's ddl-auto=validate only checks
// that mapped columns exist against the real schema, so leaving a column
// unmapped is safe.
@Entity
@Table(name = "scan_sessions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "destination_lat", nullable = false)
    private Double destinationLat;

    @Column(name = "destination_lng", nullable = false)
    private Double destinationLng;

    @Column(name = "destination_label")
    private String destinationLabel;

    @Column(name = "departure_time")
    private Instant departureTime;

    // insertable/updatable = false: the DDL already defines
    // `started_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()`. Letting SQL
    // Server's own default fire (instead of setting Instant.now() here) means
    // the timestamp reflects the moment the row actually committed rather
    // than the moment this app-server instance built the object in memory —
    // the two can drift under clock skew or GC pause, and only one of them is
    // the DB's ground truth.
    @Column(name = "started_at", insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    public boolean isActive() {
        return endedAt == null;
    }

    public void close() {
        this.endedAt = Instant.now();
    }
}
