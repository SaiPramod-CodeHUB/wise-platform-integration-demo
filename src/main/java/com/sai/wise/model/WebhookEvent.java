package com.sai.wise.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A Wise webhook delivery.
 *
 * <p>Three properties of webhooks that every partner has to design for:
 * they can arrive more than once, they can arrive out of order, and anyone on
 * the internet can POST to your endpoint. So: idempotent on event id, a state
 * machine that refuses to move backwards, and signature verification before we
 * trust a single field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookEvent(
        @JsonProperty("data") Data data,
        @JsonProperty("subscription_id") String subscriptionId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("sent_at") String sentAt
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("resource") Resource resource,
            @JsonProperty("current_state") String currentState,
            @JsonProperty("previous_state") String previousState,
            @JsonProperty("occurred_at") String occurredAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resource(
            @JsonProperty("id") Long id,
            @JsonProperty("type") String type,
            @JsonProperty("profile_id") Long profileId
    ) {}

    /** Stable identity for de-duplication. */
    public String dedupeKey() {
        Long id = (data != null && data.resource() != null) ? data.resource().id() : null;
        String state = (data != null) ? data.currentState() : null;
        String at = (data != null) ? data.occurredAt() : sentAt;
        return id + ":" + state + ":" + at;
    }
}
