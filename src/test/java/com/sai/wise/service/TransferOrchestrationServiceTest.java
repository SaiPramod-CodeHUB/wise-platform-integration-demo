package com.sai.wise.service;

import com.sai.wise.client.WiseClient;
import com.sai.wise.config.WiseProperties;
import com.sai.wise.exception.QuoteExpiredException;
import com.sai.wise.model.QuoteResponse;
import com.sai.wise.model.TransferRecord;
import com.sai.wise.model.TransferRequest;
import com.sai.wise.model.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The tests that matter in a payments integration.
 *
 * <p>Not "does the happy path work" — that is table stakes. These test the
 * three things that cost real money when they are wrong: double payment,
 * stale rates, and losing track of a transfer that was created but not funded.
 */
class TransferOrchestrationServiceTest {

    private WiseClient wiseClient;
    private TransferStore store;
    private TransferOrchestrationService service;

    @BeforeEach
    void setUp() {
        wiseClient = mock(WiseClient.class);
        store = new TransferStore();
        WiseProperties props = new WiseProperties();
        props.setProfileId("test-profile");
        service = new TransferOrchestrationService(wiseClient, store, props);
    }

    private QuoteResponse validQuote() {
        return new QuoteResponse("quote-uuid-1", "USD", "INR",
                new BigDecimal("1000.00"), new BigDecimal("83000.00"),
                new BigDecimal("83.00"), Instant.now().plusSeconds(600), "PENDING");
    }

    private TransferResponse transfer(long id) {
        return new TransferResponse(id, "user", 555L, "quote-uuid-1",
                "incoming_payment_waiting", "ref", "idem-key", false, Instant.now());
    }

