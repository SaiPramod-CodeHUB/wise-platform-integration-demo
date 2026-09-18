package com.sai.wise.service;

import com.sai.wise.client.WiseApiClient;
import com.sai.wise.config.WiseProperties;
import com.sai.wise.model.TransferRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The most payments-literate thing in this repository.
 *
 * <p>Webhooks are a convenience, not a guarantee. They get dropped, they get
 * delivered to an instance that was mid-restart, they get rejected by a bad
 * deploy. If webhooks are your only mechanism for learning that a payment
 * completed, then one missed delivery means a customer's money is in a state
 * your system will never learn about.
 *
 * <p>So: on a schedule, take everything that is not in a terminal state, ask
 * Wise what it actually thinks, and make our record agree. Wise is the source
 * of truth; we are a cache that must never be allowed to drift.
 *
 * <p>Anything stuck non-terminal beyond the stale threshold is escalated rather
 * than silently retried forever. A payment that has been "processing" for an
 * hour is a human problem, and the worst outcome in this domain is a system
 * that quietly retries while a customer waits.
 */
@Service
@ConditionalOnProperty(value = "wise.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final WiseApiClient wiseClient;
    private final TransferStore store;
    private final TransferOrchestrationService orchestration;
    private final WiseProperties props;

    public ReconciliationService(WiseApiClient wiseClient,
                                 TransferStore store,
                                 TransferOrchestrationService orchestration,
                                 WiseProperties props) {
        this.wiseClient = wiseClient;
        this.store = store;
        this.orchestration = orchestration;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${wise.reconciliation.fixed-delay-ms:300000}")
    public void reconcile() {

        List<TransferRecord> open = store.findNonTerminal();
        if (open.isEmpty()) {
            log.debug("[RECONCILE] nothing outstanding");
            return;
        }

        log.info("[RECONCILE] checking {} non-terminal transfers", open.size());

        int corrected = 0;
        int escalated = 0;

        for (TransferRecord record : open) {

            // A record that never got as far as a Wise transfer id is a failure
            // on our side — it needs attention, not a status lookup.
            if (record.wiseTransferId() == null) {
                if (isStale(record)) {
                    escalate(record, "no Wise transfer id — orchestration failed before creation");
                    escalated++;
                }
                continue;
            }

            try {
                var remote = wiseClient.getTransfer(record.wiseTransferId()).block();
                if (remote == null) {
                    log.warn("[RECONCILE] no response for transferId={}", record.wiseTransferId());
                    continue;
                }

                // The divergence check: does what we believe match reality?
                if (!remote.status().equalsIgnoreCase(record.status())) {
                    log.info("[RECONCILE] divergence transferId={} local='{}' remote='{}'",
                            record.wiseTransferId(), record.status(), remote.status());
                    orchestration.applyObservedStatus(record.wiseTransferId(), remote.status());

                    // Only count it as corrected if the local state actually
                    // changed. An unfunded transfer deliberately keeps its flag,
                    // and reporting that as a correction would overstate what
                    // the sweep achieved.
                    boolean changed = store.findByWiseTransferId(record.wiseTransferId())
                            .map(after -> !after.status().equals(record.status()))
                            .orElse(false);
                    if (changed) corrected++;
                }

                // A transfer we created but failed to fund is the worst state in
                // the system: it exists at Wise, a customer is waiting, and no
                // money is moving. It gets escalated on EVERY sweep, not only
                // once it goes stale, because time spent here is time a real
                // person is out of pocket.
                if (TransferOrchestrationService.UNFUNDED.equals(record.status())) {
                    escalate(record, "transfer created but funding failed - needs compensation "
                            + "(retry funding or cancel); Wise reports '" + remote.status() + "'");
                    escalated++;
                } else if (remote.needsAttention()) {
                    escalate(record, "Wise reports hasActiveIssues=true");
                    escalated++;
                } else if (isStale(record) && !remote.isTerminal()) {
                    escalate(record, "still non-terminal after "
                            + props.getReconciliation().getStaleAfterMinutes() + " minutes");
                    escalated++;
                }

            } catch (Exception e) {
                // One bad transfer must not stop the sweep.
                log.error("[RECONCILE] failed to check transferId={}", record.wiseTransferId(), e);
            }
        }

        log.info("[RECONCILE] done checked={} corrected={} escalated={}", open.size(), corrected, escalated);
    }

    private boolean isStale(TransferRecord record) {
        return Duration.between(record.createdAt(), Instant.now())
                .toMinutes() > props.getReconciliation().getStaleAfterMinutes();
    }

    /**
     * In production this raises an alert — a Splunk alert, a PagerDuty trigger,
     * a row on an ops dashboard. The point is that a human finds out. Logging
     * it and hoping somebody greps is not an escalation.
     */
    private void escalate(TransferRecord record, String reason) {
        log.error("[ESCALATE] clientReference={} transferId={} status={} reason={}",
                record.clientReference(), record.wiseTransferId(), record.status(), reason);
    }
}
