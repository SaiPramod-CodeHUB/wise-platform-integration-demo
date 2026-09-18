package com.sai.wise.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A transfer at Wise.
 *
 * <p>customerTransactionId is the idempotency key WE generated. If a create
 * call times out and we retry with the same value, Wise returns the original
 * transfer rather than creating a second payment. That single field is what
 * stands between a network blip and paying someone twice.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferResponse(
        Long id,
        String user,
        Long targetAccount,
        String quoteUuid,
        String status,
        String reference,
        String customerTransactionId,
        Boolean hasActiveIssues,
        Instant created
) {
    /** Terminal states: nothing further will happen on its own. */
    public boolean isTerminal() {
        if (status == null) return false;
        return switch (status.toLowerCase()) {
            case "outgoing_payment_sent", "funds_refunded", "cancelled", "bounced_back" -> true;
            default -> false;
        };
    }

    public boolean needsAttention() {
        return Boolean.TRUE.equals(hasActiveIssues);
    }
}
