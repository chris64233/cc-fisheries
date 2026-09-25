package com.chris64233.cc.fisheries.quota;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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

    public record CreateAccountRequest(
            @NotBlank String season,
            @NotBlank String species,
            @NotBlank String holder,
            @NotNull BigDecimal initialQuantity) {
    }

    public record AccountResponse(Long id, String season, String species, String holder,
                                  BigDecimal available, BigDecimal frozen, BigDecimal consumed) {
        static AccountResponse from(QuotaAccount account) {
            return new AccountResponse(account.getId(), account.getSeason(), account.getSpecies(),
                    account.getHolder(), account.getAvailable(), account.getFrozen(), account.getConsumed());
        }
    }

    public record LedgerEventResponse(Long id, Long accountId, String type, BigDecimal quantity,
                                      BigDecimal availableAfter, BigDecimal frozenAfter,
                                      BigDecimal consumedAfter, String reference, String occurredAt) {
        static LedgerEventResponse from(LedgerEvent event) {
            return new LedgerEventResponse(event.getId(), event.getAccountId(), event.getType().name(),
                    event.getQuantity(), event.getAvailableAfter(), event.getFrozenAfter(),
                    event.getConsumedAfter(), event.getReference(), event.getOccurredAt().toString());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(accountService.createAccount(
                request.season(), request.species(), request.holder(), request.initialQuantity()));
    }

    @GetMapping
    public List<AccountResponse> list(@RequestParam(required = false) String season,
                                      @RequestParam(required = false) String species) {
        return accountService.listAccounts(season, species).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return AccountResponse.from(accountService.getAccount(id));
    }

    @GetMapping("/{id}/ledger")
    public List<LedgerEventResponse> ledger(@PathVariable Long id) {
        return accountService.getLedger(id).stream().map(LedgerEventResponse::from).toList();
    }
}
