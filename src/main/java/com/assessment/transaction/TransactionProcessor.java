package com.assessment.transaction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TransactionProcessor {
    private static final Logger LOGGER = Logger.getLogger(TransactionProcessor.class.getName());

    private final TransactionValidator validator;
    private final InMemoryTransactionStateStore stateStore;
    private final TransientFailurePolicy failurePolicy;
    private final int maxAttempts;
    private final List<MutableResult> history = new ArrayList<>();
    private final Map<String, MutableResult> byTransactionId = new HashMap<>();
    private final Map<String, MutableResult> byRequestId = new HashMap<>();
    private final TreeMap<Long, MutableResult> pendingBySequence = new TreeMap<>();
    private long nextSequence = 1;

    public TransactionProcessor(
            TransactionValidator validator,
            InMemoryTransactionStateStore stateStore,
            TransientFailurePolicy failurePolicy,
            int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.validator = Objects.requireNonNull(validator, "validator");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.maxAttempts = maxAttempts;
    }

    public synchronized List<TransactionResult> processAll(TransactionSource source) {
        for (TransactionRequest request : Objects.requireNonNull(source, "source").readTransactions()) {
            process(request);
        }
        return results();
    }

    public synchronized TransactionResult process(TransactionRequest request) {
        MutableResult result = new MutableResult(request);
        history.add(result);

        Optional<String> validationFailure = validator.validate(request);
        if (validationFailure.isPresent()) {
            result.fail(validationFailure.get());
            logOutcome(result);
            reserveInvalidSequence(result);
            return result.snapshot();
        }

        MutableResult transactionMatch = byTransactionId.get(request.transactionId());
        if (transactionMatch != null) {
            if (request.hasSameBusinessData(transactionMatch.request)) {
                result.duplicate("Business transaction was already received");
            } else {
                result.fail("transactionId was reused with different business data");
            }
            logOutcome(result);
            return result.snapshot();
        }

        MutableResult requestMatch = byRequestId.get(request.requestId());
        if (requestMatch != null) {
            if (request.hasSameBusinessData(requestMatch.request)) {
                result.duplicate("requestId was already received");
            } else {
                result.fail("requestId was reused with different transaction data");
            }
            logOutcome(result);
            return result.snapshot();
        }

        if (request.sequenceNumber() < nextSequence) {
            result.fail("sequenceNumber is older than the next expected sequence");
            logOutcome(result);
            return result.snapshot();
        }

        if (pendingBySequence.containsKey(request.sequenceNumber())) {
            result.fail("Another transaction already uses this sequenceNumber");
            logOutcome(result);
            return result.snapshot();
        }

        byTransactionId.put(request.transactionId(), result);
        byRequestId.put(request.requestId(), result);
        result.status = TransactionStatus.RECEIVED;
        pendingBySequence.put(request.sequenceNumber(), result);

        if (request.sequenceNumber() > nextSequence) {
            result.status = TransactionStatus.PENDING;
            result.message = "Waiting for sequence " + nextSequence;
            logOutcome(result);
            return result.snapshot();
        }

        processReady();
        return result.snapshot();
    }

    public synchronized TransactionResult retryFailed(String transactionId) {
        MutableResult result = byTransactionId.get(transactionId);
        if (result == null) {
            throw new IllegalArgumentException("Unknown transactionId: " + transactionId);
        }
        if (result.status != TransactionStatus.FAILED) {
            return result.snapshot();
        }
        if (result.request.sequenceNumber() != nextSequence) {
            throw new IllegalStateException("Failed transaction is not the next sequence");
        }

        processWithRetries(result);
        if (result.status == TransactionStatus.PROCESSED) {
            advanceSequence();
            processReady();
        }
        return result.snapshot();
    }

    public synchronized List<TransactionResult> results() {
        return history.stream().map(MutableResult::snapshot).toList();
    }

    public synchronized ProcessingSummary summary() {
        EnumMap<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) {
            counts.put(status, 0L);
        }
        for (MutableResult result : history) {
            counts.compute(result.status, (status, count) -> count + 1);
        }
        return new ProcessingSummary(counts);
    }

    private void reserveInvalidSequence(MutableResult result) {
        if (result.request == null || result.request.sequenceNumber() < nextSequence
                || pendingBySequence.containsKey(result.request.sequenceNumber())) {
            return;
        }

        pendingBySequence.put(result.request.sequenceNumber(), result);
        if (result.request.sequenceNumber() == nextSequence) {
            processReady();
        } else {
            result.message += "; reserving sequence " + result.request.sequenceNumber()
                    + " until earlier sequences arrive";
        }
    }

    private void processReady() {
        while (true) {
            MutableResult next = pendingBySequence.remove(nextSequence);
            if (next == null) {
                for (MutableResult pending : pendingBySequence.values()) {
                    if (pending.status == TransactionStatus.PENDING) {
                        pending.message = "Waiting for sequence " + nextSequence;
                    }
                }
                return;
            }
            if (next.status != TransactionStatus.FAILED) {
                processWithRetries(next);
                if (next.status != TransactionStatus.PROCESSED) {
                    return;
                }
            }
            advanceSequence();
        }
    }

    private void processWithRetries(MutableResult result) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            result.status = TransactionStatus.PROCESSING;
            result.attempts++;
            try {
                failurePolicy.beforeApply(result.request, attempt);
                boolean applied = stateStore.apply(result.request);
                result.status = TransactionStatus.PROCESSED;
                result.message = applied ? "Processed" : "Effect was already applied";
                logOutcome(result);
                return;
            } catch (TransientProcessingException exception) {
                result.message = exception.getMessage();
                if (attempt < maxAttempts) {
                    result.status = TransactionStatus.RETRY_PENDING;
                    LOGGER.log(Level.WARNING,
                            "Transient failure for transaction {0}; retrying attempt {1}",
                            new Object[]{result.request.transactionId(), attempt + 1});
                } else {
                    result.status = TransactionStatus.FAILED;
                    LOGGER.log(Level.WARNING,
                            "Retry limit reached for transaction {0}",
                            result.request.transactionId());
                    logOutcome(result);
                }
            }
        }
    }

    private void advanceSequence() {
        nextSequence++;
    }

    private void logOutcome(MutableResult result) {
        if (result.request == null || result.request.transactionId() == null) {
            LOGGER.log(Level.WARNING, "Transaction request rejected: {0}", result.status);
            return;
        }
        LOGGER.log(Level.INFO, "Transaction {0}: {1}",
                new Object[]{result.request.transactionId(), result.status});
    }

    private static final class MutableResult {
        private final TransactionRequest request;
        private TransactionStatus status = TransactionStatus.RECEIVED;
        private int attempts;
        private String message = "";

        private MutableResult(TransactionRequest request) {
            this.request = request;
        }

        private void fail(String reason) {
            status = TransactionStatus.FAILED;
            message = reason;
        }

        private void duplicate(String reason) {
            status = TransactionStatus.DUPLICATE;
            message = reason;
        }

        private TransactionResult snapshot() {
            return new TransactionResult(request, status, attempts, message);
        }
    }
}
