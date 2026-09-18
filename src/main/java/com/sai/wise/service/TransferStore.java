package com.sai.wise.service;

import com.sai.wise.model.TransferRecord;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable-ish local record of every transfer we have attempted.
 *
 * <p>Two jobs:
 * <ol>
 *   <li><b>Idempotency.</b> Keyed on the partner's own clientReference, so if
 *       their system retries a request we return the transfer we already made
 *       instead of making a second one.</li>
 *   <li><b>Reconciliation.</b> Gives the scheduled job a list of everything not
 *       yet in a terminal state, so we can ask Wise what really happened.</li>
 * </ol>
 *
 * <p>In-memory here so the demo runs with no database. In production this is a
 * table with a unique constraint on client_reference — and the unique
 * constraint, not the application check, is what actually guarantees
 * correctness under concurrency across multiple instances.
 *
 * <p>ConcurrentHashMap with computeIfAbsent gives us the same atomic
 * check-and-insert within a single instance: two threads racing the same
 * clientReference cannot both win.
 */
@Component
public class TransferStore {

    private final Map<String, TransferRecord> byClientReference = new ConcurrentHashMap<>();

    /**
     * Atomically claim a clientReference.
     *
     * @return the existing record if one was already claimed, otherwise empty
     *         and the supplied record is stored.
     */
    public Optional<TransferRecord> putIfAbsent(TransferRecord record) {
        TransferRecord existing = byClientReference.putIfAbsent(record.clientReference(), record);
        return Optional.ofNullable(existing);
    }

    public void save(TransferRecord record) {
        byClientReference.put(record.clientReference(), record);
    }

    public Optional<TransferRecord> findByClientReference(String clientReference) {
        return Optional.ofNullable(byClientReference.get(clientReference));
    }

    public Optional<TransferRecord> findByWiseTransferId(Long wiseTransferId) {
        if (wiseTransferId == null) return Optional.empty();
        return byClientReference.values().stream()
                .filter(r -> wiseTransferId.equals(r.wiseTransferId()))
                .findFirst();
    }

    /** Everything the reconciliation job needs to chase. */
    public List<TransferRecord> findNonTerminal() {
        return byClientReference.values().stream()
                .filter(r -> !r.isTerminal())
                .toList();
    }

    public Collection<TransferRecord> findAll() {
        return byClientReference.values();
    }

    public int size() {
        return byClientReference.size();
    }
}
