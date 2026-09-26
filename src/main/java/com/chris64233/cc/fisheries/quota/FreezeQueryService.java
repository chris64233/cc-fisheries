package com.chris64233.cc.fisheries.quota;

import java.util.ArrayList;
import java.util.List;

import com.chris64233.cc.fisheries.landing.LandingRecordRepository;
import com.chris64233.cc.fisheries.landing.LandingStatus;
import com.chris64233.cc.fisheries.transfer.TransferRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户冻结明细查询：汇总转让冻结与待复核卸港冻结。
 * 明细数量之和应等于账户的冻结余额，可用于三量核对。
 */
@Service
public class FreezeQueryService {

    private final QuotaAccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LandingRecordRepository landingRepository;

    public FreezeQueryService(QuotaAccountRepository accountRepository,
                              TransferRepository transferRepository,
                              LandingRecordRepository landingRepository) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.landingRepository = landingRepository;
    }

    @Transactional(readOnly = true)
    public List<FreezeDetail> listFreezes(Long accountId) {
        QuotaAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> com.chris64233.cc.fisheries.common.BusinessException
                        .notFound("配额账户不存在: " + accountId));
        List<FreezeDetail> details = new ArrayList<>();
        transferRepository.findPendingFrom(account.getSeason(), account.getSpecies(), account.getHolder())
                .forEach(t -> details.add(new FreezeDetail("TRANSFER", t.getId().toString(),
                        t.getQuantity(), "to:" + t.getToHolder())));
        landingRepository
                .findBySeasonAndSpeciesAndHolderAndStatus(account.getSeason(), account.getSpecies(),
                        account.getHolder(), LandingStatus.PENDING_REVIEW)
                .forEach(l -> details.add(new FreezeDetail("LANDING", l.getEventId(),
                        l.getWeight(), "vessel:" + l.getVessel())));
        return details;
    }
}
