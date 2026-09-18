package com.sai.wise.client;

import com.sai.wise.model.QuoteResponse;
import com.sai.wise.model.RecipientResponse;
import com.sai.wise.model.TransferResponse;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The five Wise Platform operations this service depends on.
 *
 * <p>This exists as an interface for one practical reason: the orchestration
 * logic — idempotency, quote-expiry handling, the funding saga, reconciliation —
 * is the valuable part, and it must be testable and demonstrable without a live
 * Wise account.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link WiseClient} — the real HTTP client, active by default.</li>
 *   <li>{@code StubWiseClient} — an in-memory stand-in, active under the
 *       {@code demo} profile, so the whole flow can be run with no credentials.</li>
 * </ul>
 */
public interface WiseApiClient {

    Mono<QuoteResponse> createQuote(String sourceCurrency, String targetCurrency, BigDecimal sourceAmount);

    Mono<RecipientResponse> createRecipient(String currency, String type,
                                            String accountHolderName, Map<String, Object> details);

    Mono<TransferResponse> createTransfer(Long targetAccountId, String quoteUuid,
                                          String customerTransactionId, String reference);

    Mono<String> fundTransfer(Long transferId);

    Mono<TransferResponse> getTransfer(Long transferId);
}
