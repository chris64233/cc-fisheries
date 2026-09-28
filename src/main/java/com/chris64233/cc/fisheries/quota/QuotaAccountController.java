package com.chris64233.cc.fisheries.quota;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.hold.QuotaHold;
import com.chris64233.cc.fisheries.transfer.TransferService;

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
    private final TransferService transferService;

    public QuotaAccountController(QuotaAccountService accountService, TransferService transferService) {
        this.accountService = accountService;
        this.transferService = transferService;
    }

    public record CreateAccountRequest(
            @NotBlank String season,
            @NotBlank String species,
            @NotBlank String holder,
            @NotNull BigDecimal initialQuantity) {
    }

    public record AccountResponse(Long id, String season, String species, String holder,
                                  BigDecimal available, BigDecimal frozen, BigDecimal consumed,
                                  long version) {
        static AccountResponse from(QuotaAccount account) {
            return new AccountResponse(account.getId(), account.getSeason(), account.getSpecies(),
                    account.getHolder(), account.getAvailable(), account.getFrozen(), account.getConsumed(),
                    account.getVersion());
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

    public record HoldResponse(Long id, Long accountId, String holdType, String referenceId,
                               BigDecimal quantity, String status, String createdAt, String resolvedAt) {
        static HoldResponse from(QuotaHold hold) {
            return new HoldResponse(hold.getId(), hold.getAccountId(), hold.getHoldType().name(),
                    hold.getReferenceId(), hold.getQuantity(), hold.getStatus().name(),
                    hold.getCreatedAt().toString(),
                    hold.getResolvedAt() == null ? null : hold.getResolvedAt().toString());
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
    public List<LedgerEventResponse> ledger(@PathVariable Long id,
                                            @RequestParam(required = false) String reference) {
        List<LedgerEvent> events = reference == null
                ? accountService.getLedger(id)
                : accountService.getLedgerByReference(id, reference);
        return events.stream().map(LedgerEventResponse::from).toList();
    }

    @GetMapping("/{id}/holds")
    public List<HoldResponse> holds(@PathVariable Long id,
                                    @RequestParam(required = false) Boolean activeOnly) {
        return accountService.getHolds(id, activeOnly).stream().map(HoldResponse::from).toList();
    }

    /**
     * 可转余额分解：区分可转余额（available/transferable）、已上岸（consumed/landed）
     * 与被其他申请暂时占用的冻结量（转让 / 卸港待复核 / 更正待复核）。
     * 查看某笔在途转让时传 {@code excludeTransferId}，把该笔自身冻结算回可转量。
     */
    @GetMapping("/transfer-availability")
    public TransferService.AvailabilityView transferAvailability(
            @RequestParam String season,
            @RequestParam String species,
            @RequestParam String holder,
            @RequestParam(required = false) Long excludeTransferId) {
        return transferService.getAvailability(season, species, holder, excludeTransferId);
    }
}
