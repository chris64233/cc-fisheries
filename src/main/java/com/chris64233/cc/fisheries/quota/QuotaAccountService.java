package com.chris64233.cc.fisheries.quota;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.common.Quantities;
import com.chris64233.cc.fisheries.hold.HoldStatus;
import com.chris64233.cc.fisheries.hold.QuotaHold;
import com.chris64233.cc.fisheries.hold.QuotaHoldRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaAccountService {

    private final QuotaAccountRepository accountRepository;
    private final LedgerEventRepository ledgerRepository;
    private final QuotaHoldRepository holdRepository;

    public QuotaAccountService(QuotaAccountRepository accountRepository,
                               LedgerEventRepository ledgerRepository,
                               QuotaHoldRepository holdRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.holdRepository = holdRepository;
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

    /**
     * 账户差额台账按关联单号过滤（转让 ID、卸港事件号或更正号）。
     */
    @Transactional(readOnly = true)
    public List<LedgerEvent> getLedgerByReference(Long accountId, String reference) {
        getAccount(accountId);
        return ledgerRepository.findByAccountIdAndReferenceOrderByIdAsc(accountId, reference);
    }

    /**
     * 账户冻结明细：默认全部（持有中/已核销/已释放），可只看持有中。
     * 所有 HELD 明细数量之和应等于账户 frozen 余额。
     */
    @Transactional(readOnly = true)
    public List<QuotaHold> getHolds(Long accountId, Boolean activeOnly) {
        getAccount(accountId);
        if (Boolean.TRUE.equals(activeOnly)) {
            return holdRepository.findByAccountIdAndStatusOrderByIdAsc(accountId, HoldStatus.HELD);
        }
        return holdRepository.findByAccountIdOrderByIdAsc(accountId);
    }
}
