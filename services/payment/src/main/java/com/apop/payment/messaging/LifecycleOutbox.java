package com.apop.payment.messaging;

import com.apop.payment.config.MessagingProperties;
import com.apop.payment.domain.Payment;
import com.apop.payment.domain.UuidV7;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class LifecycleOutbox {

    static final String SOURCE = "urn:apop:payment-service";
    static final String CONTENT_TYPE = "application/cloudevents+json";

    private final OutboxRepository outbox;
    private final MessagingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    LifecycleOutbox(
            OutboxRepository outbox,
            MessagingProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.outbox = outbox;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Payment payment, LifecycleEventName eventName, String previousStatus, UUID stepId) {
        Instant occurredAt = clock.instant();
        UUID eventId = UuidV7.generate();
        String causationId = outbox.findFirstByAggregateIdOrderByCreatedAtDescOutboxIdDesc(payment.getPaymentId())
                .map(previous -> previous.getOutboxId().toString())
                .orElseGet(() -> payment.getCorrelationId().toString());
        LifecycleEnvelope envelope = new LifecycleEnvelope(
                "1.0",
                eventId,
                SOURCE,
                properties.cloudEventType(),
                payment.getPaymentId().toString(),
                occurredAt,
                "application/json",
                new LifecycleEnvelope.LifecycleData(
                        eventName,
                        payment.getPaymentId(),
                        payment.getStatus().name(),
                        previousStatus,
                        payment.getRail().name(),
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        payment.getDebtorAccountId(),
                        payment.getCreditorAccountId(),
                        null,
                        payment.getValueDate(),
                        payment.getReasonCode(),
                        stepId));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("ce_id", eventId.toString());
        headers.put("ce_type", properties.cloudEventType());
        headers.put("correlation-id", payment.getCorrelationId().toString());
        headers.put("causation-id", causationId);
        headers.put("idempotency-key", payment.getIdempotencyKey());
        headers.put("legal-entity-id", payment.getLegalEntityId());
        headers.put("traceparent", traceParent(payment));
        headers.put("content-type", CONTENT_TYPE);
        try {
            outbox.save(OutboxMessage.create(
                    eventId,
                    payment.getPaymentId(),
                    properties.topic(),
                    properties.cloudEventType(),
                    objectMapper.writeValueAsBytes(envelope),
                    objectMapper.writeValueAsString(headers),
                    occurredAt));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write the lifecycle envelope", exception);
        }
    }

    private static String traceParent(Payment payment) {
        Span span = Span.current();
        SpanContext context = span.getSpanContext();
        if (context.isValid()) {
            span.setAttribute("payment.id", payment.getPaymentId().toString());
            if (payment.getEndToEndId() != null) {
                span.setAttribute("end_to_end.id", payment.getEndToEndId());
            }
            return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + (context.isSampled() ? "01" : "00");
        }
        String current = MDC.get("traceparent");
        if (current != null && !current.isBlank()) {
            return current;
        }
        String traceId = payment.getCorrelationId().toString().replace("-", "");
        String spanId = UuidV7.generate().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }
}
