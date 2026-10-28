package com.assessment.transaction;

import java.math.BigDecimal;
import java.util.Objects;

public record TransactionRequest(
        String transactionId,
        String requestId,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        long sequenceNumber) {

    public boolean hasSameBusinessData(TransactionRequest other) {
        return other != null
                && Objects.equals(transactionId, other.transactionId)
                && Objects.equals(accountId, other.accountId)
                && transactionType == other.transactionType
                && amountsEqual(amount, other.amount)
                && Objects.equals(currency, other.currency)
                && sequenceNumber == other.sequenceNumber;
    }

    private boolean amountsEqual(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }
}
