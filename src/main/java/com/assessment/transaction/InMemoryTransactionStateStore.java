package com.assessment.transaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InMemoryTransactionStateStore {
    private final Set<String> appliedTransactionIds = new HashSet<>();
    private final List<LedgerEntry> ledgerEntries = new ArrayList<>();

    public synchronized boolean apply(TransactionRequest request) {
        if (!appliedTransactionIds.add(request.transactionId())) {
            return false;
        }
        ledgerEntries.add(new LedgerEntry(
                request.transactionId(),
                request.accountId(),
                request.transactionType(),
                request.amount(),
                request.currency(),
                request.sequenceNumber()));
        return true;
    }

    public synchronized List<LedgerEntry> ledgerEntries() {
        return List.copyOf(ledgerEntries);
    }
}
