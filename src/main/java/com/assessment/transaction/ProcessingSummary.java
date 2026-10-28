package com.assessment.transaction;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record ProcessingSummary(Map<TransactionStatus, Long> counts) {
    public ProcessingSummary {
        EnumMap<TransactionStatus, Long> copy = new EnumMap<>(TransactionStatus.class);
        copy.putAll(counts);
        counts = Collections.unmodifiableMap(copy);
    }
}
