package com.assessment.transaction;

import java.util.Optional;

public final class TransactionValidator {
    public Optional<String> validate(TransactionRequest request) {
        if (request == null) {
            return Optional.of("Transaction request is missing");
        }
        if (isBlank(request.transactionId())) {
            return Optional.of("transactionId is required");
        }
        if (isBlank(request.requestId())) {
            return Optional.of("requestId is required");
        }
        if (isBlank(request.accountId())) {
            return Optional.of("accountId is required");
        }
        if (request.transactionType() == null) {
            return Optional.of("transactionType is required");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            return Optional.of("amount must be greater than zero");
        }
        if (isBlank(request.currency())) {
            return Optional.of("currency is required");
        }
        if (request.sequenceNumber() < 1) {
            return Optional.of("sequenceNumber must be at least 1");
        }
        return Optional.empty();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
