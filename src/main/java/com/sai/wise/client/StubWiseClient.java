package com.sai.wise.client;

import com.sai.wise.exception.WiseTransientException;
import com.sai.wise.model.QuoteResponse;
import com.sai.wise.model.RecipientResponse;
import com.sai.wise.model.TransferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory stand-in for the Wise Platform API, active under the
 * {@code demo} profile.
 *
 * <p><b>Why this exists.</b> Wise Platform sandbox access is not fully
 * self-serve — credentials come through partner onboarding. That is a
 * reasonable thing for a payments company to gate, but it means a reviewer
 * cannot clone this repository and see it work. So the orchestration is written
 * against an interface, and this implementation lets the entire flow run with
 * one command and no account:
 *
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.profiles=demo
 * </pre>
 *
 * <p><b>What it deliberately models.</b> Not just the happy path — the
 * behaviours the orchestration has to survive:
 * <ul>
 *   <li>Quotes carry a real expiry, so the expiry guard can be exercised.</li>
 *   <li>Transfer creation is idempotent on {@code customerTransactionId}: the
 *       same key returns the same transfer, exactly as Wise behaves.</li>
 *   <li>Funding can be made to fail on demand, so the compensation path
 *       ({@code CREATED_UNFUNDED}) is reachable in a demo.</li>
 *   <li>Transfers progress through states over time, so reconciliation has
 *       something real to reconcile.</li>
 * </ul>
 *
 * <p>This is a stub, not a simulator. It does not model FX spreads, corridor
 * rules, compliance holds or settlement timing, and it is never active outside
 * the {@code demo} profile.
 */
@Component
@Profile("demo")
public class StubWiseClient implements WiseApiClient {

    private static final Logger log = LoggerFactory.getLogger(StubWiseClient.class);

    /** Indicative rates, fixed. Enough to make the arithmetic visible. */
    private static final Map<String, BigDecimal> RATES = Map.of(
            "USD->INR", new BigDecimal("83.12"),
            "USD->EUR", new BigDecimal("0.92"),
            "USD->GBP", new BigDecimal("0.79"),
            "GBP->INR", new BigDecimal("105.40"),
            "EUR->INR", new BigDecimal("90.35"));

    private static final BigDecimal FEE_RATE = new BigDecimal("0.0055");

    private final AtomicLong transferIds = new AtomicLong(90_000L);
    private final AtomicLong recipientIds = new AtomicLong(500L);

    /** Idempotency: customerTransactionId -> the transfer we already made. */
    private final Map<String, TransferResponse> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<Long, TransferResponse> byTransferId = new ConcurrentHashMap<>();
    private final Map<Long, Instant> createdAt = new ConcurrentHashMap<>();

    /**
     * Set true to make the next funding call fail, so the compensation path can
     * be demonstrated. Toggled from the demo controller.
     */
    private volatile boolean failNextFunding = false;

    public void failNextFunding(boolean fail) {
        this.failNextFunding = fail;
        log.warn("[STUB] failNextFunding set to {}", fail);
    }

    @Override
    public Mono<QuoteResponse> createQuote(String sourceCurrency, String targetCurrency, BigDecimal sourceAmount) {

        BigDecimal rate = RATES.get(sourceCurrency.toUpperCase() + "->" + targetCurrency.toUpperCase());
        if (rate == null) {
            return Mono.error(new WiseTransientException(
                    "Stub has no rate for " + sourceCurrency + "->" + targetCurrency));
        }

        BigDecimal fee = sourceAmount.multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal target = sourceAmount.subtract(fee).multiply(rate).setScale(2, RoundingMode.HALF_UP);

        // A real expiry, so the orchestration's expiry guard is genuinely exercised.
        QuoteResponse quote = new QuoteResponse(
                "stub-quote-" + java.util.UUID.randomUUID(),
                sourceCurrency, targetCurrency,
                sourceAmount, target, rate,
                Instant.now().plusSeconds(600),
                "PENDING");

        log.info("[STUB QUOTE] {} {} -> {} {} at {} (fee {})",
                sourceAmount, sourceCurrency, target, targetCurrency, rate, fee);
        return Mono.just(quote);
    }

    @Override
    public Mono<RecipientResponse> createRecipient(String currency, String type,
                                                   String accountHolderName, Map<String, Object> details) {
        long id = recipientIds.incrementAndGet();
        log.info("[STUB RECIPIENT] created id={} currency={} holder={}", id, currency, accountHolderName);
        return Mono.just(new RecipientResponse(id, currency, type, accountHolderName, null));
    }

    @Override
    public Mono<TransferResponse> createTransfer(Long targetAccountId, String quoteUuid,
                                                 String customerTransactionId, String reference) {

        // This is the behaviour that matters: the same idempotency key returns
        // the SAME transfer rather than creating a second payment.
        TransferResponse existing = byIdempotencyKey.get(customerTransactionId);
        if (existing != null) {
            log.info("[STUB TRANSFER] idempotency key {} already seen -> returning transfer {}",
                    customerTransactionId, existing.id());
            return Mono.just(existing);
        }

        long id = transferIds.incrementAndGet();
        TransferResponse transfer = new TransferResponse(
                id, "stub-user", targetAccountId, quoteUuid,
                "incoming_payment_waiting", reference, customerTransactionId,
                false, Instant.now());

        byIdempotencyKey.put(customerTransactionId, transfer);
        byTransferId.put(id, transfer);
        createdAt.put(id, Instant.now());

        log.info("[STUB TRANSFER] created id={} key={}", id, customerTransactionId);
        return Mono.just(transfer);
    }

    @Override
    public Mono<String> fundTransfer(Long transferId) {
        if (failNextFunding) {
            failNextFunding = false;
            log.warn("[STUB FUND] deliberately failing funding for transfer {} to exercise compensation", transferId);
            return Mono.error(new WiseTransientException("Stub: balance service unavailable"));
        }

        TransferResponse t = byTransferId.get(transferId);
        if (t == null) {
            return Mono.error(new WiseTransientException("Stub: unknown transfer " + transferId));
        }

        TransferResponse funded = new TransferResponse(t.id(), t.user(), t.targetAccount(), t.quoteUuid(),
                "processing", t.reference(), t.customerTransactionId(), false, t.created());
        byTransferId.put(transferId, funded);

        log.info("[STUB FUND] transfer {} funded", transferId);
        return Mono.just("{\"status\":\"funded\"}");
    }

    @Override
    public Mono<TransferResponse> getTransfer(Long transferId) {
        TransferResponse t = byTransferId.get(transferId);
        if (t == null) {
            return Mono.error(new WiseTransientException("Stub: unknown transfer " + transferId));
        }

        // Transfers settle over time. After 30 seconds a funded transfer is
        // reported as sent, which gives the reconciliation job a genuine state
        // change to detect rather than a static value.
        Instant created = createdAt.getOrDefault(transferId, Instant.now());
        if ("processing".equals(t.status()) && Instant.now().isAfter(created.plusSeconds(30))) {
            TransferResponse sent = new TransferResponse(t.id(), t.user(), t.targetAccount(), t.quoteUuid(),
                    "outgoing_payment_sent", t.reference(), t.customerTransactionId(), false, t.created());
            byTransferId.put(transferId, sent);
            log.info("[STUB TRACK] transfer {} progressed to outgoing_payment_sent", transferId);
            return Mono.just(sent);
        }

        return Mono.just(t);
    }
}
