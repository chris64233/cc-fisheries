package com.chris64233.cc.fisheries.quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
@RequestMapping("/api/quota-accounts")
public class QuotaAccountController {

    private final QuotaAccountService accountService;

    public QuotaAccountController(QuotaAccountService accountService) {
        this.accountService = accountService;
    }

    public record CreateAccountRequest(@NotBlank String season,
                                       @NotBlank String species,
                                       @NotBlank String holder,
                                       @NotNull BigDecimal approvedQuantity) {
    }

    public record AccountResponse(Long id, String season, String species, String holder,
                                  BigDecimal available, BigDecimal transferFrozen, BigDecimal consumed) {
        static AccountResponse of(QuotaAccount account) {
            return new AccountResponse(account.getId(), account.getSeason(), account.getSpecies(),
                    account.getHolder(), account.getAvailable(), account.getTransferFrozen(), account.getConsumed());
        }
    }

    public record LedgerResponse(Long id, Long accountId, LedgerEntry.Type type, BigDecimal amount,
                                 BigDecimal availableAfter, BigDecimal transferFrozenAfter,
                                 BigDecimal consumedAfter, String referenceType, Long referenceId,
                                 Instant createdAt) {
        static LedgerResponse of(LedgerEntry entry) {
            return new LedgerResponse(entry.getId(), entry.getAccountId(), entry.getType(), entry.getAmount(),
                    entry.getAvailableAfter(), entry.getTransferFrozenAfter(), entry.getConsumedAfter(),
                    entry.getReferenceType(), entry.getReferenceId(), entry.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@RequestBody @Valid CreateAccountRequest request) {
        return AccountResponse.of(accountService.createAccount(
                request.season(), request.species(), request.holder(), request.approvedQuantity()));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return AccountResponse.of(accountService.getAccount(id));
    }

    @GetMapping("/lookup")
    public AccountResponse lookup(@RequestParam String season,
                                  @RequestParam String species,
                                  @RequestParam String holder) {
        return AccountResponse.of(accountService.getByCombo(season, species, holder));
    }

    @GetMapping("/{id}/ledger")
    public List<LedgerResponse> ledger(@PathVariable Long id) {
        return accountService.getLedger(id).stream().map(LedgerResponse::of).toList();
    }
}
