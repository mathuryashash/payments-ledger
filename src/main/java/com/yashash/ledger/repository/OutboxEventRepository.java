package com.yashash.ledger.repository;

import com.yashash.ledger.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Selects unpublished events with SKIP LOCKED so multiple relay instances
     * don't compete for the same rows — each instance processes a distinct batch.
     * The FOR UPDATE SKIP LOCKED is in the SQL itself; no JPA @Lock needed.
     * This is a native query so the transaction must be active at call site.
     */
    @Query(value = "SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<OutboxEvent> findUnpublishedForUpdate(@Param("limit") int limit);
}
