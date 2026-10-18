package com.stayhotel.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parses envelopes and typed payloads; failures are not retryable (see the DLT error handler). */
public final class EventReader {

    private final ObjectMapper objectMapper;

    public EventReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EventEnvelope envelope(String message) {
        try {
            EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventType() == null) {
                throw new IllegalArgumentException("Event without id/type");
            }
            return envelope;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unreadable event", e);
        }
    }

    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unreadable payload of " + envelope.eventType(), e);
        }
    }
}
