package com.yashash.ledger.integration;

import com.yashash.ledger.domain.Account;
import com.yashash.ledger.domain.OutboxEvent;
import com.yashash.ledger.domain.Transfer;
import com.yashash.ledger.outbox.OutboxRelay;
import com.yashash.ledger.repository.*;
import com.yashash.ledger.service.AccountService;
import com.yashash.ledger.service.TransferService;
import com.yashash.ledger.service.TransferService.PostingRequest;
import com.yashash.ledger.service.TransferService.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yashash.ledger.domain.Entry.Direction.CREDIT;
import static com.yashash.ledger.domain.Entry.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
class OutboxKafkaIntegrationTest {

    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepo;
    @Autowired EntryRepository entryRepo;
    @Autowired TransferRepository transferRepo;
    @Autowired OutboxEventRepository outboxRepo;
    @Autowired ProcessedEventRepository processedEventRepo;
    @Autowired OutboxRelay outboxRelay;

    @BeforeEach
    void clean() {
        processedEventRepo.deleteAll();
        outboxRepo.deleteAll();
        entryRepo.deleteAll();
        transferRepo.deleteAll();
        accountRepo.deleteAll();
    }

    @Test
    void outbox_relay_publishesToKafka_and_consumer_processesIdempotently() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 500_00L);

        List<PostingRequest> postings = List.of(
            new PostingRequest(alice.getId(), DEBIT, 100_00L, "USD"),
            new PostingRequest(bob.getId(), CREDIT, 100_00L, "USD")
        );
        String bodyHash = TransferService.sha256("outbox-test-ref|" + alice.getId() + ":DEBIT:10000:USD;"
            + bob.getId() + ":CREDIT:10000:USD;");
        String idemKey = "outbox-test-key-" + UUID.randomUUID();
        TransferRequest req = new TransferRequest(idemKey, bodyHash, "outbox-test-ref", postings);
        Transfer t = transferService.postTransfer(req);

        // Outbox event should exist in DB
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Optional<OutboxEvent> evt = outboxRepo.findAll().stream()
                .filter(e -> e.getAggregateId().equals(t.getId()))
                .findFirst();
            assertThat(evt).isPresent();
        });

        // Run relay — should publish the event to Kafka
        outboxRelay.relay();

        // Event should now be marked as published
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvent event = outboxRepo.findAll().stream()
                .filter(e -> e.getAggregateId().equals(t.getId()))
                .findFirst().orElseThrow();
            assertThat(event.isPublished()).isTrue();
        });

        // Consumer should process it (wait for Kafka consumer to pick up the message)
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(processedEventRepo.count()).isGreaterThan(0)
        );

        // Run relay again — no new unpublished events for our transfer
        long publishedCountBefore = outboxRepo.findAll().stream()
            .filter(e -> e.getAggregateId().equals(t.getId()) && e.isPublished()).count();
        assertThat(publishedCountBefore).isEqualTo(1);
        outboxRelay.relay();
        long publishedCountAfter = outboxRepo.findAll().stream()
            .filter(e -> e.getAggregateId().equals(t.getId()) && e.isPublished()).count();
        assertThat(publishedCountAfter).isEqualTo(1); // unchanged
    }

    private void seedBalance(UUID accountId, long amount) {
        Account account = accountRepo.findById(accountId).orElseThrow();
        account.setBalanceMinor(amount);
        accountRepo.save(account);
    }
}
