package com.chris64233.cc.fisheries.landing;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.common.IdempotentDecision;
import com.chris64233.cc.fisheries.common.IdempotentDecisionRepository;
import com.chris64233.cc.fisheries.common.Quantities;
import com.chris64233.cc.fisheries.correction.Correction;
import com.chris64233.cc.fisheries.correction.CorrectionRepository;
import com.chris64233.cc.fisheries.hold.HoldType;
import com.chris64233.cc.fisheries.hold.QuotaHold;
import com.chris64233.cc.fisheries.hold.QuotaHoldRepository;
import com.chris64233.cc.fisheries.quota.LedgerEvent;
import com.chris64233.cc.fisheries.quota.LedgerEventRepository;
import com.chris64233.cc.fisheries.quota.LedgerEventType;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 卸港申报：申报先冻结权利人可用配额并进入待复核，港口复核人确认重量后
 * 冻结量才正式核销（frozen → consumed），驳回则释放回可用（frozen → available）。
 *
 * <p>三个余额（可用、冻结、已核销）在每一步守恒：冻结与释放只在两个余额间等量平移，
 * 核销把冻结量转为已核销。申报事件号与复核事件号均幂等，重复处理不会重复核销或归还。
 */
@Service
public class LandingService {

    static final String REVIEW_KEY_PREFIX = "LANDING-REVIEW:";

    private final LandingRecordRepository landingRepository;
    private final QuotaAccountRepository accountRepository;
    private final LedgerEventRepository ledgerRepository;
    private final QuotaHoldRepository holdRepository;
    private final IdempotentDecisionRepository decisionRepository;
    private final CorrectionRepository correctionRepository;
    private final TransactionTemplate transactionTemplate;

    public LandingService(LandingRecordRepository landingRepository,
                          QuotaAccountRepository accountRepository,
                          LedgerEventRepository ledgerRepository,
                          QuotaHoldRepository holdRepository,
                          IdempotentDecisionRepository decisionRepository,
                          CorrectionRepository correctionRepository,
                          TransactionTemplate transactionTemplate) {
        this.landingRepository = landingRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.holdRepository = holdRepository;
        this.decisionRepository = decisionRepository;
        this.correctionRepository = correctionRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 卸港申报：冻结对应数量的可用配额，记录进入待复核。
     * 相同事件号 + 相同内容重放幂等返回，内容不同返回 409；并发同事件号由唯一约束兜底。
     */
    public LandingRecord declare(String eventId, String vessel, String holder, String species,
                                 String season, BigDecimal weight) {
        BigDecimal amount = Quantities.requirePositive(weight, "卸港重量");
        try {
            return transactionTemplate.execute(
                    status -> doDeclare(eventId, vessel, holder, species, season, amount));
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
            throw BusinessException.unprocessable("可用配额不足，无法冻结 " + weight);
        }
        account.freeze(weight);
        assertNotNegative(account);
        LandingRecord record = landingRepository.save(
                new LandingRecord(eventId, vessel, holder, species, season, weight));
        ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_FREEZE, weight, eventId));
        holdRepository.save(new QuotaHold(account, HoldType.LANDING, eventId, weight));
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

    /**
     * 港口复核确认：冻结量正式核销。复核事件号幂等，重复确认不重复核销。
     */
    public LandingRecord confirm(String eventId, String reviewEventId, String reviewer) {
        return resolveReview(eventId, reviewEventId, reviewer, true);
    }

    /**
     * 港口复核驳回：冻结量释放回可用。复核事件号幂等，重复驳回不重复释放。
     */
    public LandingRecord reject(String eventId, String reviewEventId, String reviewer) {
        return resolveReview(eventId, reviewEventId, reviewer, false);
    }

    private LandingRecord resolveReview(String eventId, String reviewEventId, String reviewer,
                                        boolean confirm) {
        Quantities.requireText(reviewEventId, "复核事件号");
        Quantities.requireText(reviewer, "复核人");
        try {
            return transactionTemplate.execute(status -> doResolveReview(eventId, reviewEventId, reviewer, confirm));
        } catch (DataIntegrityViolationException e) {
            // 并发下两个相同复核事件号同时登记：唯一约束拦截，本事务回滚后按幂等重放处理
            return replayReviewAfterKeyClash(eventId, reviewEventId);
        }
    }

