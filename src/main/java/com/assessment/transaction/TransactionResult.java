package com.assessment.transaction;

public record TransactionResult(
        TransactionRequest request,
        TransactionStatus status,
        int attempts,
        String message) {
}
