package com.yashash.ledger.service;

/** Thrown when idempotency key is reused with a different request body. */
public class IdempotencyConflictException extends RuntimeException {
    private final String existingTransferId;

    public IdempotencyConflictException(String existingTransferId) {
        super("Idempotency key already used for a different request body");
        this.existingTransferId = existingTransferId;
    }

    public String getExistingTransferId() {
        return existingTransferId;
    }
}
