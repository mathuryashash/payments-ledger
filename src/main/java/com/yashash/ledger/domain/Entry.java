package com.yashash.ledger.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single journal entry (posting) belonging to a transfer.
 * DEBIT increases ASSET/EXPENSE; CREDIT increases LIABILITY/EQUITY/REVENUE.
 * Every transfer must have balanced postings: total debits == total credits per currency.
 */
@Entity
@Table(name = "entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Entry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    /** ISO 4217 currency code */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Amount in minor units (always positive) */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private Direction direction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public enum Direction {
        DEBIT, CREDIT
    }
}
