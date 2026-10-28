package com.assessment.transaction;

import java.math.BigDecimal;

public record LedgerEntry(
        String transactionId,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        long sequenceNumber) {
}
