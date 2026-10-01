package com.ledgerline.payments.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Fingerprints a payment request so a reused Idempotency-Key can be checked: same key + same
 * body is a safe retry; same key + different body is a client bug and must be rejected.
 */
public final class RequestHasher {

    private RequestHasher() {}

    public static String hash(UUID payer, UUID payee, long amountMinor, String currency) {
        String canonical = payer + "|" + payee + "|" + amountMinor + "|" + currency;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
