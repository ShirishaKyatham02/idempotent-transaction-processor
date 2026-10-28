package com.assessment.transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Logger;

public final class App {
    private static final Logger LOGGER = Logger.getLogger(App.class.getName());

    private App() {
    }

    public static void main(String[] args) {
        TransactionSource source = new InMemoryTransactionSource(List.of(
                request("TXN-2002", "REQ-2", "ACC-201", TransactionType.DEBIT, "40", 2),
                request("TXN-2001", "REQ-1", "ACC-201", TransactionType.CREDIT, "100", 1),
                request("TXN-2002", "REQ-2-DUP", "ACC-201", TransactionType.DEBIT, "40", 2),
                request("TXN-2003", "REQ-3", "ACC-201", TransactionType.CREDIT, "25", 3),
                request("TXN-2004", "REQ-4", "ACC-202", TransactionType.DEBIT, "-10", 4),
                request("TXN-2005", "REQ-5", "ACC-203", TransactionType.CREDIT, "500", 5)));

        InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
        TransientFailurePolicy failOnce = (transaction, attempt) -> {
            if ("TXN-2005".equals(transaction.transactionId()) && attempt == 1) {
                throw new TransientProcessingException("Simulated temporary failure");
            }
        };
        TransactionProcessor processor = new TransactionProcessor(
                new TransactionValidator(), store, failOnce, 3);

        processor.processAll(source);
        processor.results().forEach(result ->
                System.out.printf("%s %s attempts=%d (%s)%n",
                        result.request() == null ? "unknown" : result.request().transactionId(),
                        result.status(), result.attempts(), result.message()));

        ProcessingSummary summary = processor.summary();
        System.out.println("Summary: " + summary.counts());
        System.out.println("Ledger entries: " + store.ledgerEntries().size());
        LOGGER.info("Transaction batch completed");
    }

    private static TransactionRequest request(
            String transactionId,
            String requestId,
            String accountId,
            TransactionType type,
            String amount,
            long sequence) {
        return new TransactionRequest(
                transactionId, requestId, accountId, type, new BigDecimal(amount), "USD", sequence);
    }
}
