package com.yashash.ledger.service;

/**
 * Thrown when a transfer would cause a non-overdraft account to go negative.
 */
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String accountId) {
        super("Insufficient funds in account: " + accountId);
    }
}
