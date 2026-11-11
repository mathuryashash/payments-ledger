package com.yashash.ledger.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yashash.ledger.domain.Account;
import com.yashash.ledger.domain.Transfer;
import com.yashash.ledger.repository.*;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yashash.ledger.domain.Entry.Direction.CREDIT;
import static com.yashash.ledger.domain.Entry.Direction.DEBIT;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
class TransferIntegrationTest {

    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepo;
    @Autowired EntryRepository entryRepo;
    @Autowired TransferRepository transferRepo;
    @Autowired OutboxEventRepository outboxRepo;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        outboxRepo.deleteAll();
        entryRepo.deleteAll();
        transferRepo.deleteAll();
        accountRepo.deleteAll();
    }

    @Test
    void createAccount_and_getBalance() {
        Account a = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account fetched = accountService.getAccount(a.getId());
        assertThat(fetched.getBalanceMinor()).isZero();
        assertThat(fetched.getCurrency()).isEqualTo("USD");
    }

    @Test
    void postTransfer_updatesBalances() {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);

        // Seed Alice with $1000
        seedBalance(alice.getId(), 1000_00L);

        TransferRequest req = buildTransfer(alice.getId(), bob.getId(), 200_00L, "USD");
        Transfer t = transferService.postTransfer(req);
        assertThat(t.getStatus()).isEqualTo(Transfer.TransferStatus.POSTED);

        assertThat(accountService.getAccount(alice.getId()).getBalanceMinor()).isEqualTo(800_00L);
        assertThat(accountService.getAccount(bob.getId()).getBalanceMinor()).isEqualTo(200_00L);
    }

    @Test
    void insufficientFunds_throws() {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);

        TransferRequest req = buildTransfer(alice.getId(), bob.getId(), 100_00L, "USD");
        assertThatThrownBy(() -> transferService.postTransfer(req))
            .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void overdraftAccount_allowsNegativeBalance() {
        Account credit = accountService.createAccount("CreditCard", "USD", Account.AccountType.LIABILITY, true);
        Account merchant = accountService.createAccount("Merchant", "USD", Account.AccountType.ASSET, false);

        TransferRequest req = buildTransfer(credit.getId(), merchant.getId(), 500_00L, "USD");
        transferService.postTransfer(req);

        assertThat(accountService.getAccount(credit.getId()).getBalanceMinor()).isEqualTo(-500_00L);
        assertThat(accountService.getAccount(merchant.getId()).getBalanceMinor()).isEqualTo(500_00L);
    }

    @Test
    void idempotency_sameKeyAndBody_returnsExistingTransfer() {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 1000_00L);

        TransferRequest req = buildTransferWithKey(alice.getId(), bob.getId(), 200_00L, "USD", "idem-key-1", "ref-1");
        Transfer t1 = transferService.postTransfer(req);
        Transfer t2 = transferService.postTransfer(req);

        assertThat(t1.getId()).isEqualTo(t2.getId());
        assertThat(accountService.getAccount(alice.getId()).getBalanceMinor()).isEqualTo(800_00L);
    }

    @Test
    void reversal_compensatesOriginalTransfer() {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 1000_00L);

        TransferRequest req = buildTransfer(alice.getId(), bob.getId(), 300_00L, "USD");
        Transfer original = transferService.postTransfer(req);

        Transfer reversal = transferService.reverseTransfer(original.getId(), UUID.randomUUID().toString());
        assertThat(reversal.getReversalOfId()).isEqualTo(original.getId());

        assertThat(accountService.getAccount(alice.getId()).getBalanceMinor()).isEqualTo(1000_00L);
        assertThat(accountService.getAccount(bob.getId()).getBalanceMinor()).isZero();
    }

    @Test
    void outboxEvent_writtenWithTransfer() {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 500_00L);

        long outboxCountBefore = outboxRepo.count();
        transferService.postTransfer(buildTransfer(alice.getId(), bob.getId(), 100_00L, "USD"));

        assertThat(outboxRepo.count()).isGreaterThan(outboxCountBefore);
    }

    @Test
    void concurrentSameIdempotencyKey_exactlyOneTransfer() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 10000_00L);

        String idempotencyKey = "concurrent-idem-" + UUID.randomUUID();
        TransferRequest req = buildTransferWithKey(alice.getId(), bob.getId(), 100_00L, "USD", idempotencyKey, "concurrent");

        int threads = 8;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                try { transferService.postTransfer(req); } catch (Exception ignored) {}
            }));
        }
        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        exec.shutdown();

        // Balance should have changed exactly once regardless of concurrency
        long aliceBalance = accountService.getAccount(alice.getId()).getBalanceMinor();
        assertThat(aliceBalance).isEqualTo(10000_00L - 100_00L);
    }

    // ---- helpers ----

    private TransferRequest buildTransfer(UUID from, UUID to, long amount, String currency) {
        return buildTransferWithKey(from, to, amount, currency, UUID.randomUUID().toString(), null);
    }

    private TransferRequest buildTransferWithKey(UUID from, UUID to, long amount, String currency,
                                                  String idemKey, String ref) {
        List<PostingRequest> postings = List.of(
            new PostingRequest(from, DEBIT, amount, currency),
            new PostingRequest(to, CREDIT, amount, currency)
        );
        String bodyHash = TransferService.sha256((ref == null ? "" : ref) + "|"
            + from + ":" + DEBIT + ":" + amount + ":" + currency + ";"
            + to + ":" + CREDIT + ":" + amount + ":" + currency + ";");
        return new TransferRequest(idemKey, bodyHash, ref, postings);
    }

    private void seedBalance(UUID accountId, long amount) {
        Account account = accountRepo.findById(accountId).orElseThrow();
        account.setBalanceMinor(amount);
        accountRepo.save(account);
    }
}
