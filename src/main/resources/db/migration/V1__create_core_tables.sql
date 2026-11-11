-- V1: Core tables for double-entry ledger

CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    type            VARCHAR(20) NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    balance_minor   BIGINT NOT NULL DEFAULT 0,
    allow_overdraft BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
    id                      UUID PRIMARY KEY,
    idempotency_key_hash    VARCHAR(64) NOT NULL UNIQUE,
    reference               VARCHAR(255),
    status                  VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','POSTED','REVERSED','FAILED')),
    reversal_of_id          UUID REFERENCES transfers(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE entries (
    id              UUID PRIMARY KEY,
    transfer_id     UUID NOT NULL REFERENCES transfers(id),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    currency        VARCHAR(3) NOT NULL,
    amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
    direction       VARCHAR(10) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for keyset pagination on entries
CREATE INDEX idx_entries_account_created ON entries(account_id, created_at, id);
CREATE INDEX idx_entries_transfer ON entries(transfer_id);
