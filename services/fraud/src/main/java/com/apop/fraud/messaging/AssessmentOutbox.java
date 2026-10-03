package com.apop.fraud.messaging;

import com.apop.fraud.domain.UuidV7;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AssessmentOutbox {

    static final String SOURCE = "urn:apop:fraud-service";
    static final String ASSESSMENT_TYPE = "com.apop.payments.fraud.assessment.v1";

    private final OutboxRepository outbox;
    private final FraudMessagingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    AssessmentOutbox(OutboxRepository outbox, FraudMessagingProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void assessmentCompleted(UUID paymentId, UUID assessmentId, String decision, int score, int policyVersion, List<String> ruleIds) {
        UUID eventId = UuidV7.generate();
        Instant occurredAt = clock.instant();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", paymentId);
        data.put("assessmentId", assessmentId);
        data.put("decision", decision);
        data.put("score", score);
        data.put("policyVersion", policyVersion);
        data.put("ruleIds", ruleIds);
        write(paymentId, eventId, properties.assessmentTopic(), ASSESSMENT_TYPE, "FraudAssessmentCompleted", data, occurredAt);
    }

    public void audit(UUID aggregateId, String eventName, Map<String, Object> data) {
        write(aggregateId, UuidV7.generate(), properties.auditTopic(), "com.apop.platform.audit.recorded.v1", eventName, data, clock.instant());
    }

    private void write(UUID aggregateId, UUID eventId, String topic, String type, String eventName, Map<String, Object> data, Instant occurredAt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId);
        envelope.put("source", SOURCE);
        envelope.put("type", type);
        envelope.put("subject", aggregateId.toString());
        envelope.put("time", occurredAt);
        envelope.put("datacontenttype", "application/json");
        data.put("eventName", eventName);
        envelope.put("data", data);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("ce_id", eventId.toString());
        headers.put("ce_type", type);
        headers.put("content-type", "application/cloudevents+json");
        String correlation = MDC.get("correlationId");
        if (correlation != null) {
            headers.put("correlation-id", correlation);
        }
        headers.put("traceparent", traceParent());
        try {
            outbox.save(OutboxMessage.create(
                    eventId,
                    aggregateId,
                    topic,
                    type,
                    objectMapper.writeValueAsBytes(envelope),
                    objectMapper.writeValueAsString(headers),
                    occurredAt));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write the fraud envelope", exception);
        }
    }

    private static String traceParent() {
        SpanContext context = Span.current().getSpanContext();
        if (context.isValid()) {
            return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + (context.isSampled() ? "01" : "00");
        }
        String current = MDC.get("traceparent");
        if (current != null && !current.isBlank()) {
            return current;
        }
        String seed = UUID.randomUUID().toString().replace("-", "");
        return "00-" + seed + "-" + seed.substring(0, 16) + "-01";
    }
}
