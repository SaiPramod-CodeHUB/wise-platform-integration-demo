package com.sai.wise.client;

import com.sai.wise.config.WiseProperties;
import com.sai.wise.exception.WisePermanentException;
import com.sai.wise.exception.WiseTransientException;
import com.sai.wise.model.QuoteResponse;
import com.sai.wise.model.RecipientResponse;
import com.sai.wise.model.TransferResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The five calls that move money through Wise Platform.
 *
 * <pre>
 *   1. QUOTE      POST /v3/profiles/{profileId}/quotes
 *   2. RECIPIENT  POST /v1/accounts
 *   3. TRANSFER   POST /v1/transfers          (carries customerTransactionId)
 *   4. FUND       POST /v3/profiles/{profileId}/transfers/{id}/payments
 *   5. TRACK      GET  /v1/transfers/{id}
 * </pre>
 *
 * <p>Every outbound call is wrapped in a retry and a circuit breaker. The retry
 * only fires on {@link WiseTransientException} — we do not retry a 400, because
 * a 400 will be a 400 again and the partner deserves to hear that immediately.
 */
@Component
@org.springframework.context.annotation.Profile("!demo")
public class WiseClient implements WiseApiClient {

    private static final Logger log = LoggerFactory.getLogger(WiseClient.class);

    private final WebClient webClient;
    private final WiseProperties props;

    public WiseClient(WebClient wiseWebClient, WiseProperties props) {
        this.webClient = wiseWebClient;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // 1. QUOTE — ask what this transfer costs and lock the rate briefly
    // ------------------------------------------------------------------
    @Override
    @Retry(name = "wiseApi")
    @CircuitBreaker(name = "wiseApi")
    public Mono<QuoteResponse> createQuote(String sourceCurrency, String targetCurrency, BigDecimal sourceAmount) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceCurrency", sourceCurrency);
        body.put("targetCurrency", targetCurrency);
        // sourceAmount stays a BigDecimal all the way to serialisation.
        body.put("sourceAmount", sourceAmount);

        return webClient.post()
                .uri("/v3/profiles/{profileId}/quotes", props.getProfileId())
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> true, this::classify)
                .bodyToMono(QuoteResponse.class)
                .doOnSuccess(q -> log.info("[QUOTE] {} {} -> {} rate={} expires={}",
                        sourceAmount, sourceCurrency, targetCurrency, q.rate(), q.expirationTime()))
                .onErrorMap(WebClientRequestException.class,
                        e -> new WiseTransientException("Quote call failed to reach Wise", e));
    }

    // ------------------------------------------------------------------
    // 2. RECIPIENT — who is being paid
    //
    // The required fields here change per corridor: IFSC for India, sort code
    // for the UK, routing number for the US, IBAN for the euro area. A partner
    // that hard-codes these breaks the day they add a country. The right answer
    // is to drive the form off Wise's account-requirements endpoint.
    // ------------------------------------------------------------------
    @Override
    @Retry(name = "wiseApi")
    @CircuitBreaker(name = "wiseApi")
    public Mono<RecipientResponse> createRecipient(String currency,
                                                   String type,
                                                   String accountHolderName,
                                                   Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", currency);
        body.put("type", type);
        body.put("profile", props.getProfileId());
        body.put("accountHolderName", accountHolderName);
        body.put("details", details);

        return webClient.post()
                .uri("/v1/accounts")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> true, this::classify)
                .bodyToMono(RecipientResponse.class)
                .doOnSuccess(r -> log.info("[RECIPIENT] created id={} currency={}", r.id(), r.currency()))
                .onErrorMap(WebClientRequestException.class,
                        e -> new WiseTransientException("Recipient call failed to reach Wise", e));
    }

    // ------------------------------------------------------------------
    // 3. TRANSFER — the idempotent create
    //
    // customerTransactionId is generated by us, once, and reused on every
    // retry of this specific transfer. It is the reason a timeout here is
    // survivable instead of dangerous.
    // ------------------------------------------------------------------
    @Override
    @Retry(name = "wiseApi")
    @CircuitBreaker(name = "wiseApi")
    public Mono<TransferResponse> createTransfer(Long targetAccountId,
                                                 String quoteUuid,
                                                 String customerTransactionId,
                                                 String reference) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", reference == null ? "" : reference);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetAccount", targetAccountId);
        body.put("quoteUuid", quoteUuid);
        body.put("customerTransactionId", customerTransactionId);
        body.put("details", details);

        return webClient.post()
                .uri("/v1/transfers")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> true, this::classify)
                .bodyToMono(TransferResponse.class)
                .doOnSuccess(t -> log.info("[TRANSFER] created id={} status={} idempotencyKey={}",
                        t.id(), t.status(), customerTransactionId))
                .onErrorMap(WebClientRequestException.class,
                        e -> new WiseTransientException("Transfer call failed to reach Wise", e));
    }

    // ------------------------------------------------------------------
    // 4. FUND — actually move the money from the partner balance
    //
    // Unfunded transfers are cancelled by Wise after fourteen days. That is a
    // safety net, not a strategy: a transfer we created and failed to fund is
    // a customer sitting in limbo, so the orchestration treats a funding
    // failure as something to compensate for, not something to leave.
    // ------------------------------------------------------------------
    @Override
    @Retry(name = "wiseApi")
    @CircuitBreaker(name = "wiseApi")
    public Mono<String> fundTransfer(Long transferId) {
        Map<String, Object> body = Map.of("type", "BALANCE");

        return webClient.post()
                .uri("/v3/profiles/{profileId}/transfers/{transferId}/payments",
                        props.getProfileId(), transferId)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> true, this::classify)
                .bodyToMono(String.class)
                .doOnSuccess(r -> log.info("[FUND] transfer={} funded", transferId))
                .onErrorMap(WebClientRequestException.class,
                        e -> new WiseTransientException("Funding call failed to reach Wise", e));
    }

    // ------------------------------------------------------------------
    // 5. TRACK — used by the webhook handler and the reconciliation job
    // ------------------------------------------------------------------
    @Override
    @Retry(name = "wiseApi")
    @CircuitBreaker(name = "wiseApi")
    public Mono<TransferResponse> getTransfer(Long transferId) {
        return webClient.get()
                .uri("/v1/transfers/{transferId}", transferId)
                .retrieve()
                .onStatus(s -> true, this::classify)
                .bodyToMono(TransferResponse.class)
                .onErrorMap(WebClientRequestException.class,
                        e -> new WiseTransientException("Status call failed to reach Wise", e));
    }

    // ------------------------------------------------------------------
    // Error classification. This small method is where retry safety lives.
    // ------------------------------------------------------------------
    private Mono<? extends Throwable> classify(org.springframework.web.reactive.function.client.ClientResponse resp) {
        int code = resp.statusCode().value();

        if (code < 400) {
            return Mono.empty();
        }

        return resp.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> {
            // 429 and 5xx are worth another go.
            if (code == 429 || code >= 500) {
                log.warn("[WISE] transient failure status={} body={}", code, truncate(body));
                return Mono.error(new WiseTransientException("Wise returned " + code + ": " + truncate(body)));
            }
            // Everything else is our fault or the partner's, and retrying hides it.
            log.error("[WISE] permanent failure status={} body={}", code, truncate(body));
            return Mono.error(new WisePermanentException("Wise returned " + code, code, body));
        });
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
