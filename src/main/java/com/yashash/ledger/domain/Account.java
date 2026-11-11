package com.yashash.ledger.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An account holds a balance in one currency.
 * Balances are stored as BIGINT minor units (e.g. cents for USD) to avoid floating point.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    /** ISO 4217 currency code, e.g. USD, EUR, INR */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private AccountType type;

    /**
     * Materialized balance in minor units (cents etc.).
     * Kept consistent with journal entries inside the same transaction.
     */
    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    /**
     * When true, balance may go below zero (e.g. a credit card account).
     */
    @Column(name = "allow_overdraft", nullable = false)
    private boolean allowOverdraft;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public enum AccountType {
        ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE
    }
}
