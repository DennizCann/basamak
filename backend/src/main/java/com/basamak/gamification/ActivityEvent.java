package com.basamak.gamification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One member action. eventKey makes it idempotent: a retry, a network duplicate or a mobile re-sync of the same
 * action carries the same key and is ignored the second time.
 *
 * @param occurredAt when the member did it (may come from a device clock; used for display only)
 */
public record ActivityEvent(UUID userId, String eventKey, String action, String objectType, String objectId,
                            Integer objectVersion, Instant occurredAt, String source, Map<String, Object> payload) {

    public static ActivityEvent web(UUID userId, String eventKey, String action, String objectType, String objectId, Integer version) {
        return new ActivityEvent(userId, eventKey, action, objectType, objectId, version, Instant.now(), "web", Map.of());
    }

    public ActivityEvent withSource(String s) {
        return new ActivityEvent(userId, eventKey, action, objectType, objectId, objectVersion, occurredAt, s, payload);
    }
}
