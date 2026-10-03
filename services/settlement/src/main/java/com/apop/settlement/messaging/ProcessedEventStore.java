package com.apop.settlement.messaging;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class ProcessedEventStore {

    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    public ProcessedEventStore(ProcessedEventRepository processedEvents, Clock clock) {
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    public boolean alreadyProcessed(UUID eventId) {
        return processedEvents.existsById(eventId);
    }

    @Transactional
    public void record(UUID eventId, String eventType, String outcome) {
        if (processedEvents.existsById(eventId)) {
            return;
        }
        try {
            processedEvents.saveAndFlush(ProcessedEvent.record(eventId, eventType, outcome, clock.instant()));
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent delivery already recorded this event id.
        }
    }
}