    private TransferRequest request(String clientRef) {
        return new TransferRequest(clientRef, "USD", "INR",
                new BigDecimal("1000.00"), 555L, "rent");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Happy path: quote -> transfer -> fund, and the record ends FUNDED")
    void happyPath() {
        when(wiseClient.createQuote(anyString(), anyString(), any())).thenReturn(Mono.just(validQuote()));
        when(wiseClient.createTransfer(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(transfer(9001L)));
        when(wiseClient.fundTransfer(any())).thenReturn(Mono.just("funded"));

        TransferRecord result = service.execute(request("ref-happy")).block();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("FUNDED");
        assertThat(result.wiseTransferId()).isEqualTo(9001L);
        // Money survived the round trip at the right scale.
        assertThat(result.amount().amount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("IDEMPOTENCY: calling twice with the same clientReference creates ONE transfer")
    void doesNotDoublePay() {
        when(wiseClient.createQuote(anyString(), anyString(), any())).thenReturn(Mono.just(validQuote()));
        when(wiseClient.createTransfer(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(transfer(9002L)));
        when(wiseClient.fundTransfer(any())).thenReturn(Mono.just("funded"));

        TransferRecord first = service.execute(request("ref-dupe")).block();
        TransferRecord second = service.execute(request("ref-dupe")).block();

        // Same transfer came back both times...
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.wiseTransferId()).isEqualTo(first.wiseTransferId());

        // ...and, the part that actually matters, we only ever asked Wise once.
        verify(wiseClient, times(1)).createTransfer(any(), anyString(), anyString(), any());
        verify(wiseClient, times(1)).fundTransfer(any());
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("IDEMPOTENCY under concurrency: 10 threads, same reference, still one transfer")
    void concurrentDuplicatesCreateOneTransfer() throws Exception {
        when(wiseClient.createQuote(anyString(), anyString(), any())).thenReturn(Mono.just(validQuote()));
        when(wiseClient.createTransfer(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(transfer(9003L)));
        when(wiseClient.fundTransfer(any())).thenReturn(Mono.just("funded"));

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    service.execute(request("ref-race")).block();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        startGun.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // The atomic claim in TransferStore is what makes this hold.
        verify(wiseClient, times(1)).createTransfer(any(), anyString(), anyString(), any());
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Expired quote is refused before funding, not executed at a stale rate")
    void refusesExpiredQuote() {
        QuoteResponse expired = new QuoteResponse("quote-old", "USD", "INR",
                new BigDecimal("1000.00"), new BigDecimal("83000.00"),
                new BigDecimal("83.00"), Instant.now().minusSeconds(5), "EXPIRED");

        when(wiseClient.createQuote(anyString(), anyString(), any())).thenReturn(Mono.just(expired));

        assertThatThrownBy(() -> service.execute(request("ref-expired")).block())
                .isInstanceOf(QuoteExpiredException.class);

        // Nothing was created and nothing was funded — we failed loudly instead.
        verify(wiseClient, never()).createTransfer(any(), anyString(), anyString(), any());
        verify(wiseClient, never()).fundTransfer(any());
        assertThat(store.findByClientReference("ref-expired"))
                .get().extracting(TransferRecord::status).isEqualTo("QUOTE_EXPIRED");
    }

    @Test
    @DisplayName("Quote inside the expiry safety buffer is also refused")
    void refusesQuoteAboutToExpire() {
        QuoteResponse nearlyDead = new QuoteResponse("quote-edge", "USD", "INR",
                new BigDecimal("1000.00"), new BigDecimal("83000.00"),
                new BigDecimal("83.00"), Instant.now().plusSeconds(5), "PENDING");

        when(wiseClient.createQuote(anyString(), anyString(), any())).thenReturn(Mono.just(nearlyDead));

        assertThatThrownBy(() -> service.execute(request("ref-edge")).block())
                .isInstanceOf(QuoteExpiredException.class);
        verify(wiseClient, never()).createTransfer(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Funding failure leaves a CREATED_UNFUNDED record — the transfer is never lost")
    void fundingFailureIsRecorded() {
        when(wiseClient.createQuote(anyString(), anyString(), any())).thenReturn(Mono.just(validQuote()));
        when(wiseClient.createTransfer(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(transfer(9004L)));
        when(wiseClient.fundTransfer(any()))
                .thenReturn(Mono.error(new RuntimeException("balance service down")));

        assertThatThrownBy(() -> service.execute(request("ref-unfunded")).block())
                .isInstanceOf(RuntimeException.class);

        // This is the dangerous state, and the point of the test is that it is
        // VISIBLE. The reconciliation job picks this up rather than a customer
        // discovering it.
        TransferRecord record = store.findByClientReference("ref-unfunded").orElseThrow();
        assertThat(record.status()).isEqualTo("CREATED_UNFUNDED");
        assertThat(record.wiseTransferId()).isEqualTo(9004L);
        assertThat(record.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("REGRESSION: reconciliation must not erase the CREATED_UNFUNDED flag")
    void unfundedFlagSurvivesReconciliation() {
        // Found by running the service, not by reading the code.
        //
        // Wise keeps reporting an unfunded transfer as 'incoming_payment_waiting'
        // — true from its side, useless from ours. Blindly applying that status
        // wiped out the only marker saying funding had failed, so the money
        // stayed stuck and nothing was tracking it.
        when(wiseClient.createQuote(anyString(), anyString(), any())).thenReturn(Mono.just(validQuote()));
        when(wiseClient.createTransfer(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(transfer(9006L)));
        when(wiseClient.fundTransfer(any()))
                .thenReturn(Mono.error(new RuntimeException("balance service down")));

        assertThatThrownBy(() -> service.execute(request("ref-flag")).block())
                .isInstanceOf(RuntimeException.class);
        assertThat(store.findByClientReference("ref-flag").orElseThrow().status())
                .isEqualTo(TransferOrchestrationService.UNFUNDED);

        // Wise says "still waiting to be paid" — that does NOT mean funded.
        service.applyObservedStatus(9006L, "incoming_payment_waiting");
        assertThat(store.findByClientReference("ref-flag").orElseThrow().status())
                .as("the compensation flag must survive a non-funding remote status")
                .isEqualTo(TransferOrchestrationService.UNFUNDED);

        // But once funding genuinely progresses, the flag clears.
        service.applyObservedStatus(9006L, "processing");
        assertThat(store.findByClientReference("ref-flag").orElseThrow().status())
                .isEqualTo("processing");
    }

    @Test
    @DisplayName("Late webhook cannot move a completed transfer backwards")
    void statusNeverGoesBackwards() {
        when(wiseClient.createQuote(anyString(), anyString(), any())).thenReturn(Mono.just(validQuote()));
        when(wiseClient.createTransfer(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(transfer(9005L)));
        when(wiseClient.fundTransfer(any())).thenReturn(Mono.just("funded"));

        service.execute(request("ref-order")).block();

        // Transfer completes...
        service.applyObservedStatus(9005L, "outgoing_payment_sent");
        // ...then a stale event turns up late.
        service.applyObservedStatus(9005L, "processing");

        assertThat(store.findByClientReference("ref-order").orElseThrow().status())
                .isEqualTo("outgoing_payment_sent");
    }
}
