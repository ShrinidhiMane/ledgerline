package com.ledgerline.ledger.messaging;

/** The message could not be parsed. Retrying will not help, so it goes straight to the DLT. */
public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
