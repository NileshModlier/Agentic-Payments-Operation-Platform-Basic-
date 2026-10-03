package com.apop.settlement.messaging;

import com.apop.settlement.service.SettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name = "apop.messaging.enabled", havingValue = "true")
public class PaymentAuthorizedConsumer {

    private final SettlementService settlement;
    private final ProcessedEventStore processedEvents;
    private final ObjectMapper objectMapper;

    public PaymentAuthorizedConsumer(
            SettlementService settlement,
            ProcessedEventStore processedEvents,
            ObjectMapper objectMapper) {
        this.settlement = settlement;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${apop.messaging.lifecycle-topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(ConsumerRecord<String, byte[]> record) {
        String correlation = header(record, "correlation-id");
        if (correlation != null) {
            MDC.put("correlationId", correlation);
        }
        if (record.key() != null) {
            MDC.put("paymentId", record.key());
        }
        try {
            LifecycleNotice notice = LifecycleNotice.read(objectMapper, record.value());
            if (notice.data().paymentId() != null) {
                MDC.put("paymentId", notice.data().paymentId().toString());
                Span.current().setAttribute("payment.id", notice.data().paymentId().toString());
            }
            if (processedEvents.alreadyProcessed(notice.eventId())) {
                return;
            }
            String eventName = notice.data().eventName();
            if (!"PaymentAuthorized".equals(eventName)) {
                processedEvents.record(notice.eventId(), notice.type(), "IGNORED");
                return;
            }
            settlement.settle(notice.data().paymentId(), notice.eventId());
            processedEvents.record(notice.eventId(), notice.type(), "APPLIED");
        } finally {
            MDC.remove("correlationId");
            MDC.remove("paymentId");
        }
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
