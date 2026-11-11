-- V3: Idempotent consumer table

CREATE TABLE processed_events (
    id              BIGSERIAL PRIMARY KEY,
    topic           VARCHAR(255) NOT NULL,
    partition_num   INTEGER NOT NULL,
    offset_num      BIGINT NOT NULL,
    event_id        UUID NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (topic, partition_num, offset_num)
);
