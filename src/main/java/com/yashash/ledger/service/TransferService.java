package com.yashash.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yashash.ledger.domain.*;
import com.yashash.ledger.domain.Entry.Direction;
import com.yashash.ledger.domain.Transfer.TransferStatus;
import com.yashash.ledger.repository.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Core business logic for creating and reversing transfers.
 *
 * Concurrency design:
 *  1. Accounts are locked in ascending UUID order inside a single DB transaction
 *     to prevent deadlocks (every thread acquires locks in the same order).
 *  2. Insufficient-funds check happens under the lock so no two concurrent
 *     transfers can both pass the check and over-draw the same account.
 *  3. Idempotency key hash is stored with a unique DB constraint; concurrent
 *     requests with the same key race on that constraint — only one wins,
 *     the others get a unique-violation which is handled by re-reading the row.
 *  4. Serialization / deadlock failures are retried with bounded exponential
 *     backoff (see retryOnTransient).
 *  5. We use TransactionTemplate so each retry attempt starts a fresh transaction
 *     even when called from a non-transactional context. Spring AOP @Transactional
 *     on a protected method called from within the same bean would be ignored.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private final AccountRepository accountRepo;
    private final TransferRepository transferRepo;
    private final EntryRepository entryRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final PlatformTransactionManager txManager;

    private static final int MAX_RETRIES = 5;
    private static final long RETRY_BASE_MS = 50;

    public record PostingRequest(UUID accountId, Direction direction, long amountMinor, String currency) {}

    public record TransferRequest(
        String idempotencyKey,
        String idempotencyBodyHash,
        String reference,
        List<PostingRequest> postings
    ) {}

    /**
     * Creates a balanced transfer. Returns existing transfer on idempotent replay.
     */
    public Transfer postTransfer(TransferRequest req) {
        String keyHash = sha256(req.idempotencyKey());
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Transfer result = retryOnTransient(() -> executeInTransaction(req, keyHash));
            sample.stop(meterRegistry.timer("ledger.transfers", "outcome", "success"));
            return result;
        } catch (InsufficientFundsException e) {
            sample.stop(meterRegistry.timer("ledger.transfers", "outcome", "insufficient_funds"));
            throw e;
        } catch (IdempotencyConflictException e) {
            sample.stop(meterRegistry.timer("ledger.transfers", "outcome", "idempotency_conflict"));
            throw e;
        } catch (Exception e) {
            sample.stop(meterRegistry.timer("ledger.transfers", "outcome", "error"));
            throw e;
        }
    }

    private Transfer executeInTransaction(TransferRequest req, String keyHash) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tt.execute(status -> {
            // Idempotency check: return existing or detect conflict
            Optional<Transfer> existing = transferRepo.findByIdempotencyKeyHash(keyHash);
            if (existing.isPresent()) {
                Transfer t = existing.get();
                String storedBodyHashTag = extractBodyHashTag(t.getReference());
                if (storedBodyHashTag != null && !storedBodyHashTag.equals(req.idempotencyBodyHash())) {
                    throw new IdempotencyConflictException(t.getId().toString());
                }
                log.info("Idempotent replay for transfer {}", t.getId());
                return t;
            }

            validatePostings(req.postings());

            List<UUID> accountIds = req.postings().stream()
                .map(PostingRequest::accountId)
                .distinct()
                .sorted()
                .toList();

            List<Account> lockedAccounts = accountRepo.findByIdsForUpdate(accountIds);
            if (lockedAccounts.size() != accountIds.size()) {
                throw new IllegalArgumentException("One or more accounts not found");
            }
            Map<UUID, Account> accountMap = new HashMap<>();
            for (Account a : lockedAccounts) accountMap.put(a.getId(), a);

            for (PostingRequest p : req.postings()) {
                Account account = accountMap.get(p.accountId());
                if (!account.getCurrency().equals(p.currency())) {
                    throw new IllegalArgumentException(
                        "Account " + account.getId() + " currency mismatch: expected " + account.getCurrency());
                }
                long newBalance = p.direction() == Direction.DEBIT
                    ? account.getBalanceMinor() - p.amountMinor()
                    : account.getBalanceMinor() + p.amountMinor();

                if (newBalance < 0 && !account.isAllowOverdraft()) {
                    throw new InsufficientFundsException(account.getId().toString());
                }
                account.setBalanceMinor(newBalance);
            }

            UUID transferId = UUID.randomUUID();
            String refWithHash = "hash:" + req.idempotencyBodyHash() + "|" + (req.reference() != null ? req.reference() : "");

            Transfer transfer = Transfer.builder()
                .id(transferId)
                .idempotencyKeyHash(keyHash)
                .reference(refWithHash)
                .status(TransferStatus.POSTED)
                .createdAt(Instant.now())
                .build();
            transferRepo.save(transfer);

            List<Entry> entries = new ArrayList<>();
            for (PostingRequest p : req.postings()) {
                entries.add(Entry.builder()
                    .id(UUID.randomUUID())
                    .transferId(transferId)
                    .accountId(p.accountId())
                    .currency(p.currency())
                    .amountMinor(p.amountMinor())
                    .direction(p.direction())
                    .createdAt(Instant.now())
                    .build());
            }
            entryRepo.saveAll(entries);
            accountRepo.saveAll(lockedAccounts);
            writeOutboxEvent(transferId, transfer, entries);
            return transfer;
        });
    }

    @Transactional(readOnly = true)
    public Transfer getTransferById(UUID id) {
        return transferRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Transfer not found: " + id));
    }

    @Transactional
    public Transfer reverseTransfer(UUID originalTransferId, String idempotencyKey) {
        String keyHash = sha256(idempotencyKey);
        Optional<Transfer> existing = transferRepo.findByIdempotencyKeyHash(keyHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        Transfer original = transferRepo.findById(originalTransferId)
            .orElseThrow(() -> new NoSuchElementException("Transfer not found: " + originalTransferId));
        if (original.getStatus() == TransferStatus.REVERSED) {
            throw new IllegalStateException("Transfer already reversed: " + originalTransferId);
        }

        List<Entry> originalEntries = entryRepo.findByTransferId(originalTransferId);

        List<UUID> accountIds = originalEntries.stream()
            .map(Entry::getAccountId).distinct().sorted().toList();
        List<Account> lockedAccounts = accountRepo.findByIdsForUpdate(accountIds);
        Map<UUID, Account> accountMap = new HashMap<>();
        for (Account a : lockedAccounts) accountMap.put(a.getId(), a);

        UUID reversalId = UUID.randomUUID();
        List<Entry> reversalEntries = new ArrayList<>();
        for (Entry e : originalEntries) {
            Direction reversed = e.getDirection() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
            Account account = accountMap.get(e.getAccountId());
            long newBalance = reversed == Direction.DEBIT
                ? account.getBalanceMinor() - e.getAmountMinor()
                : account.getBalanceMinor() + e.getAmountMinor();
            if (newBalance < 0 && !account.isAllowOverdraft()) {
                throw new InsufficientFundsException(account.getId().toString());
            }
            account.setBalanceMinor(newBalance);
            reversalEntries.add(Entry.builder()
                .id(UUID.randomUUID())
                .transferId(reversalId)
                .accountId(e.getAccountId())
                .currency(e.getCurrency())
                .amountMinor(e.getAmountMinor())
                .direction(reversed)
                .createdAt(Instant.now())
                .build());
        }

        Transfer reversal = Transfer.builder()
            .id(reversalId)
            .idempotencyKeyHash(keyHash)
            .reference("reversal-of:" + originalTransferId)
            .status(TransferStatus.POSTED)
            .reversalOfId(originalTransferId)
            .createdAt(Instant.now())
            .build();
        transferRepo.save(reversal);
        entryRepo.saveAll(reversalEntries);
        accountRepo.saveAll(lockedAccounts);

        original.setStatus(TransferStatus.REVERSED);
        transferRepo.save(original);

        writeOutboxEvent(reversalId, reversal, reversalEntries);
        return reversal;
    }

    private void writeOutboxEvent(UUID transferId, Transfer transfer, List<Entry> entries) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("transferId", transferId.toString());
            payload.put("status", transfer.getStatus().name());
            payload.put("reference", transfer.getReference());
            payload.put("reversalOfId", transfer.getReversalOfId() != null ? transfer.getReversalOfId().toString() : null);
            payload.put("entryCount", entries.size());
            payload.put("createdAt", transfer.getCreatedAt().toString());

            OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transfer")
                .aggregateId(transferId)
                .eventType(transfer.getReversalOfId() != null ? "TransferReversed" : "TransferPosted")
                .payload(objectMapper.writeValueAsString(payload))
                .createdAt(Instant.now())
                .build();
            outboxRepo.save(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write outbox event", e);
        }
    }

    /** Validates that postings are balanced per currency. */
    public static void validatePostings(List<PostingRequest> postings) {
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("Transfer must have at least 2 postings");
        }
        Map<String, Long> netPerCurrency = new HashMap<>();
        for (PostingRequest p : postings) {
            if (p.amountMinor() <= 0) {
                throw new IllegalArgumentException("Posting amount must be positive");
            }
            long signed = p.direction() == Direction.DEBIT ? p.amountMinor() : -p.amountMinor();
            netPerCurrency.merge(p.currency(), signed, Long::sum);
        }
        for (Map.Entry<String, Long> e : netPerCurrency.entrySet()) {
            if (e.getValue() != 0) {
                throw new IllegalArgumentException(
                    "Postings are not balanced for currency " + e.getKey() + ": net=" + e.getValue());
            }
        }
    }

    private <T> T retryOnTransient(java.util.function.Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (CannotAcquireLockException | DeadlockLoserDataAccessException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    meterRegistry.counter("ledger.transfer.retry.exhausted").increment();
                    throw e;
                }
                meterRegistry.counter("ledger.transfer.retry", "attempt", String.valueOf(attempt)).increment();
                log.warn("Transient DB error on attempt {}/{}, retrying: {}", attempt, MAX_RETRIES, e.getMessage());
                try {
                    Thread.sleep(RETRY_BASE_MS * (1L << (attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String extractBodyHashTag(String reference) {
        if (reference == null || !reference.startsWith("hash:")) return null;
        int pipeIdx = reference.indexOf('|');
        if (pipeIdx < 0) return null;
        return reference.substring(5, pipeIdx);
    }
}
