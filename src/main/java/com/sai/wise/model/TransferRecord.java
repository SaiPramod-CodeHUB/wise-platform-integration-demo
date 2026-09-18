package com.sai.wise.model;

import java.time.Instant;

/**
 * Our own durable record of a transfer.
 *
 * <p>A partner integration cannot treat the remote system as its only memory.
 * If Wise is the source of truth for transfer state, we still need a local
 * record to answer "what did we send, and did it land?" — which is exactly what
 * the reconciliation job reads.
 *
 * <p>In production this is a database row. Here it is an in-memory record so
 * the demo runs with no infrastructure; the shape is the same.
 */
public record TransferRecord(
        String clientReference,
        String customerTransactionId,
        Long wiseTransferId,
        String quoteId,
        Long targetAccountId,
        Money amount,
        String status,
        Instant createdAt,
        Instant lastCheckedAt
) {
    public TransferRecord withStatus(String newStatus) {
        return new TransferRecord(clientReference, customerTransactionId, wiseTransferId, quoteId,
                targetAccountId, amount, newStatus, createdAt, Instant.now());
    }

    public boolean isTerminal() {
        if (status == null) return false;
        return switch (status.toLowerCase()) {
            case "outgoing_payment_sent", "funds_refunded", "cancelled", "bounced_back" -> true;
            default -> false;
        };
    }
}
