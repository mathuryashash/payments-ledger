package com.yashash.ledger.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A Transfer groups one or more journal Entries that together must balance:
 * sum(debit amounts) == sum(credit amounts) per currency.
 * Transfers are immutable once committed; reversals create a new compensating Transfer.
 */
@Entity
@Table(name = "transfers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transfer {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Client-supplied idempotency key (hashed with SHA-256 before storage).
     * The hash is stored so the original key never appears in DB query logs.
     */
    @Column(name = "idempotency_key_hash", nullable = false, unique = true, length = 64, columnDefinition = "VARCHAR(64)")
    private String idempotencyKeyHash;

    @Column(name = "reference", length = 255)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransferStatus status;

    /** Points to the original transfer if this is a reversal. */
    @Column(name = "reversal_of_id")
    private UUID reversalOfId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public enum TransferStatus {
        PENDING, POSTED, REVERSED, FAILED
    }
}
