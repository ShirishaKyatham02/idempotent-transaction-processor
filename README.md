# Idempotent Transaction Processor

A small Java/Maven example of in-memory background transaction processing. It demonstrates idempotency, sequence ordering, bounded transient retries, failure recovery, validation, and status summaries without external services or frameworks.

## Requirements

- Java 25 to build with the project's configured target.
- Maven 3.8 or later.

## Run

```shell
mvn clean test
mvn compile
java -cp target/classes com.assessment.transaction.App
```

The sample runner uses the six suggested L2 assessment records. It sends sequence 2 before sequence 1, replays a transaction with a different request ID, includes the invalid negative amount, and simulates a transient failure for TXN-2005.

When running tests on a Java 17 machine, the compiler release can be temporarily overridden:

```shell
mvn -Dmaven.compiler.release=17 clean test
```

This override only validates Java 17-compatible source and does not verify the configured Java 25 target.

## Sample input and output

Sample requests (in arrival order):

| transactionId | requestId | accountId | type | amount | currency | sequence |
|---|---|---|---|---:|---|---:|
| TXN-2002 | REQ-2 | ACC-201 | DEBIT | 40 | USD | 2 |
| TXN-2001 | REQ-1 | ACC-201 | CREDIT | 100 | USD | 1 |
| TXN-2002 | REQ-2-DUP | ACC-201 | DEBIT | 40 | USD | 2 |
| TXN-2003 | REQ-3 | ACC-201 | CREDIT | 25 | USD | 3 |
| TXN-2004 | REQ-4 | ACC-202 | DEBIT | -10 | USD | 4 |
| TXN-2005 | REQ-5 | ACC-203 | CREDIT | 500 | USD | 5 |

Expected final outcomes (logging timestamps and formatting may vary):

```text
TXN-2002 PROCESSED attempts=1 (Processed)
TXN-2001 PROCESSED attempts=1 (Processed)
TXN-2002 DUPLICATE attempts=0 (Business transaction was already received)
TXN-2003 PROCESSED attempts=1 (Processed)
TXN-2004 FAILED attempts=0 (amount must be greater than zero)
TXN-2005 PROCESSED attempts=2 (Processed)
Summary: {RECEIVED=0, PROCESSING=0, PROCESSED=4, DUPLICATE=1, FAILED=1, RETRY_PENDING=0, PENDING=0}
Ledger entries: 4
```

## Design and operational notes

- **Architecture:** `TransactionSource` separates ingestion from `TransactionProcessor`; the starter implementation uses `InMemoryTransactionSource`. `TransactionValidator` validates requests, and `InMemoryTransactionStateStore` records applied ledger entries. `App` wires a sample batch and a deterministic failure policy.
- **Transaction fields:** transaction ID, request ID, account ID, transaction type, positive amount, currency, and positive sequence number. Amount uses `BigDecimal`. Invalid requests are marked `FAILED` and do not stop later inputs.
- **Idempotency:** `transactionId` is the business idempotency key. Identical business data with a different request ID is a duplicate. Reusing a transaction ID or request ID with conflicting business data is rejected as `FAILED`. The state store also guards ledger application by transaction ID.
- **Ordering:** to match the supplied assessment data, sequence numbers are treated as one contiguous stream starting at 1 across the input batch, even when account IDs differ. Future sequences stay `PENDING` until missing earlier sequences arrive. A transiently failed sequence blocks later sequences until `retryFailed(transactionId)` recovers it. A validation-failed record with a usable, unclaimed sequence is rejected and consumes that sequence, so one bad record does not permanently block later events. A stale sequence or a different transaction using a sequence already held in the pending queue is rejected. A production source whose sequence numbers are per account should use a separate ordering cursor per account instead.
- **Retries and recovery:** a `TransientFailurePolicy` can simulate retryable errors. Processing retries immediately up to the configured maximum attempts. Exhaustion leaves the record `FAILED`; `retryFailed` starts a new bounded retry cycle for a failed next-in-sequence transaction. Unexpected programming/runtime errors are not converted into successful outcomes.
- **Consistency boundary:** the state store synchronizes its idempotency check and ledger append in this single process. This example does not persist data across restarts, atomically coordinate with external systems, or guarantee distributed exactly-once processing.
- **Observability and data handling:** Java logging reports outcomes and retry events without logging account IDs, amounts, or currency. `ProcessingSummary` counts final input-record statuses; duplicates and invalid requests are included as separate records.
- **AI-assisted development:** AI assistance may be used for scaffolding and test ideas; review generated changes, run the tests, and be prepared to explain design choices and limitations.
- **Production hardening:** replace in-memory source/store with durable adapters; persist status, deduplication key, and ledger effect atomically; use queue acknowledgement/redelivery semantics and concurrency control; define sequence-gap timeout and recovery procedures; add structured logs, metrics, access controls, and operational dashboards. External side effects require an outbox or equivalent durable handoff rather than relying on in-memory exactly-once claims.

## Tests

JUnit 5 tests cover the exact six suggested assessment records, duplicate detection and conflicting identifier reuse, scale-insensitive amount comparison, out-of-order sequences and updated missing-sequence reporting, invalid-record isolation and required-field validation, transient retries, retry exhaustion and recovery, and retry configuration validation.
