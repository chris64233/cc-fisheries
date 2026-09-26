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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 卸港申报与称重更正。
 *
 * <p>申报只冻结权利人可用配额并进入待复核；港口复核确认重量后才正式核销，拒绝则释放冻结。
 * 已核销申报发现称重错误时创建更正，确认时按实际差额追扣或归还配额。
 *
 * <p>冻结、确认、释放与更正差额全部在单事务内、持有申报行锁 + 账户悲观行锁完成，
 * 保证可用/冻结/已核销三量守恒且任何时刻不为负；创建决定时记录的申报版本、账户版本
 * 在执行时已变化则拒绝旧决定。复核事件号与更正号幂等，失败事务回滚不写部分台账。
 */
@Service
public class LandingService {

    private final LandingRecordRepository landingRepository;
    private final LandingReviewRepository reviewRepository;
    private final LandingCorrectionRepository correctionRepository;
    private final QuotaAccountRepository accountRepository;
    private final LedgerEventRepository ledgerRepository;
    private final TransactionTemplate transactionTemplate;

    public LandingService(LandingRecordRepository landingRepository,
                          LandingReviewRepository reviewRepository,
                          LandingCorrectionRepository correctionRepository,
                          QuotaAccountRepository accountRepository,
                          LedgerEventRepository ledgerRepository,
                          TransactionTemplate transactionTemplate) {
        this.landingRepository = landingRepository;
        this.reviewRepository = reviewRepository;
        this.correctionRepository = correctionRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.transactionTemplate = transactionTemplate;
    }

    // ---------------------------------------------------------------------
    // 卸港申报：冻结可用配额，进入待复核
    // ---------------------------------------------------------------------

    public LandingRecord declare(String eventId, String vessel, String holder, String species,
                                 String season, BigDecimal weight) {
        BigDecimal amount = Quantities.requirePositive(weight, "卸港重量");
        try {
            return transactionTemplate.execute(
                    status -> doDeclare(eventId, vessel, holder, species, season, amount));
        } catch (DataIntegrityViolationException e) {
            // 并发下两个相同事件号同时插入：唯一约束拦截，本事务已回滚，按已存在记录处理
            return waitForExisting(
                    () -> landingRepository.findByEventId(eventId)
                            .map(r -> verifyReplay(r, vessel, holder, species, season, amount)),
                    "卸港事件处理冲突，请重试: " + eventId);
        }
    }

