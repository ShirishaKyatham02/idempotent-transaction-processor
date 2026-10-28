package com.assessment.transaction;

public enum TransactionStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    DUPLICATE,
    FAILED,
    RETRY_PENDING,
    PENDING
}
