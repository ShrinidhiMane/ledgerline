package com.ledgerline.ledger.api;

import com.ledgerline.ledger.domain.Account;
import com.ledgerline.ledger.domain.AccountService;
import com.ledgerline.ledger.domain.Posting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    public record OpenAccountRequest(
            @NotBlank @Size(max = 200) String ownerName,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @PositiveOrZero long openingBalanceMinor) {}

    public record AccountResponse(UUID id, String ownerName, String currency, long balanceMinor,
                                  boolean systemAccount, Instant createdAt) {
        static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getOwnerName(), a.getCurrency(), a.getBalanceMinor(),
                    a.isSystemAccount(), a.getCreatedAt());
        }
    }

    public record PostingResponse(long id, UUID journalEntryId, long amountMinor, String currency, Instant createdAt) {
        static PostingResponse from(Posting p) {
            return new PostingResponse(p.getId(), p.getJournalEntryId(), p.getAmountMinor(), p.getCurrency(), p.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@Valid @RequestBody OpenAccountRequest req) {
        return AccountResponse.from(accounts.open(req.ownerName(), req.currency(), req.openingBalanceMinor()));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accounts.get(id));
    }

    @GetMapping("/{id}/postings")
    public List<PostingResponse> postings(@PathVariable UUID id,
                                          @RequestParam(defaultValue = "50") int limit) {
        return accounts.recentPostings(id, Math.min(Math.max(limit, 1), 500)).stream()
                .map(PostingResponse::from).toList();
    }
}
