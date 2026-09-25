package com.chris64233.cc.fisheries.landing;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.common.Quantities;
import com.chris64233.cc.fisheries.quota.LedgerEvent;
import com.chris64233.cc.fisheries.quota.LedgerEventRepository;
import com.chris64233.cc.fisheries.quota.LedgerEventType;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 卸港申报：只扣减权利人可用配额。事件号唯一，相同内容重放幂等返回，
 * 内容不同返回 409 冲突；并发下同事件号由数据库唯一约束兜底。
 */
@Service
public class LandingService {

    private final LandingRecordRepository landingRepository;
    private final QuotaAccountRepository accountRepository;
    private final LedgerEventRepository ledgerRepository;
    private final TransactionTemplate transactionTemplate;

    public LandingService(LandingRecordRepository landingRepository,
                          QuotaAccountRepository accountRepository,
                          LedgerEventRepository ledgerRepository,
                          TransactionTemplate transactionTemplate) {
        this.landingRepository = landingRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public LandingRecord declare(String eventId, String vessel, String holder, String species,
                                 String season, BigDecimal weight) {
        BigDecimal amount = Quantities.requirePositive(weight, "卸港重量");
        try {
            return transactionTemplate.execute(status -> doDeclare(eventId, vessel, holder, species, season, amount));
        } catch (DataIntegrityViolationException e) {
            // 并发下两个相同事件号同时插入：唯一约束拦截，本事务已回滚，按已存在记录处理
            return resolveExisting(eventId, vessel, holder, species, season, amount);
        }
    }

    private LandingRecord doDeclare(String eventId, String vessel, String holder, String species,
                                    String season, BigDecimal weight) {
        var existing = landingRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            return verifyReplay(existing.get(), vessel, holder, species, season, weight);
        }
        QuotaAccount account = accountRepository.findForUpdate(season, species, holder)
                .orElseThrow(() -> BusinessException.notFound(
                        "配额账户不存在: " + season + "/" + species + "/" + holder));
        if (account.getAvailable().compareTo(weight) < 0) {
            throw BusinessException.unprocessable("可用配额不足，无法核销 " + weight);
        }
        account.consume(weight);
        if (account.hasNegativeBalance()) {
            throw BusinessException.unprocessable("操作会导致账户余额为负: " + holder);
        }
        LandingRecord record = landingRepository.save(
                new LandingRecord(eventId, vessel, holder, species, season, weight));
        ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_DEDUCT, weight, eventId));
        return record;
    }

    private LandingRecord resolveExisting(String eventId, String vessel, String holder, String species,
                                          String season, BigDecimal weight) {
        // 触发唯一约束时，获胜事务可能尚未提交，短暂重试等待其可见
        for (int attempt = 0; attempt < 20; attempt++) {
            var record = landingRepository.findByEventId(eventId);
            if (record.isPresent()) {
                return verifyReplay(record.get(), vessel, holder, species, season, weight);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw BusinessException.conflict("卸港事件处理冲突，请重试: " + eventId);
    }

    private LandingRecord verifyReplay(LandingRecord record, String vessel, String holder, String species,
                                       String season, BigDecimal weight) {
        if (!record.matches(vessel, holder, species, season, weight)) {
            throw BusinessException.conflict("卸港事件号已存在且内容不一致: " + record.getEventId());
        }
        return record;
    }

    public LandingRecord getByEventId(String eventId) {
        return landingRepository.findByEventId(eventId)
                .orElseThrow(() -> BusinessException.notFound("卸港记录不存在: " + eventId));
    }

    public List<LandingRecord> list(String season, String species, String holder) {
        if (season != null && species != null && holder != null) {
            return landingRepository.findBySeasonAndSpeciesAndHolder(season, species, holder);
        }
        return landingRepository.findAll();
    }
}
