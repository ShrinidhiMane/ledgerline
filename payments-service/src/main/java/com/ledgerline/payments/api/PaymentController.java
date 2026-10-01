package com.ledgerline.payments.api;

import com.ledgerline.payments.domain.PaymentService;
import com.ledgerline.payments.domain.PaymentStatus;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * Returns 202 Accepted for a new payment (it completes asynchronously through the ledger)
     * and 200 OK with the original payment for a replayed Idempotency-Key.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody CreatePaymentRequest req) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-100 characters");
        }
        if (req.payerAccountId().equals(req.payeeAccountId())) {
            throw new IllegalArgumentException("payer and payee must be different accounts");
        }
        var result = payments.create(idempotencyKey, req.payerAccountId(), req.payeeAccountId(),
                req.amountMinor(), req.currency());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(PaymentResponse.from(result.payment()));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(payments.get(id));
    }

    @GetMapping("/stats")
    public Map<PaymentStatus, Long> stats() {
        return payments.statusCounts();
    }
}
