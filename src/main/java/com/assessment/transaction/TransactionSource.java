package com.assessment.transaction;

import java.util.List;

public interface TransactionSource {
    List<TransactionRequest> readTransactions();
}
