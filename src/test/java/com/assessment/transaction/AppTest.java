package com.assessment.transaction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppTest {

    @Test
    void duplicateBusinessTransactionDoesNotCreateAnotherLedgerEntry() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);

        processor.process(transaction("TX-1", "REQ-1", "ACC-1", 1, "10"));
        TransactionResult duplicate = processor.process(
                transaction("TX-1", "REQ-1-REPLAY", "ACC-1", 1, "10"));

        assertEquals(TransactionStatus.DUPLICATE, duplicate.status());
        assertEquals(1, store.ledgerEntries().size());
        assertEquals(1L, processor.summary().counts().get(TransactionStatus.PROCESSED));
        assertEquals(1L, processor.summary().counts().get(TransactionStatus.DUPLICATE));
    }

    @Test
    void conflictingReuseOfTransactionIdIsRejected() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);
        processor.process(transaction("TX-1", "REQ-1", "ACC-1", 1, "10"));

        TransactionResult conflict = processor.process(
                transaction("TX-1", "REQ-2", "ACC-1", 1, "11"));

        assertEquals(TransactionStatus.FAILED, conflict.status());
        assertEquals(1, store.ledgerEntries().size());
    }

    @Test
    void equalAmountsWithDifferentScaleAreTheSameBusinessData() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);
        processor.process(transaction("TX-1", "REQ-1", "ACC-1", 1, "10.0"));

        TransactionResult duplicate = processor.process(
                transaction("TX-1", "REQ-2", "ACC-1", 1, "10.00"));

        assertEquals(TransactionStatus.DUPLICATE, duplicate.status());
        assertEquals(1, store.ledgerEntries().size());
    }

    @Test
    void futureSequenceWaitsUntilTheMissingSequenceArrives() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);

        TransactionResult early = processor.process(
                transaction("TX-2", "REQ-2", "ACC-1", 2, "20"));
        assertEquals(TransactionStatus.PENDING, early.status());

        processor.process(transaction("TX-1", "REQ-1", "ACC-1", 1, "10"));

        assertEquals(List.of("TX-1", "TX-2"),
                store.ledgerEntries().stream().map(LedgerEntry::transactionId).toList());
        assertEquals(TransactionStatus.PROCESSED, processor.results().get(0).status());
        assertEquals(TransactionStatus.PROCESSED, processor.results().get(1).status());
    }

    @Test
    void pendingRecordReportsNextMissingSequenceAfterEarlierGapCloses() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);

        processor.process(transaction("TX-4", "REQ-4", "ACC-1", 4, "40"));
        TransactionResult sequenceOne = processor.process(
                transaction("TX-1", "REQ-1", "ACC-1", 1, "10"));
        TransactionResult pending = processor.results().get(0);

        assertEquals(TransactionStatus.PROCESSED, sequenceOne.status());
        assertEquals(TransactionStatus.PENDING, pending.status());
        assertEquals("Waiting for sequence 2", pending.message());
    }

    @Test
    void invalidRecordIsIsolatedFromValidRecords() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);
        TransactionRequest invalid = transaction("TX-BAD", "REQ-BAD", "ACC-1", 1, "-10");
        TransactionRequest valid = transaction("TX-1", "REQ-1", "ACC-1", 2, "10");

        processor.processAll(new InMemoryTransactionSource(List.of(invalid, valid)));

        assertEquals(TransactionStatus.FAILED, processor.results().get(0).status());
        assertEquals(TransactionStatus.PROCESSED, processor.results().get(1).status());
        assertEquals(1, store.ledgerEntries().size());
    }

    @Test
    void assessmentSuggestedDataProcessesInGlobalSequenceOrder() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransientFailurePolicy failOnce = (request, attempt) -> {
            if ("TXN-2005".equals(request.transactionId()) && attempt == 1) {
                throw new TransientProcessingException("temporary");
            }
        };
        TransactionProcessor processor = processor(store, failOnce, 3);
        List<TransactionRequest> assessmentData = List.of(
                transaction("TXN-2002", "REQ-2", "ACC-201", TransactionType.DEBIT, 2, "40"),
                transaction("TXN-2001", "REQ-1", "ACC-201", TransactionType.CREDIT, 1, "100"),
                transaction("TXN-2002", "REQ-2-DUP", "ACC-201", TransactionType.DEBIT, 2, "40"),
                transaction("TXN-2003", "REQ-3", "ACC-201", TransactionType.CREDIT, 3, "25"),
                transaction("TXN-2004", "REQ-4", "ACC-202", TransactionType.DEBIT, 4, "-10"),
                transaction("TXN-2005", "REQ-5", "ACC-203", TransactionType.CREDIT, 5, "500"));

        processor.processAll(new InMemoryTransactionSource(assessmentData));

        assertEquals(List.of(
                        TransactionStatus.PROCESSED,
                        TransactionStatus.PROCESSED,
                        TransactionStatus.DUPLICATE,
                        TransactionStatus.PROCESSED,
                        TransactionStatus.FAILED,
                        TransactionStatus.PROCESSED),
                processor.results().stream().map(TransactionResult::status).toList());
        assertEquals(2, processor.results().get(5).attempts());
        assertEquals(List.of("TXN-2001", "TXN-2002", "TXN-2003", "TXN-2005"),
                store.ledgerEntries().stream().map(LedgerEntry::transactionId).toList());
    }

    @Test
    void transientFailureIsRetriedWithoutDuplicatingTheEffect() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        AtomicInteger failures = new AtomicInteger();
        TransientFailurePolicy failOnce = (request, attempt) -> {
            if (failures.getAndIncrement() == 0) {
                throw new TransientProcessingException("temporary");
            }
        };
        TransactionProcessor processor = processor(store, failOnce, 3);

        TransactionResult result = processor.process(
                transaction("TX-1", "REQ-1", "ACC-1", 1, "10"));

        assertEquals(TransactionStatus.PROCESSED, result.status());
        assertEquals(2, result.attempts());
        assertEquals(1, store.ledgerEntries().size());
    }

    @Test
    void exhaustedRetryCanBeRecoveredAndUnblocksLaterSequence() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        AtomicInteger failuresRemaining = new AtomicInteger(2);
        TransientFailurePolicy failTwice = (request, attempt) -> {
            if (failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new TransientProcessingException("temporary");
            }
        };
        TransactionProcessor processor = processor(store, failTwice, 2);

        TransactionResult failed = processor.process(
                transaction("TX-1", "REQ-1", "ACC-1", 1, "10"));
        TransactionResult pending = processor.process(
                transaction("TX-2", "REQ-2", "ACC-1", 2, "20"));

        assertEquals(TransactionStatus.FAILED, failed.status());
        assertEquals(TransactionStatus.PENDING, pending.status());

        TransactionResult recovered = processor.retryFailed("TX-1");

        assertEquals(TransactionStatus.PROCESSED, recovered.status());
        assertEquals(TransactionStatus.PROCESSED, processor.results().get(1).status());
        assertEquals(List.of("TX-1", "TX-2"),
                store.ledgerEntries().stream().map(LedgerEntry::transactionId).toList());
    }

    @Test
    void repeatedRequestIdWithDifferentDataIsRejected() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);
        processor.process(transaction("TX-1", "REQ-1", "ACC-1", 1, "10"));

        TransactionResult conflict = processor.process(
                transaction("TX-2", "REQ-1", "ACC-1", 2, "20"));

        assertEquals(TransactionStatus.FAILED, conflict.status());
        assertEquals(1, store.ledgerEntries().size());
    }

    @Test
    void requestIdReusedForDifferentTransactionIsRejectedEvenWhenOtherFieldsMatch() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);
        processor.process(transaction("TX-1", "REQ-1", "ACC-1", 1, "10"));

        TransactionResult conflict = processor.process(
                transaction("TX-2", "REQ-1", "ACC-1", 1, "10"));

        assertEquals(TransactionStatus.FAILED, conflict.status());
        assertEquals(1, store.ledgerEntries().size());
    }

    @Test
    void differentTransactionCannotClaimAnAlreadyPendingSequence() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);
        processor.process(transaction("TX-2", "REQ-2", "ACC-1", 2, "20"));

        TransactionResult conflict = processor.process(
                transaction("TX-OTHER", "REQ-OTHER", "ACC-1", 2, "25"));

        assertEquals(TransactionStatus.FAILED, conflict.status());
        assertEquals(2, processor.results().size());
        assertEquals(TransactionStatus.PENDING, processor.results().get(0).status());
        assertEquals(TransactionStatus.FAILED, processor.results().get(1).status());
        assertEquals(0, store.ledgerEntries().size());
    }

    @Test
    void nullRequestIsRejectedWithoutStoppingBatch() {
        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransactionProcessor processor = processor(store, TransientFailurePolicy.neverFail(), 3);

        processor.process(null);
        processor.process(transaction("TX-1", "REQ-1", "ACC-1", 1, "10"));

        assertEquals(TransactionStatus.FAILED, processor.results().get(0).status());
        assertEquals(TransactionStatus.PROCESSED, processor.results().get(1).status());
    }

    @Test
    void missingRequiredFieldIsRejected() {
        TransactionValidator validator = new TransactionValidator();
        TransactionRequest missingCurrency = new TransactionRequest(
                "TX-1", "REQ-1", "ACC-1", TransactionType.CREDIT,
                new BigDecimal("10"), " ", 1);

        assertEquals("currency is required", validator.validate(missingCurrency).orElseThrow());
    }

    @Test
    void maxAttemptsMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> processor(new InMemoryTransactionStateStore(),
                        TransientFailurePolicy.neverFail(), 0));
    }

    private TransactionProcessor processor(
            InMemoryTransactionStateStore store,
            TransientFailurePolicy failurePolicy,
            int maxAttempts) {
        return new TransactionProcessor(
                new TransactionValidator(), store, failurePolicy, maxAttempts);
    }

    private TransactionRequest transaction(
            String transactionId,
            String requestId,
            String accountId,
            long sequence,
            String amount) {
        return transaction(transactionId, requestId, accountId, TransactionType.CREDIT, sequence, amount);
    }

    private TransactionRequest transaction(
            String transactionId,
            String requestId,
            String accountId,
            TransactionType type,
            long sequence,
            String amount) {
        return new TransactionRequest(
                transactionId,
                requestId,
                accountId,
                type,
                new BigDecimal(amount),
                "USD",
                sequence);
    }
}
