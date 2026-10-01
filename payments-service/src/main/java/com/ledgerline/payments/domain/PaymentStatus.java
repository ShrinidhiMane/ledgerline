package com.ledgerline.payments.domain;

public enum PaymentStatus {
    /** Accepted by the API; waiting for the ledger to post it. */
    PENDING,
    /** The ledger posted the double-entry transfer. */
    COMPLETED,
    /** The ledger rejected it (e.g. insufficient funds, unknown account). */
    FAILED
}
