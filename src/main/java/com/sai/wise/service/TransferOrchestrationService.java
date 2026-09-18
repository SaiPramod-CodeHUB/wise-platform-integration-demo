package com.sai.wise.service;

import com.sai.wise.client.WiseApiClient;
import com.sai.wise.config.WiseProperties;
import com.sai.wise.exception.QuoteExpiredException;
import com.sai.wise.model.Money;
import com.sai.wise.model.QuoteResponse;
import com.sai.wise.model.TransferRecord;
import com.sai.wise.model.TransferRequest;
import com.sai.wise.model.TransferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Walks a payment through the Wise Platform sequence, and survives the ways it
 * can go wrong halfway.
 *
 * <pre>
 *   quote  ->  transfer  ->  fund
 *     |            |           |
 *     |            |           +-- if this fails the transfer EXISTS but is
 *     |            |               unfunded: compensate, do not abandon
 *     |            +-- idempotent on customerTransactionId
 *     +-- expires; re-quote rather than funding a stale rate
 * </pre>
 *
 * <p>This is a small saga. Each step is durable in the local store before the
 * next one is attempted, so a crash between steps leaves a record we can
 * recover from rather than a payment nobody knows about. That property — never
 * losing track of an in-flight payment — matters more here than throughput.
 */
@Service
public class TransferOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(TransferOrchestrationService.class);

    /**
     * Our own marker: the transfer exists at Wise but funding failed. Not a
     * Wise status — a flag that a human needs to act.
     */
    public static final String UNFUNDED = "CREATED_UNFUNDED";

    private final WiseApiClient wiseClient;
    private final TransferStore store;
    private final WiseProperties props;

    public TransferOrchestrationService(WiseApiClient wiseClient, TransferStore store, WiseProperties props) {
        this.wiseClient = wiseClient;
        this.store = store;
        this.props = props;
    }

    /**
     * Execute a transfer, exactly once per clientReference.
     *
     * <p>Call this twice with the same clientReference and the second call
     * returns the first transfer. That is the behaviour a partner integration
     * must have: their retry, their duplicate click, their at-least-once queue
     * redelivery must not move money twice.
     */
    public Mono<TransferRecord> execute(TransferRequest request) {

        // ---- Step 0: idempotency gate ---------------------------------
        // Claim the clientReference atomically BEFORE calling Wise. If someone
        // already claimed it, return theirs and make no network call at all.
        String idempotencyKey = UUID.randomUUID().toString();

        TransferRecord claim = new TransferRecord(
                request.clientReference(),
                idempotencyKey,
                null,
                null,
                request.targetAccountId(),
                new Money(request.sourceAmount(), request.sourceCurrency()),
                "CLAIMED",
                Instant.now(),
                Instant.now());

        Optional<TransferRecord> existing = store.putIfAbsent(claim);
        if (existing.isPresent()) {
            log.info("[IDEMPOTENT] clientReference={} already processed, returning existing transfer id={}",
                    request.clientReference(), existing.get().wiseTransferId());
            return Mono.just(existing.get());
        }

        log.info("[ORCHESTRATION] start clientReference={} {} {} -> {}",
                request.clientReference(), request.sourceAmount(),
                request.sourceCurrency(), request.targetCurrency());

        // ---- Step 1: quote --------------------------------------------
        return wiseClient.createQuote(request.sourceCurrency(), request.targetCurrency(), request.sourceAmount())
                .flatMap(quote -> {

                    // The rate we were given has a shelf life. If it is already
                    // too close to expiry, funding will fail later with a
                    // confusing error — so fail here, clearly, instead.
                    int buffer = props.getQuote().getMinRemainingValiditySeconds();
                    if (quote.isExpired() || quote.expiresWithin(buffer)) {
                        return Mono.error(new QuoteExpiredException(
                                "Quote " + quote.id() + " expires at " + quote.expirationTime()
                                        + " which is inside the " + buffer + "s safety buffer; re-quote required"));
                    }

                    store.save(new TransferRecord(claim.clientReference(), idempotencyKey, null, quote.id(),
                            request.targetAccountId(), quote.source(), "QUOTED", claim.createdAt(), Instant.now()));

                    // ---- Step 2: create transfer (idempotent) ----------
                    return createAndFund(request, quote, idempotencyKey, claim.createdAt());
                })
                .onErrorResume(QuoteExpiredException.class, e -> {
                    log.warn("[QUOTE_EXPIRED] clientReference={} {}", request.clientReference(), e.getMessage());
                    TransferRecord failed = store.findByClientReference(request.clientReference())
                            .map(r -> r.withStatus("QUOTE_EXPIRED"))
                            .orElse(claim.withStatus("QUOTE_EXPIRED"));
                    store.save(failed);
                    return Mono.error(e);
                });
    }

    private Mono<TransferRecord> createAndFund(TransferRequest request,
                                               QuoteResponse quote,
                                               String idempotencyKey,
                                               Instant createdAt) {

        return wiseClient.createTransfer(request.targetAccountId(), quote.id(), idempotencyKey, request.reference())
                .flatMap(transfer -> {

                    TransferRecord created = new TransferRecord(
                            request.clientReference(), idempotencyKey, transfer.id(), quote.id(),
                            request.targetAccountId(), quote.source(),
                            "CREATED", createdAt, Instant.now());
                    store.save(created);

                    // ---- Step 3: fund ------------------------------------
                    return wiseClient.fundTransfer(transfer.id())
                            .thenReturn(created.withStatus("FUNDED"))
                            .doOnNext(store::save)
                            .doOnNext(r -> log.info("[ORCHESTRATION] complete clientReference={} transferId={}",
                                    r.clientReference(), r.wiseTransferId()))

                            // ---- Compensation ------------------------------
                            // The transfer exists at Wise but funding failed.
                            // This is the dangerous state: a real customer is
                            // waiting on money that will never move unless
                            // somebody notices. We record it distinctly so the
                            // reconciliation job and our alerting both see it.
                            .onErrorResume(err -> {
                                log.error("[FUNDING_FAILED] transferId={} clientReference={} — transfer created but "
                                                + "NOT funded; flagged for compensation",
                                        transfer.id(), request.clientReference(), err);
                                TransferRecord unfunded = created.withStatus(UNFUNDED);
                                store.save(unfunded);
                                return Mono.error(err);
                            });
                });
    }

    /**
     * Apply a status observed elsewhere (webhook or reconciliation).
     *
     * <p>Guarded so state only moves forward. Webhooks arrive out of order, and
     * a late "processing" event must never overwrite a delivered transfer.
     */
    public void applyObservedStatus(Long wiseTransferId, String observedStatus) {
        store.findByWiseTransferId(wiseTransferId).ifPresentOrElse(record -> {
            if (record.isTerminal()) {
                log.debug("[STATUS] transferId={} already terminal ({}), ignoring late event '{}'",
                        wiseTransferId, record.status(), observedStatus);
                return;
            }

            // CREATED_UNFUNDED is not a status Wise reports - it is OUR record
            // that funding failed and a human needs to act. Wise will keep
            // reporting the transfer as awaiting payment, which is true from its
            // side and useless from ours.
            //
            // Overwriting our flag with that would silently erase the only
            // marker saying this payment is stuck. The money would still be
            // stuck; nothing would be tracking it. So we only clear the flag
            // when the remote status shows funding actually progressed.
            if (UNFUNDED.equals(record.status()) && !fundingHasProgressed(observedStatus)) {
                log.warn("[STATUS] transferId={} remains {} - remote reports '{}', which does not indicate funding "
                                + "succeeded; keeping the compensation flag",
                        wiseTransferId, UNFUNDED, observedStatus);
                store.save(record.withStatus(UNFUNDED));   // refresh lastCheckedAt only
                return;
            }

            store.save(record.withStatus(observedStatus));
            log.info("[STATUS] transferId={} {} -> {}", wiseTransferId, record.status(), observedStatus);
        }, () -> log.warn("[STATUS] received status '{}' for unknown transferId={}", observedStatus, wiseTransferId));
    }

    /** Statuses that mean money actually moved, so an unfunded flag can be cleared. */
    private boolean fundingHasProgressed(String remoteStatus) {
        if (remoteStatus == null) return false;
        return switch (remoteStatus.toLowerCase()) {
            case "processing", "funds_converted", "outgoing_payment_sent",
                 "bounced_back", "funds_refunded", "cancelled" -> true;
            default -> false;   // incoming_payment_waiting means still NOT funded
        };
    }

    public Optional<TransferRecord> lookup(String clientReference) {
        return store.findByClientReference(clientReference);
    }
}
