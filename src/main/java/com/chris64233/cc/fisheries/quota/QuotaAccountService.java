package com.chris64233.cc.fisheries.quota;

import com.chris64233.cc.fisheries.common.ApiException;
import com.chris64233.cc.fisheries.common.Quantities;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaAccountService {

    private final QuotaAccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;

    public QuotaAccountService(QuotaAccountRepository accountRepository,
                               LedgerEntryRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional
    public QuotaAccount createAccount(String season, String species, String holder, BigDecimal approvedQuantity) {
        Quantities.requireText(season, "捕捞季");
        Quantities.requireText(species, "物种");
        Quantities.requireText(holder, "权利人");
        BigDecimal quantity = Quantities.requirePositive(approvedQuantity, "核准数量");
        accountRepository.findBySeasonAndSpeciesAndHolder(season, species, holder)
                .ifPresent(existing -> {
                    throw ApiException.conflict("该捕捞季、物种、权利人的配额账户已存在");
                });
        QuotaAccount account = new QuotaAccount(season, species, holder);
        account.credit(quantity);
        account = accountRepository.save(account);
        ledgerRepository.save(new LedgerEntry(account, LedgerEntry.Type.APPROVE, quantity, "ACCOUNT", account.getId()));
        return account;
    }

    @Transactional(readOnly = true)
    public QuotaAccount getAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("配额账户不存在: " + id));
    }

    @Transactional(readOnly = true)
    public QuotaAccount getByCombo(String season, String species, String holder) {
        return accountRepository.findBySeasonAndSpeciesAndHolder(season, species, holder)
                .orElseThrow(() -> ApiException.notFound("配额账户不存在"));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> getLedger(Long accountId) {
        getAccount(accountId);
        return ledgerRepository.findByAccountIdOrderByIdAsc(accountId);
    }

}
