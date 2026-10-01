package com.ledgerline.payments.domain;

/** The Idempotency-Key was already used for a different request body. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
