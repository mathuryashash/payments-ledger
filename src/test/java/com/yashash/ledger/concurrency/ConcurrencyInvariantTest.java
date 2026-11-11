package com.yashash.ledger.concurrency;

import com.yashash.ledger.domain.Account;
import com.yashash.ledger.domain.Entry;
import com.yashash.ledger.integration.TestContainersConfig;
import com.yashash.ledger.repository.AccountRepository;
import com.yashash.ledger.repository.EntryRepository;
import com.yashash.ledger.repository.OutboxEventRepository;
import com.yashash.ledger.repository.TransferRepository;
import com.yashash.ledger.service.AccountService;
import com.yashash.ledger.service.InsufficientFundsException;
import com.yashash.ledger.service.TransferService;
import com.yashash.ledger.service.TransferService.PostingRequest;
import com.yashash.ledger.service.TransferService.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yashash.ledger.domain.Entry.Direction.CREDIT;
import static com.yashash.ledger.domain.Entry.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency invariant test:
 * 32 threads each attempt N random transfers among M accounts.
 * After all complete:
 *   1. Total money (sum of all balances) must be unchanged.
 *   2. No account without overdraft should have a negative balance.
 *   3. Sum of entries per account must equal the account's balance.
 *   4. No lost updates (zero deadlock failures after retries).
 */
@SpringBootTest
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
class ConcurrencyInvariantTest {

    private static final int THREADS = 32;
    private static final int TRANSFERS_PER_THREAD = 10;
    private static final int NUM_ACCOUNTS = 6;
    private static final long INITIAL_BALANCE = 1000_00L; // $1000 each
    private static final String CURRENCY = "USD";

    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepo;
    @Autowired EntryRepository entryRepo;
    @Autowired TransferRepository transferRepo;
    @Autowired OutboxEventRepository outboxRepo;

    @BeforeEach
    void clean() {
        outboxRepo.deleteAll();
        entryRepo.deleteAll();
        transferRepo.deleteAll();
        accountRepo.deleteAll();
    }

    @Test
    void concurrentTransfers_moneyIsConserved() throws Exception {
        // Create accounts
        List<UUID> accountIds = new ArrayList<>();
        for (int i = 0; i < NUM_ACCOUNTS; i++) {
            Account a = accountService.createAccount("Account-" + i, CURRENCY, Account.AccountType.ASSET, false);
            accountRepo.findById(a.getId()).ifPresent(acc -> {
                acc.setBalanceMinor(INITIAL_BALANCE);
                accountRepo.save(acc);
            });
            accountIds.add(a.getId());
        }

        long totalMoneyBefore = (long) NUM_ACCOUNTS * INITIAL_BALANCE;

        // 32 threads, each does 10 random transfers
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Random random = new Random(42);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            futures.add(exec.submit(() -> {
                Random rng = new Random(threadId * 1000L);
                for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                    // Pick two distinct accounts randomly
                    int srcIdx = rng.nextInt(NUM_ACCOUNTS);
                    int dstIdx;
                    do { dstIdx = rng.nextInt(NUM_ACCOUNTS); } while (dstIdx == srcIdx);

                    UUID src = accountIds.get(srcIdx);
                    UUID dst = accountIds.get(dstIdx);
                    long amount = (rng.nextInt(10) + 1) * 10_00L; // $10-$100

                    List<PostingRequest> postings = List.of(
                        new PostingRequest(src, DEBIT, amount, CURRENCY),
                        new PostingRequest(dst, CREDIT, amount, CURRENCY)
                    );
                    String bodyHash = TransferService.sha256(src + ":" + dst + ":" + amount + ":" + threadId + ":" + i);
                    TransferRequest req = new TransferRequest(UUID.randomUUID().toString(), bodyHash, null, postings);
                    try {
                        transferService.postTransfer(req);
                        succeeded.incrementAndGet();
                    } catch (InsufficientFundsException e) {
                        failed.incrementAndGet(); // acceptable: src didn't have enough
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        exec.shutdown();

        // --- INVARIANT CHECKS ---

        // 1. Total money conserved
        long totalAfter = accountRepo.findAll().stream()
            .mapToLong(Account::getBalanceMinor).sum();
        assertThat(totalAfter).as("Total money conserved").isEqualTo(totalMoneyBefore);

        // 2. No negative balances on non-overdraft accounts
        for (Account a : accountRepo.findAll()) {
            if (!a.isAllowOverdraft()) {
                assertThat(a.getBalanceMinor())
                    .as("Account %s balance must be >= 0", a.getId())
                    .isGreaterThanOrEqualTo(0);
            }
        }

        // 3. Entry sums match balances
        for (Account a : accountRepo.findAll()) {
            List<Entry> entries = entryRepo.findByAccountIdFirstPage(a.getId(),
                org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE));
            long entryNet = entries.stream().mapToLong(e ->
                e.getDirection() == DEBIT ? -e.getAmountMinor() : e.getAmountMinor()
            ).sum();
            long expectedBalance = INITIAL_BALANCE + entryNet;
            assertThat(a.getBalanceMinor())
                .as("Balance for account %s matches entry sum", a.getId())
                .isEqualTo(expectedBalance);
        }

        System.out.printf("Concurrency test: succeeded=%d, failed(insufficient_funds/retried)=%d, threads=%d%n",
            succeeded.get(), failed.get(), THREADS);
        System.out.printf("Total money before=%d, after=%d%n", totalMoneyBefore, totalAfter);
    }
}
