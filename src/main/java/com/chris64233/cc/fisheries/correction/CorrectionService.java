package com.chris64233.cc.fisheries.correction;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.common.IdempotentDecision;
import com.chris64233.cc.fisheries.common.IdempotentDecisionRepository;
import com.chris64233.cc.fisheries.common.Quantities;
import com.chris64233.cc.fisheries.hold.HoldType;
import com.chris64233.cc.fisheries.hold.QuotaHold;
import com.chris64233.cc.fisheries.hold.QuotaHoldRepository;
import com.chris64233.cc.fisheries.landing.LandingRecord;
import com.chris64233.cc.fisheries.landing.LandingRecordRepository;
import com.chris64233.cc.fisheries.landing.LandingService;
import com.chris64233.cc.fisheries.quota.LedgerEvent;
import com.chris64233.cc.fisheries.quota.LedgerEventRepository;
import com.chris64233.cc.fisheries.quota.LedgerEventType;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已核销申报的称重更正。
 *
 * <ul>
 *   <li>更正引用一条已确认（已核销）申报，原台账不可覆盖，只追加更正台账；</li>
 *   <li>增重：创建时再次取得足够配额（冻结），确认时冻结转核销；可用不足直接拒绝；</li>
 *   <li>减重：创建时不碰余额，确认时只把实际差额从已核销归还可用；</li>
 *   <li>创建时拍下申报版本与账户版本，确认时任一版本变化即拒绝旧决定；</li>
 *   <li>整个差额处理在单一事务内完成，失败整体回滚，不写入部分差额；</li>
 *   <li>更正号与复核事件号幂等，重复处理不会重复核销或归还。</li>
 * </ul>
 */
@Service
public class CorrectionService {

    static final String REVIEW_KEY_PREFIX = "CORRECTION-REVIEW:";

    private final CorrectionRepository correctionRepository;
    private final LandingRecordRepository landingRepository;
    private final QuotaAccountRepository accountRepository;
    private final QuotaHoldRepository holdRepository;
    private final LedgerEventRepository ledgerRepository;
    private final IdempotentDecisionRepository decisionRepository;
    private final TransactionTemplate transactionTemplate;

