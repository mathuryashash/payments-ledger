package com.yashash.ledger.repository;

import com.yashash.ledger.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    Optional<ProcessedEvent> findByTopicAndPartitionNumAndOffsetNum(
        String topic, int partitionNum, long offsetNum);
}
