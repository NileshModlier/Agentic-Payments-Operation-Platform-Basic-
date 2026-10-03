package com.apop.payment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class LifecycleInbox {

    private final ProcessedEventRepository processedEvents;
    private final LifecycleProjectionRepository projections;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    LifecycleInbox(
            ProcessedEventRepository processedEvents,
            LifecycleProjectionRepository projections,
            ObjectMapper objectMapper,
            Clock clock) {
        this.processedEvents = processedEvents;
        this.projections = projections;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void project(byte[] payload) {
        LifecycleEnvelope envelope = read(payload);
        if (processedEvents.existsById(envelope.id())) {
            return;
        }
        try {
            processedEvents.saveAndFlush(ProcessedEvent.applied(
                    envelope.id(), envelope.type(), clock.instant()));
            projections.save(LifecycleProjection.from(envelope));
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent delivery of the same event id already recorded the effect.
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeadLetter(String eventId, String eventType) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(eventId);
        } catch (IllegalArgumentException exception) {
            return;
        }
        if (processedEvents.existsById(id)) {
            return;
        }
        processedEvents.save(ProcessedEvent.deadLettered(
                id, eventType == null || eventType.isBlank() ? "unknown" : eventType, clock.instant()));
    }

    private LifecycleEnvelope read(byte[] payload) {
        try {
            LifecycleEnvelope envelope = objectMapper.readValue(payload, LifecycleEnvelope.class);
            if (envelope.id() == null || envelope.data() == null || envelope.data().eventName() == null) {
                throw new IllegalArgumentException("lifecycle event is missing its id or event name");
            }
            return envelope;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("lifecycle event payload is not a CloudEvent", exception);
        }
    }
}