    public CorrectionService(CorrectionRepository correctionRepository,
                             LandingRecordRepository landingRepository,
                             QuotaAccountRepository accountRepository,
                             QuotaHoldRepository holdRepository,
                             LedgerEventRepository ledgerRepository,
                             IdempotentDecisionRepository decisionRepository,
                             TransactionTemplate transactionTemplate) {
        this.correctionRepository = correctionRepository;
        this.landingRepository = landingRepository;
        this.accountRepository = accountRepository;
        this.holdRepository = holdRepository;
        this.ledgerRepository = ledgerRepository;
        this.decisionRepository = decisionRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 创建更正。correctionId 幂等；增重需要从可用量冻结差额，减重不碰余额。
     */
    public Correction create(String correctionId, String originalEventId, BigDecimal newWeight) {
        Quantities.requireText(correctionId, "更正号");
        Quantities.requireText(originalEventId, "原申报事件号");
        BigDecimal target = Quantities.requirePositive(newWeight, "更正重量");
        try {
            return transactionTemplate.execute(status -> doCreate(correctionId, originalEventId, target));
        } catch (DataIntegrityViolationException e) {
            return resolveExistingCorrection(correctionId, originalEventId, target);
        }
    }

    private Correction doCreate(String correctionId, String originalEventId, BigDecimal newWeight) {
        var existing = correctionRepository.findByCorrectionId(correctionId);
        if (existing.isPresent()) {
            return verifyReplay(existing.get(), originalEventId, newWeight);
        }
        // 先锁账户再锁申报行，保证读取的 confirmedWeight/version 与余额一致
        LandingRecord original = landingRepository.findByEventIdForUpdate(originalEventId)
                .orElseThrow(() -> BusinessException.notFound("原卸港记录不存在: " + originalEventId));
        if (!original.isConfirmed()) {
            throw BusinessException.unprocessable("只能更正已核销（已确认）的申报: " + originalEventId
                    + "，当前状态 " + original.getStatus());
        }
        QuotaAccount account = accountRepository.findForUpdate(
                original.getSeason(), original.getSpecies(), original.getHolder())
                .orElseThrow(() -> BusinessException.notFound(
                        "配额账户不存在: " + original.getSeason() + "/" + original.getSpecies()
                                + "/" + original.getHolder()));

        BigDecimal base = original.getConfirmedWeight();
        int cmp = newWeight.compareTo(base);
        if (cmp == 0) {
            throw BusinessException.unprocessable("更正重量与已确认重量一致，无需更正: " + base);
        }
        CorrectionDirection direction = cmp > 0 ? CorrectionDirection.INCREASE : CorrectionDirection.DECREASE;
        BigDecimal delta = newWeight.subtract(base).abs();

        if (direction == CorrectionDirection.INCREASE) {
            if (account.getAvailable().compareTo(delta) < 0) {
                throw BusinessException.unprocessable("可用配额不足，无法为增重冻结 " + delta);
            }
            account.freeze(delta);
            LandingService.assertNotNegative(account);
        }

        // 先把本事务内的冻结落库，使 @Version 递增回写托管实体，
        // 这样拍下的账户版本就是确认决策应当比对的版本。
        accountRepository.flush();

        Correction correction = correctionRepository.save(new Correction(
                correctionId, originalEventId, original.getSeason(), original.getSpecies(),
                original.getHolder(), direction, newWeight, delta,
                original.getVersion(), account.getVersion(), base));
        if (direction == CorrectionDirection.INCREASE) {
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.CORRECTION_FREEZE, delta, correctionId));
            holdRepository.save(new QuotaHold(account, HoldType.CORRECTION, correctionId, delta));
        }
        return correction;
    }

    private Correction resolveExistingCorrection(String correctionId, String originalEventId, BigDecimal newWeight) {
        for (int attempt = 0; attempt < 20; attempt++) {
            var correction = correctionRepository.findByCorrectionId(correctionId);
            if (correction.isPresent()) {
                return verifyReplay(correction.get(), originalEventId, newWeight);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw BusinessException.conflict("更正处理冲突，请重试: " + correctionId);
    }

    private Correction verifyReplay(Correction correction, String originalEventId, BigDecimal newWeight) {
        if (!correction.getOriginalEventId().equals(originalEventId)
                || correction.getCorrectedWeight().compareTo(newWeight) != 0) {
            throw BusinessException.conflict("更正号已存在且内容不一致: " + correction.getCorrectionId());
        }
        return correction;
    }

    /**
     * 更正确认。增重把冻结转为核销，减重归还实际差额。
     * 原申报或账户版本自创建后发生变化则拒绝旧决定；失败不写入任何差额。
     */
    public Correction confirm(String correctionId, String reviewEventId, String reviewer) {
        return resolve(correctionId, reviewEventId, reviewer, true);
    }

    /**
     * 更正驳回。增重释放已冻结配额，减重仅终结；重复驳回幂等。
     */
    public Correction reject(String correctionId, String reviewEventId, String reviewer) {
        return resolve(correctionId, reviewEventId, reviewer, false);
    }

    private Correction resolve(String correctionId, String reviewEventId, String reviewer, boolean confirm) {
        Quantities.requireText(reviewEventId, "复核事件号");
        Quantities.requireText(reviewer, "复核人");
        try {
            return transactionTemplate.execute(status -> doResolve(correctionId, reviewEventId, confirm));
        } catch (DataIntegrityViolationException e) {
            // 并发下两个相同复核事件号同时登记：唯一约束拦截，本事务回滚后按幂等重放处理
            return replayReviewAfterKeyClash(correctionId, reviewEventId);
        }
    }

    private Correction replayReviewAfterKeyClash(String correctionId, String reviewEventId) {
        return transactionTemplate.execute(status -> {
            var decision = decisionRepository.findByIdempotencyKey(REVIEW_KEY_PREFIX + reviewEventId);
            if (decision.isPresent() && decision.get().getTargetRef().equals(correctionId)) {
                return correctionRepository.findByCorrectionId(correctionId)
                        .orElseThrow(() -> BusinessException.notFound("更正不存在: " + correctionId));
            }
            throw BusinessException.conflict("复核事件号已用于其他更正: " + reviewEventId);
        });
    }

    private Correction doResolve(String correctionId, String reviewEventId, boolean confirm) {
        Correction correction = correctionRepository.findByCorrectionIdForUpdate(correctionId)
                .orElseThrow(() -> BusinessException.notFound("更正不存在: " + correctionId));

        String key = REVIEW_KEY_PREFIX + reviewEventId;
        var prior = decisionRepository.findByIdempotencyKey(key);
        if (prior.isPresent()) {
            if (!prior.get().getTargetRef().equals(correctionId)) {
                throw BusinessException.conflict("复核事件号已用于其他更正: " + reviewEventId);
            }
            // 幂等重放：不再核销或归还
            return correction;
        }
        if (!correction.isPending()) {
            throw BusinessException.conflict("更正已终结，当前状态: " + correction.getStatus());
        }

        LandingRecord original = landingRepository.findByEventIdForUpdate(correction.getOriginalEventId())
                .orElseThrow(() -> BusinessException.notFound(
                        "原卸港记录不存在: " + correction.getOriginalEventId()));
        QuotaAccount account = accountRepository.findForUpdate(
                correction.getSeason(), correction.getSpecies(), correction.getHolder())
                .orElseThrow(() -> BusinessException.notFound(
                        "配额账户不存在: " + correction.getSeason() + "/" + correction.getSpecies()
                                + "/" + correction.getHolder()));

        if (confirm) {
            // 乐观版本校验：决定依据的申报或账户版本变化即拒绝旧决定
            if (original.getVersion() != correction.getLandingVersionAtCreate()) {
                throw BusinessException.conflict(
                        "原申报版本已变化，更正依据过期，请基于最新重量重新发起: " + correctionId);
            }
            if (account.getVersion() != correction.getAccountVersionAtCreate()) {
                throw BusinessException.conflict(
                        "配额账户版本已变化，更正依据过期，请重新发起: " + correctionId);
            }
            if (original.getConfirmedWeight().compareTo(correction.getOriginalConfirmedWeight()) != 0) {
                throw BusinessException.conflict(
                        "原申报已确认重量已变化，更正依据过期: " + correctionId);
            }

            BigDecimal delta = correction.getDelta();
            if (correction.getDirection() == CorrectionDirection.INCREASE) {
                QuotaHold hold = holdRepository.findByHoldTypeAndReferenceId(HoldType.CORRECTION, correctionId)
                        .orElseThrow(() -> BusinessException.notFound("增重冻结明细不存在: " + correctionId));
                if (!hold.isHeld()) {
                    throw BusinessException.conflict("更正冻结已处理: " + correctionId);
                }
                account.settleFrozenToConsumed(delta);
                hold.markSettled();
                ledgerRepository.save(new LedgerEvent(account, LedgerEventType.CORRECTION_CONSUME, delta,
                        correctionId));
            } else {
                account.refundConsumed(delta);
                ledgerRepository.save(new LedgerEvent(account, LedgerEventType.CORRECTION_REFUND, delta,
                        correctionId));
            }
            LandingService.assertNotNegative(account);
            original.applyCorrection(correction.getCorrectedWeight());
            correction.markConfirmed();
        } else {
            // 驳回：增重释放冻结（frozen→available，不可能为负），减重无需动账
            if (correction.getDirection() == CorrectionDirection.INCREASE) {
                QuotaHold hold = holdRepository.findByHoldTypeAndReferenceId(HoldType.CORRECTION, correctionId)
                        .orElseThrow(() -> BusinessException.notFound("增重冻结明细不存在: " + correctionId));
                if (hold.isHeld()) {
                    account.releaseFrozen(correction.getDelta());
                    LandingService.assertNotNegative(account);
                    hold.markReleased();
                    ledgerRepository.save(new LedgerEvent(account, LedgerEventType.CORRECTION_RELEASE,
                            correction.getDelta(), correctionId));
                }
            }
            correction.markRejected();
        }
        decisionRepository.save(new IdempotentDecision(key, correction.getStatus().name(), correctionId));
        return correction;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Correction getByCorrectionId(String correctionId) {
        return correctionRepository.findByCorrectionId(correctionId)
                .orElseThrow(() -> BusinessException.notFound("更正不存在: " + correctionId));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Correction> listByOriginal(String originalEventId) {
        return correctionRepository.findByOriginalEventIdOrderByIdAsc(originalEventId);
    }
}
