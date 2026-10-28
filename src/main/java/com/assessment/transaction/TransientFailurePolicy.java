package com.assessment.transaction;

@FunctionalInterface
public interface TransientFailurePolicy {
    void beforeApply(TransactionRequest request, int attempt) throws TransientProcessingException;

    static TransientFailurePolicy neverFail() {
        return (request, attempt) -> {
        };
    }
}
