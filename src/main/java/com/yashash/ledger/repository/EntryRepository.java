package com.yashash.ledger.repository;

import com.yashash.ledger.domain.Entry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EntryRepository extends JpaRepository<Entry, UUID> {

    List<Entry> findByTransferId(UUID transferId);

    /**
     * Keyset pagination: returns entries for an account after a given cursor (createdAt, id).
     * Caller passes the createdAt+id from the last item in the previous page.
     */
    @Query("""
        SELECT e FROM Entry e
        WHERE e.accountId = :accountId
          AND (e.createdAt > :afterTs OR (e.createdAt = :afterTs AND e.id > :afterId))
        ORDER BY e.createdAt ASC, e.id ASC
    """)
    List<Entry> findByAccountIdAfterCursor(
        @Param("accountId") UUID accountId,
        @Param("afterTs") Instant afterTs,
        @Param("afterId") UUID afterId,
        Pageable pageable
    );

    @Query("SELECT e FROM Entry e WHERE e.accountId = :accountId ORDER BY e.createdAt ASC, e.id ASC")
    List<Entry> findByAccountIdFirstPage(@Param("accountId") UUID accountId, Pageable pageable);
}
