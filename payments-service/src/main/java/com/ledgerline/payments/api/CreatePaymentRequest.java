package com.ledgerline.payments.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Amounts are integers in minor units (cents) to avoid floating-point rounding errors.
 */
public record CreatePaymentRequest(
        @NotNull UUID payerAccountId,
        @NotNull UUID payeeAccountId,
        @Positive long amountMinor,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code like USD") String currency) {}