    private LandingRecord doDeclare(String eventId, String vessel, String holder, String species,
                                    String season, BigDecimal weight) {
        var existing = landingRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            return verifyReplay(existing.get(), vessel, holder, species, season, weight);
        }
        QuotaAccount account = lockAccount(season, species, holder);
        if (account.getAvailable().compareTo(weight) < 0) {
            throw BusinessException.unprocessable("可用配额不足，无法冻结 " + weight);
        }
        account.freeze(weight);
        assertConsistent(account);
        LandingRecord record = landingRepository.save(
                new LandingRecord(eventId, vessel, holder, species, season, weight));
        ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_FREEZE, weight, eventId));
        return record;
    }

    private LandingRecord verifyReplay(LandingRecord record, String vessel, String holder, String species,
                                       String season, BigDecimal weight) {
        if (!record.matches(vessel, holder, species, season, weight)) {
            throw BusinessException.conflict("卸港事件号已存在且内容不一致: " + record.getEventId());
        }
        return record;
    }

    // ---------------------------------------------------------------------
    // 港口复核：确认（可调整重量）或拒绝
    // ---------------------------------------------------------------------

    /**
     * 复核确认重量。确认重量等于申报重量时直接核销冻结量；小于时核销确认量、释放差额；
     * 大于时先释放全部冻结、再从可用量追扣确认总量，可用不足则拒绝。
     */
    public LandingReview confirmReview(String reviewEventId, String landingEventId, String reviewer,
                                       BigDecimal confirmedWeight) {
        BigDecimal weight = Quantities.requirePositive(confirmedWeight, "确认重量");
        try {
            return transactionTemplate.execute(status ->
                    doReview(reviewEventId, landingEventId, reviewer, LandingStatus.CONFIRMED, weight));
        } catch (DataIntegrityViolationException e) {
            // 复核事件号或“同一申报只能复核一次”的唯一约束在并发下拦截
            return resolveReviewConflict(reviewEventId, landingEventId, LandingStatus.CONFIRMED, weight);
        }
    }

    /** 复核拒绝：冻结量全额释放回可用量。 */
    public LandingReview rejectReview(String reviewEventId, String landingEventId, String reviewer) {
        try {
            return transactionTemplate.execute(status ->
                    doReview(reviewEventId, landingEventId, reviewer, LandingStatus.REJECTED, null));
        } catch (DataIntegrityViolationException e) {
            return resolveReviewConflict(reviewEventId, landingEventId, LandingStatus.REJECTED, null);
        }
    }

    private LandingReview doReview(String reviewEventId, String landingEventId, String reviewer,
                                   LandingStatus decision, BigDecimal confirmedWeight) {
        // 直接加锁读取申报（避免先无锁加载再锁造成版本冲突），锁内做幂等与状态校验：
        // 并发同复核事件号在锁后按幂等重放处理
        LandingRecord locked = landingRepository.findByEventIdForUpdate(landingEventId)
                .orElseThrow(() -> BusinessException.notFound("卸港记录不存在: " + landingEventId));
        var sameEvent = reviewRepository.findByReviewEventId(reviewEventId);
        if (sameEvent.isPresent()) {
            return verifyReviewReplay(sameEvent.get(), landingEventId, decision, confirmedWeight);
        }
        var already = reviewRepository.findByLandingId(locked.getId());
        if (already.isPresent()) {
            throw BusinessException.conflict("申报已复核，复核事件号: " + already.get().getReviewEventId());
        }
        if (!locked.isPendingReview()) {
            throw BusinessException.conflict("申报当前状态不允许复核: " + locked.getStatus());
        }
        QuotaAccount account = lockAccount(locked.getSeason(), locked.getSpecies(), locked.getHolder());
        BigDecimal declared = locked.getWeight();

        if (decision == LandingStatus.REJECTED) {
            account.releaseFrozen(declared);
            assertConsistent(account);
            locked.markRejected();
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_RELEASE,
                    declared, reviewEventId));
        } else if (confirmedWeight.compareTo(declared) == 0) {
            // 冻结量直接转为已核销：frozen -= D, consumed += D
            account.settleFrozenToConsumed(declared);
            assertConsistent(account);
            locked.markConfirmed(declared);
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_SETTLE,
                    declared, reviewEventId));
        } else if (confirmedWeight.compareTo(declared) < 0) {
            // 确认量核销冻结，差额冻结归还可用
            BigDecimal released = declared.subtract(confirmedWeight);
            account.settleFrozenToConsumed(confirmedWeight);
            account.releaseFrozen(released);
            assertConsistent(account);
            locked.markConfirmed(confirmedWeight);
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_SETTLE,
                    confirmedWeight, reviewEventId));
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_RELEASE,
                    released, reviewEventId));
        } else {
            BigDecimal extra = confirmedWeight.subtract(declared);
            if (account.getAvailable().compareTo(extra) < 0) {
                throw BusinessException.unprocessable(
                        "可用配额不足，无法追扣复核增重差额 " + extra);
            }
            // 申报量由冻结核销，增重差额从可用追扣
            account.settleFrozenToConsumed(declared);
            account.consume(extra);
            assertConsistent(account);
            locked.markConfirmed(confirmedWeight);
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.LANDING_SETTLE,
                    declared, reviewEventId));
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.REVIEW_CONSUME,
                    extra, reviewEventId));
        }
        return reviewRepository.save(new LandingReview(reviewEventId, locked.getId(), locked.getHolder(),
                locked.getSpecies(), locked.getSeason(), reviewer, declared, decision, confirmedWeight));
    }

    private LandingReview verifyReviewReplay(LandingReview review, String landingEventId,
                                             LandingStatus decision, BigDecimal confirmedWeight) {
        LandingRecord landing = getByEventId(landingEventId);
        if (!review.getLandingId().equals(landing.getId())) {
            throw BusinessException.conflict("复核事件号已绑定其他申报: " + review.getReviewEventId());
        }
        if (!review.sameDecision(decision, confirmedWeight)) {
            throw BusinessException.conflict("复核事件号已存在且结论不一致: " + review.getReviewEventId());
        }
        return review;
    }

    private LandingReview resolveReviewConflict(String reviewEventId, String landingEventId,
                                                LandingStatus decision, BigDecimal confirmedWeight) {
        var byEvent = reviewRepository.findByReviewEventId(reviewEventId);
        if (byEvent.isPresent()) {
            return verifyReviewReplay(byEvent.get(), landingEventId, decision, confirmedWeight);
        }
        LandingRecord landing = waitForExisting(
                () -> landingRepository.findByEventId(landingEventId)
                        .map(r -> r), "卸港记录不存在: " + landingEventId);
        var byLanding = reviewRepository.findByLandingId(landing.getId());
        if (byLanding.isPresent()) {
            throw BusinessException.conflict("申报已复核，复核事件号: " + byLanding.get().getReviewEventId());
        }
        throw BusinessException.conflict("复核事件处理冲突，请重试: " + reviewEventId);
    }

    // ---------------------------------------------------------------------
    // 称重更正：引用已核销申报，按实际差额追扣/归还
    // ---------------------------------------------------------------------

    /**
     * 创建更正（不发生任何配额变化）。记录所基于的申报版本与账户版本；
     * 允许将重量更正为 0（归还全部已核销），但不允许负数或与当前重量相同。
     */
    public LandingCorrection createCorrection(String correctionNo, String landingEventId,
                                              BigDecimal correctedWeight) {
        BigDecimal newWeight = Quantities.normalize(correctedWeight, "更正重量");
        try {
            return transactionTemplate.execute(status -> doCreateCorrection(correctionNo, landingEventId, newWeight));
        } catch (DataIntegrityViolationException e) {
            return waitForExisting(() -> correctionRepository.findByCorrectionNo(correctionNo)
                    .map(c -> verifyCorrectionReplay(c, landingEventId, newWeight)),
                    "更正处理冲突，请重试: " + correctionNo);
        }
    }

    private LandingCorrection doCreateCorrection(String correctionNo, String landingEventId,
                                                 BigDecimal correctedWeight) {
        var existing = correctionRepository.findByCorrectionNo(correctionNo);
        if (existing.isPresent()) {
            return verifyCorrectionReplay(existing.get(), landingEventId, correctedWeight);
        }
        LandingRecord landing = landingRepository.findByEventId(landingEventId)
                .orElseThrow(() -> BusinessException.notFound("卸港记录不存在: " + landingEventId));
        if (!landing.isConfirmed()) {
            throw BusinessException.unprocessable("只有已核销的申报才能更正，当前状态: " + landing.getStatus());
        }
        if (landing.getConfirmedWeight().compareTo(correctedWeight) == 0) {
            throw BusinessException.unprocessable("更正重量与当前生效重量相同，无需更正");
        }
        QuotaAccount account = accountRepository
                .findBySeasonAndSpeciesAndHolder(landing.getSeason(), landing.getSpecies(), landing.getHolder())
                .orElseThrow(() -> BusinessException.notFound(
                        "配额账户不存在: " + landing.getSeason() + "/" + landing.getSpecies()
                                + "/" + landing.getHolder()));
        return correctionRepository.save(new LandingCorrection(correctionNo, landing.getId(),
                landing.getConfirmedWeight(), correctedWeight, landing.getVersion(), account.getVersion()));
    }

    private LandingCorrection verifyCorrectionReplay(LandingCorrection correction, String landingEventId,
                                                     BigDecimal correctedWeight) {
        LandingRecord landing = getByEventId(landingEventId);
        if (!correction.getLandingId().equals(landing.getId())
                || correction.getCorrectedWeight().compareTo(correctedWeight) != 0) {
            throw BusinessException.conflict("更正号已存在且内容不一致: " + correction.getCorrectionNo());
        }
        return correction;
    }

    /**
     * 确认更正：单事务内完成版本校验与全部差额处理，失败回滚，不写部分差额。
     * 增重时从可用量追扣，不足拒绝；减重时只把实际差额从已核销归还可用量。
     * 重复确认（更正号幂等）直接返回原更正，不重复扣减或归还。
     */
    @Transactional
    public LandingCorrection confirmCorrection(String correctionNo) {
        LandingCorrection correction = correctionRepository.findByCorrectionNoForUpdate(correctionNo)
                .orElseThrow(() -> BusinessException.notFound("更正不存在: " + correctionNo));
        if (correction.isConfirmed()) {
            // 更正号幂等：重复确认直接返回，不重复扣减或归还
            return correction;
        }
        // 先锁申报（@Version 同时做乐观校验），再锁账户，与复核路径一致
        LandingRecord landing;
        QuotaAccount account;
        try {
            landing = landingRepository.findByIdForUpdate(correction.getLandingId())
                    .orElseThrow(() -> BusinessException.notFound(
                            "卸港记录不存在: " + correction.getLandingId()));
            if (!landing.isConfirmed()) {
                throw BusinessException.conflict("申报当前状态不允许更正: " + landing.getStatus());
            }
            if (landing.getVersion() != correction.getExpectedLandingVersion()) {
                throw staleDecision("更正基于的申报版本已变化");
            }
            if (landing.getConfirmedWeight().compareTo(correction.getOriginalWeight()) != 0) {
                throw staleDecision("更正基于的生效重量已变化");
            }
            account = lockAccount(landing.getSeason(), landing.getSpecies(), landing.getHolder());
            if (account.getVersion() != correction.getExpectedAccountVersion()) {
                throw staleDecision("更正基于的账户版本已变化");
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            throw staleDecision("更正基于的申报或账户版本已变化");
        }

        BigDecimal original = correction.getOriginalWeight();
        BigDecimal target = correction.getCorrectedWeight();
        if (target.compareTo(original) > 0) {
            BigDecimal delta = target.subtract(original);
            if (account.getAvailable().compareTo(delta) < 0) {
                throw BusinessException.unprocessable("可用配额不足，无法追扣更正增重差额 " + delta);
            }
            account.consume(delta);
            assertConsistent(account);
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.CORRECTION_DEDUCT,
                    delta, correctionNo));
        } else {
            BigDecimal delta = original.subtract(target);
            account.returnConsumed(delta);
            assertConsistent(account);
            ledgerRepository.save(new LedgerEvent(account, LedgerEventType.CORRECTION_RETURN,
                    delta, correctionNo));
        }
        landing.markConfirmed(target);
        correction.markConfirmed();
        return correction;
    }

    private BusinessException staleDecision(String message) {
        return BusinessException.conflict(message + "，旧决定已拒绝，请基于最新版本重新发起");
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public LandingRecord getByEventId(String eventId) {
        return landingRepository.findByEventId(eventId)
                .orElseThrow(() -> BusinessException.notFound("卸港记录不存在: " + eventId));
    }

    @Transactional(readOnly = true)
    public String getLandingEventId(Long landingId) {
        return landingRepository.findById(landingId)
                .orElseThrow(() -> BusinessException.notFound("卸港记录不存在: " + landingId))
                .getEventId();
    }

    @Transactional(readOnly = true)
    public List<LandingRecord> list(String season, String species, String holder) {
        if (season != null && species != null && holder != null) {
            return landingRepository.findBySeasonAndSpeciesAndHolder(season, species, holder);
        }
        return landingRepository.findAll();
    }

    @Transactional(readOnly = true)
    public LandingReview getReview(String reviewEventId) {
        return reviewRepository.findByReviewEventId(reviewEventId)
                .orElseThrow(() -> BusinessException.notFound("复核记录不存在: " + reviewEventId));
    }

    @Transactional(readOnly = true)
    public LandingCorrection getCorrection(String correctionNo) {
        return correctionRepository.findByCorrectionNo(correctionNo)
                .orElseThrow(() -> BusinessException.notFound("更正不存在: " + correctionNo));
    }

    /**
     * 申报版本链：原申报 + 复核结论（如有）+ 按序的更正链。
     */
    @Transactional(readOnly = true)
    public LandingVersionChain getVersionChain(String eventId) {
        LandingRecord landing = getByEventId(eventId);
        LandingReview review = reviewRepository.findByLandingId(landing.getId()).orElse(null);
        List<LandingCorrection> corrections = correctionRepository.findByLandingIdOrderByIdAsc(landing.getId());
        return new LandingVersionChain(landing, review, corrections);
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    private QuotaAccount lockAccount(String season, String species, String holder) {
        return accountRepository.findForUpdate(season, species, holder)
                .orElseThrow(() -> BusinessException.notFound(
                        "配额账户不存在: " + season + "/" + species + "/" + holder));
    }

    private void assertConsistent(QuotaAccount account) {
        if (account.hasNegativeBalance()) {
            throw BusinessException.unprocessable("操作会导致账户余额为负: " + account.getHolder());
        }
    }

    /**
     * 唯一约束冲突后，获胜事务可能尚未提交，短暂等待其可见后按幂等重放校验。
     */
    private <T> T waitForExisting(java.util.function.Supplier<java.util.Optional<T>> lookup,
                                  String conflictMessage) {
        for (int attempt = 0; attempt < 20; attempt++) {
            var found = lookup.get();
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw BusinessException.conflict(conflictMessage);
    }
}
