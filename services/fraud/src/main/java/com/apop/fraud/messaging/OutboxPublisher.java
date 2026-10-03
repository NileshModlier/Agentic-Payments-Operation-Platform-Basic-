package com.apop.fraud.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "apop.messaging.enabled", havingValue = "true")
public class OutboxPublisher {

    private final EntityManager entityManager;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxPublisher(EntityManager entityManager, KafkaTemplate<String, byte[]> kafkaTemplate, ObjectMapper objectMapper, Clock clock) {
        this.entityManager = entityManager;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public boolean publishNext() {
        @SuppressWarnings("unchecked")
        List<OutboxMessage> claimed = entityManager.createNativeQuery("""
                SELECT o.*
                FROM outbox o
                WHERE o.published_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM outbox earlier
                      WHERE earlier.aggregate_id = o.aggregate_id
                        AND earlier.published_at IS NULL
                        AND (earlier.created_at, earlier.outbox_id) < (o.created_at, o.outbox_id)
                  )
                ORDER BY o.created_at, o.outbox_id
                FOR UPDATE OF o SKIP LOCKED
                LIMIT 1
                """, OutboxMessage.class).getResultList();
        if (claimed.isEmpty()) {
            return false;
        }
        OutboxMessage message = claimed.getFirst();
        org.apache.kafka.clients.producer.ProducerRecord<String, byte[]> record =
                new org.apache.kafka.clients.producer.ProducerRecord<>(message.getTopic(), message.getRecordKey(), message.getPayload());
        headers(message).forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
        try {
            kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing outbox " + message.getOutboxId(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Broker did not acknowledge outbox " + message.getOutboxId(), exception);
        }
        message.markPublished(clock.instant());
        return true;
    }

    private Map<String, String> headers(OutboxMessage message) {
        try {
            return objectMapper.readValue(message.getHeaders(), new TypeReference<>() {
            });
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Outbox headers are not readable", exception);
        }
    }
}

@Component
@ConditionalOnProperty(name = "apop.messaging.enabled", havingValue = "true")
class OutboxRelay {

    private final OutboxPublisher publisher;

    OutboxRelay(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${apop.messaging.relay-delay-ms}")
    public void relay() {
        int published = 0;
        while (published < 50 && publisher.publishNext()) {
            published++;
        }
    }
}
