package com.ledgerline.ledger.api;

import com.ledgerline.ledger.domain.LedgerVerifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerVerifier verifier;

    public LedgerController(LedgerVerifier verifier) {
        this.verifier = verifier;
    }

    /** Proves the books balance. Run it after a load test: it must say consistent=true. */
    @GetMapping("/verify")
    public LedgerVerifier.Report verify() {
        return verifier.verify();
    }
}
