package com.chris64233.cc.fisheries.quota;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.common.Quantities;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaAccountService {

    private final QuotaAccountRepository accountRepository;
    private final LedgerEventRepository ledgerRepository;

    public QuotaAccountService(QuotaAccountRepository accountRepository,
                               LedgerEventRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * 开立配额账户并核准入账，(捕捞季, 物种, 权利人) 组合唯一。
     */
    @Transactional
    public QuotaAccount createAccount(String season, String species, String holder, BigDecimal initialQuantity) {
        BigDecimal quantity = Quantities.requirePositive(initialQuantity, "核准数量");
        accountRepository.findBySeasonAndSpeciesAndHolder(season, species, holder)
                .ifPresent(existing -> {
                    throw BusinessException.conflict("账户已存在: " + season + "/" + species + "/" + holder);
                });
        QuotaAccount account = new QuotaAccount(season, species, holder);
        account.credit(quantity);
        QuotaAccount saved = accountRepository.save(account);
        ledgerRepository.save(new LedgerEvent(saved, LedgerEventType.GRANT, quantity, null));
        return saved;
    }

    @Transactional(readOnly = true)
    public QuotaAccount getAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("配额账户不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<QuotaAccount> listAccounts(String season, String species) {
        if (season != null && species != null) {
            return accountRepository.findBySeasonAndSpecies(season, species);
        }
        return accountRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<LedgerEvent> getLedger(Long accountId) {
        getAccount(accountId);
        return ledgerRepository.findByAccountIdOrderByIdAsc(accountId);
    }
}
