package com.stayhotel.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire format of every domain event (design 5.5.3). {@code eventId} is the idempotency key for consumers.
 * Payloads must never contain credentials, tokens or card data.
 */
public record EventEnvelope(UUID eventId, String eventType, int eventVersion, Instant occurredAt, String correlationId,
                            String aggregateId, JsonNode payload) {
}
