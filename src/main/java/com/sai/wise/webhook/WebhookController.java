package com.sai.wise.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sai.wise.model.WebhookEvent;
import com.sai.wise.service.TransferOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Receives transfer status events from Wise.
 *
 * <p>Three defences, in order:
 * <ol>
 *   <li><b>Verify the signature</b> against the raw body before parsing anything.</li>
 *   <li><b>De-duplicate</b> — the same event can be delivered more than once,
 *       and at-least-once delivery is the norm, not an edge case.</li>
 *   <li><b>Never move state backwards</b> — events can arrive out of order, so
 *       a late event for an already-completed transfer is dropped.</li>
 * </ol>
 *
 * <p>We answer 200 quickly. A webhook sender treats a slow or failed response
 * as a delivery failure and retries, so doing real work inline here creates a
 * retry storm. Acknowledge first, process after.
 */
@RestController
@RequestMapping("/webhooks/wise")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final int MAX_REMEMBERED_EVENTS = 10_000;

    private final WebhookSignatureVerifier verifier;
    private final TransferOrchestrationService orchestration;
    private final ObjectMapper objectMapper;

    /**
     * Seen-event ids, for de-duplication. Bounded LinkedHashMap so a long-running
     * process cannot leak memory; in production this is Redis with a TTL, shared
     * across instances — an in-memory set only de-dupes within one pod.
     */
    private final Set<String> seenEvents = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > MAX_REMEMBERED_EVENTS;
                }
            }));

    public WebhookController(WebhookSignatureVerifier verifier,
                             TransferOrchestrationService orchestration,
                             ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.orchestration = orchestration;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Receive a Wise transfer state-change webhook")
    @PostMapping("/transfers")
    public ResponseEntity<String> receive(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Signature-SHA256", required = false) String signature) {

        // 1. Trust nothing until the signature checks out, and check it against
        //    the raw bytes — not a re-serialised object.
        if (!verifier.isValid(rawBody, signature)) {
            log.warn("[WEBHOOK] rejected unverified delivery");
            return ResponseEntity.status(401).body("invalid signature");
        }

        WebhookEvent event;
        try {
            event = objectMapper.readValue(rawBody, WebhookEvent.class);
        } catch (Exception e) {
            // Malformed body: 400, and do NOT ask for a retry — it will be
            // malformed again.
            log.error("[WEBHOOK] unparseable body", e);
            return ResponseEntity.badRequest().body("malformed payload");
        }

        // 2. De-duplicate. Answer 200 either way: telling the sender we failed
        //    would just make it redeliver an event we have already handled.
        String key = event.dedupeKey();
        if (!seenEvents.add(key)) {
            log.info("[WEBHOOK] duplicate delivery ignored key={}", key);
            return ResponseEntity.ok("duplicate ignored");
        }

        // 3. Apply. The orchestration service refuses to move a terminal
        //    transfer backwards, which is what protects us from out-of-order
        //    delivery.
        if (event.data() != null && event.data().resource() != null) {
            orchestration.applyObservedStatus(
                    event.data().resource().id(),
                    event.data().currentState());
        } else {
            log.warn("[WEBHOOK] event had no resource; nothing to apply");
        }

        return ResponseEntity.ok("ok");
    }
}
