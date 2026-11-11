package com.yashash.ledger.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yashash.ledger.domain.ProcessedEvent;
import com.yashash.ledger.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Example idempotent consumer for the ledger.transfers topic.
 * Uses a processed_events table (keyed by topic+partition+offset) to skip
 * redeliveries — demonstrates at-least-once delivery + idempotent consumer.
 *
 * In a real system this consumer might update a read-side projection or trigger
 * notifications. Here it just logs and records receipt.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerEventConsumer {

    private final ProcessedEventRepository processedEventRepo;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "ledger.transfers", groupId = "ledger-notifications")
    @Transactional
    public void consume(ConsumerRecord<String, String> record) {
        String topic = record.topic();
        int partition = record.partition();
        long offset = record.offset();

        // Check if already processed
        if (processedEventRepo.findByTopicAndPartitionNumAndOffsetNum(topic, partition, offset).isPresent()) {
            log.debug("Skipping already-processed event topic={} partition={} offset={}", topic, partition, offset);
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(record.value());
            String transferId = node.path("transferId").asText();
            String eventType = node.path("eventType").asText("unknown");

            log.info("Received ledger event type={} transferId={} partition={} offset={}",
                eventType, transferId, partition, offset);

            // Record processed
            processedEventRepo.save(ProcessedEvent.builder()
                .topic(topic)
                .partitionNum(partition)
                .offsetNum(offset)
                .eventId(UUID.fromString(transferId))
                .build());

        } catch (Exception e) {
            log.error("Error processing event at partition={} offset={}: {}", partition, offset, e.getMessage(), e);
            // Re-throw to trigger at-least-once redelivery (or DLQ depending on config)
            throw new RuntimeException(e);
        }
    }
}
