package com.yashash.ledger.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks which Kafka events have been processed by this consumer.
 * Stored per (topic, partition, offset) so redeliveries are safely skipped.
 */
@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    @Column(name = "partition_num", nullable = false)
    private int partitionNum;

    @Column(name = "offset_num", nullable = false)
    private long offsetNum;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (processedAt == null) processedAt = Instant.now();
    }
}
