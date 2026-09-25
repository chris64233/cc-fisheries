package com.chris64233.cc.fisheries.landing;

import com.chris64233.cc.fisheries.common.ApiException;
import com.chris64233.cc.fisheries.common.Quantities;
import com.chris64233.cc.fisheries.quota.LedgerEntry;
import com.chris64233.cc.fisheries.quota.LedgerEntryRepository;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;
import com.chris64233.cc.fisheries.quota.QuotaAccountService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LandingService {

    private final LandingRecordRepository landingRepository;
    private final QuotaAccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;

    public LandingService(LandingRecordRepository landingRepository,
                          QuotaAccountRepository accountRepository,
                          LedgerEntryRepository ledgerRepository) {
        this.landingRepository = landingRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    public record LandingResult(LandingRecord record, boolean replayed) {
    }

    @Transactional
    public LandingResult declare(String eventId, String vessel, String holder, String species,
                                 String season, BigDecimal weight) {
        Quantities.requireText(eventId, "事件号");
        Quantities.requireText(vessel, "渔船");
        Quantities.requireText(holder, "权利人");
        Quantities.requireText(species, "物种");
        Quantities.requireText(season, "捕捞季");
        BigDecimal amount = Quantities.requirePositive(weight, "卸港重量");

        Optional<LandingResult> replay = findReplay(eventId, vessel, holder, species, season, amount);
        if (replay.isPresent()) {
            return replay.get();
        }

        QuotaAccount account = accountRepository.findForUpdate(season, species, holder)
                .orElseThrow(() -> ApiException.notFound("权利人在该捕捞季、物种下没有配额账户"));

        // 拿到账户行锁后再次检查，覆盖并发重放场景
        replay = findReplay(eventId, vessel, holder, species, season, amount);
        if (replay.isPresent()) {
            return replay.get();
        }

        account.consume(amount);
        LandingRecord record = landingRepository.save(
                new LandingRecord(eventId, vessel, holder, species, season, amount));
        ledgerRepository.save(new LedgerEntry(account, LedgerEntry.Type.LANDING_CONSUME, amount, "LANDING", record.getId()));
        return new LandingResult(record, false);
    }

    private Optional<LandingResult> findReplay(String eventId, String vessel, String holder,
                                               String species, String season, BigDecimal weight) {
        return landingRepository.findByEventId(eventId).map(existing -> {
            if (!existing.sameContent(vessel, holder, species, season, weight)) {
                throw ApiException.conflict("事件号已存在且申报内容不一致");
            }
            return new LandingResult(existing, true);
        });
    }

    @Transactional(readOnly = true)
    public LandingRecord getByEventId(String eventId) {
        return landingRepository.findByEventId(eventId)
                .orElseThrow(() -> ApiException.notFound("卸港记录不存在: " + eventId));
    }

    @Transactional(readOnly = true)
    public List<LandingRecord> listByHolder(String holder) {
        return landingRepository.findByHolderOrderByIdAsc(holder);
    }
}
