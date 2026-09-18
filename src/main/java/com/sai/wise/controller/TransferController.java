package com.sai.wise.controller;

import com.sai.wise.exception.QuoteExpiredException;
import com.sai.wise.exception.WisePermanentException;
import com.sai.wise.model.RecipientResponse;
import com.sai.wise.model.TransferRecord;
import com.sai.wise.model.TransferRequest;
import com.sai.wise.client.WiseApiClient;
import com.sai.wise.service.TransferOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The partner-facing surface of this service.
 *
 * <p>Error responses are deliberately explicit. When an integration fails at
 * 2am, the partner's engineer reads the response body — so it needs to say
 * what went wrong and whether retrying is safe, not just "500 internal error".
 * Good error messages are a feature of a platform product.
 */
@RestController
@RequestMapping("/api/v1")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferOrchestrationService orchestration;
    private final WiseApiClient wiseClient;

    public TransferController(TransferOrchestrationService orchestration, WiseApiClient wiseClient) {
        this.orchestration = orchestration;
        this.wiseClient = wiseClient;
    }

    @Operation(summary = "Get an indicative quote — rate, fee and expiry")
    @GetMapping("/quotes")
    public ResponseEntity<?> quote(@RequestParam String source,
                                   @RequestParam String target,
                                   @RequestParam BigDecimal amount) {
        var q = wiseClient.createQuote(source, target, amount).block();
        return ResponseEntity.ok(q);
    }

    @Operation(summary = "Create a recipient. Required detail fields vary by corridor.")
    @PostMapping("/recipients")
    public ResponseEntity<RecipientResponse> recipient(@RequestBody RecipientRequestBody body) {
        var r = wiseClient.createRecipient(body.currency(), body.type(),
                body.accountHolderName(), body.details()).block();
        return ResponseEntity.ok(r);
    }

    /**
     * Send money. Safe to retry with the same clientReference — you will get
     * the original transfer back, not a second payment.
     */
    @Operation(summary = "Execute a transfer (idempotent on clientReference)")
    @PostMapping("/transfers")
    public ResponseEntity<?> transfer(@Valid @RequestBody TransferRequest request) {
        try {
            TransferRecord record = orchestration.execute(request).block();
            return ResponseEntity.ok(record);

        } catch (QuoteExpiredException e) {
            // 409: the request was valid, the world moved. Re-quote and resubmit.
            return ResponseEntity.status(409).body(Map.of(
                    "error", "QUOTE_EXPIRED",
                    "message", e.getMessage(),
                    "retryable", true,
                    "action", "Request a new quote and resubmit with the same clientReference."));

        } catch (WisePermanentException e) {
            return ResponseEntity.status(422).body(Map.of(
                    "error", "WISE_REJECTED",
                    "status", e.getStatusCode(),
                    "message", e.getMessage(),
                    "retryable", false,
                    "action", "Fix the request; retrying unchanged will fail identically."));

        } catch (Exception e) {
            log.error("[API] transfer failed clientReference={}", request.clientReference(), e);
            // Note what we tell them: retry is SAFE, because the orchestration
            // is idempotent on clientReference. That single sentence prevents a
            // partner from being scared to retry and leaving money in limbo.
            return ResponseEntity.status(503).body(Map.of(
                    "error", "UPSTREAM_UNAVAILABLE",
                    "message", e.getMessage() == null ? "upstream failure" : e.getMessage(),
                    "retryable", true,
                    "action", "Retry with the SAME clientReference; the operation is idempotent."));
        }
    }

    @Operation(summary = "Look up what we know about a transfer")
    @GetMapping("/transfers/{clientReference}")
    public ResponseEntity<?> lookup(@PathVariable String clientReference) {
        return orchestration.lookup(clientReference)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "NOT_FOUND",
                        "clientReference", clientReference)));
    }

    public record RecipientRequestBody(String currency,
                                       String type,
                                       String accountHolderName,
                                       Map<String, Object> details) {}
}
