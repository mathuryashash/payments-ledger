package com.yashash.ledger.outbox;

import com.yashash.ledger.domain.OutboxEvent;
import com.yashash.ledger.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls the outbox_events table and publishes unpublished events to Kafka.
 * Uses FOR UPDATE SKIP LOCKED so multiple relay instances can run safely
 * without stepping on each other's rows.
 *
 * Trade-off: polling adds some latency (default 500ms max). The alternative
 * (Debezium CDC) has lower latency but needs more ops setup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private static final String TOPIC = "ledger.transfers";
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    private final AtomicLong outboxLag = new AtomicLong(0);

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("ledger.outbox.lag", outboxLag, AtomicLong::get)
            .description("Number of unpublished outbox events")
            .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outboxRepo.findUnpublishedForUpdate(BATCH_SIZE);
        if (batch.isEmpty()) {
            outboxLag.set(0);
            return;
        }
        outboxLag.set(batch.size());

        for (OutboxEvent event : batch) {
            try {
                // Key on aggregateId so all events for the same transfer go to the same partition
                kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload())
                    .get(); // block to confirm delivery before marking published
                event.setPublishedAt(Instant.now());
                outboxRepo.save(event);
                log.debug("Published outbox event {} to {}", event.getId(), TOPIC);
            } catch (Exception e) {
                // Log and leave unpublished — it will be retried in the next poll
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}