    private LandingRecord replayReviewAfterKeyClash(String eventId, String reviewEventId) {
        return transactionTemplate.execute(status -> {
            var decision = decisionRepository.findByIdempotencyKey(REVIEW_KEY_PREFIX + reviewEventId);
            if (decision.isPresent() && decision.get().getTargetRef().equals(eventId)) {
                return landingRepository.findByEventId(eventId)
                        .orElseThrow(() -> BusinessException.notFound("卸港记录不存在: " + eventId));
            }
            throw BusinessException.conflict("复核事件号已用于其他申报: " + reviewEventId);
        });
    }

    LandingRecord doResolveReview(String eventId, String reviewEventId, String reviewer, boolean confirm) {        LandingRecord record = landingRepository.findByEventIdForUpdate(eventId)
                .orElseThrow(() -> BusinessException.notFound("卸港记录不存在: " + eventId));

        String key = REVIEW_KEY_PREFIX + reviewEventId;
        var priorDecision = decisionRepository.findByIdempotencyKey(key);
        if (priorDecision.isPresent()) {
            // 幂等重放：键必须作用于同一申报，直接返回当前记录，不再变更余额
            if (!priorDecision.get().getTargetRef().equals(eventId)) {
                throw BusinessException.conflict("复核事件号已用于其他申报: " + reviewEventId);
            }
            return record;
        }
        if (!record.isPendingReview()) {
            throw BusinessException.conflict("申报已复核，当前状态: " + record.getStatus());
        }

        QuotaAccount account = accountRepository.findForUpdate(
                record.getSeason(), record.getSpecies(), record.getHolder())
                .orElseThrow(() -> BusinessException.notFound(
                        "配额账户不存在: " + record.getSeason() + "/" + record.getSpecies()
                                + "/" + record.getHolder()));
        QuotaHold hold = holdRepository.findByHoldTypeAndReferenceId(HoldType.LANDING, eventId)
                .orElseThrow(() -> BusinessException.notFound("卸港冻结明细不存在: " + eventId));
        if (!hold.isHeld()) {
            throw BusinessException.conflict("冻结明细已处理: " + eventId);
        }

        BigDecimal amount = record.getWeight();
        if (confirm) {
            account.settleFrozenToConsumed(amount);
            hold.markSettled();
            record.markConfirmed(amount, reviewer);
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_CONSUME, amount, eventId));
        } else {
            account.releaseFrozen(amount);
            hold.markReleased();
            record.markRejected(reviewer);
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_RELEASE, amount, eventId));
        }
        assertNotNegative(account);
        decisionRepository.save(new IdempotentDecision(
                key, record.getStatus().name(), eventId));
        return record;
    }

    public static void assertNotNegative(QuotaAccount account) {
        if (account.hasNegativeBalance()) {
            throw BusinessException.unprocessable("操作会导致账户余额为负: " + account.getHolder());
        }
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

    /**
     * 申报版本链：原始申报 + 按时间顺序的全部更正（含已驳回），以及当前有效重量。
     * 原始台账不被覆盖，重量演进完全由该链表达。
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public VersionChain getVersionChain(String eventId) {
        LandingRecord original = getByEventId(eventId);
        List<ChainEntry> entries = new java.util.ArrayList<>();
        entries.add(new ChainEntry("LANDING", eventId, original.getWeight(),
                original.getStatus().name(), original.getVersion(),
                original.getRecordedAt().toString()));
        for (Correction c : correctionRepository.findByOriginalEventIdOrderByIdAsc(eventId)) {
            entries.add(new ChainEntry("CORRECTION", c.getCorrectionId(), c.getCorrectedWeight(),
                    c.getStatus().name(), c.getVersion(), c.getCreatedAt().toString()));
        }
        BigDecimal effective = original.isConfirmed()
                ? original.getConfirmedWeight() : original.getWeight();
        return new VersionChain(eventId, original.getStatus().name(), original.getVersion(),
                effective, entries);
    }

    /** 版本链中的一个节点：原始申报或一次更正。 */
    public record ChainEntry(String nodeType, String refId, BigDecimal weight, String status,
                             long version, String at) {
    }

    public record VersionChain(String eventId, String status, long version, BigDecimal effectiveWeight,
                               List<ChainEntry> entries) {
    }
}
