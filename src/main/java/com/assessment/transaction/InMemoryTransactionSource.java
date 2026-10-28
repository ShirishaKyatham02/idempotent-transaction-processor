package com.assessment.transaction;

import java.util.List;

public final class InMemoryTransactionSource implements TransactionSource {
    private final List<TransactionRequest> transactions;

    public InMemoryTransactionSource(List<TransactionRequest> transactions) {
        this.transactions = List.copyOf(transactions);
    }

    @Override
    public List<TransactionRequest> readTransactions() {
        return transactions;
    }
}
