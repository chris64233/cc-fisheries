package com.chris64233.cc.fisheries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.correction.Correction;
import com.chris64233.cc.fisheries.correction.CorrectionDirection;
import com.chris64233.cc.fisheries.correction.CorrectionService;
import com.chris64233.cc.fisheries.correction.CorrectionStatus;
import com.chris64233.cc.fisheries.landing.LandingService;
import com.chris64233.cc.fisheries.landing.LandingStatus;
import com.chris64233.cc.fisheries.quota.LedgerEvent;
import com.chris64233.cc.fisheries.quota.LedgerEventType;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

@SpringBootTest
class CorrectionServiceTest {

    @Autowired
    QuotaAccountService accountService;
    @Autowired
    LandingService landingService;
    @Autowired
    CorrectionService correctionService;

    private QuotaAccount setupConfirmedLanding(String season, String holder, String event,
                                               String review, String grant, String weight) {
        QuotaAccount account = accountService.createAccount(season, "COD", holder, new BigDecimal(grant));
        landingService.declare(event, "V1", holder, "COD", season, new BigDecimal(weight));
        landingService.confirm(event, review, "port-1");
        return account;
    }

    @Test
    void increaseFreezesExtraQuotaOnCreateAndConsumesOnConfirm() {
        QuotaAccount account = setupConfirmedLanding("K1", "A", "E1", "R1", "100", "40");
        // 确认后 available=60 frozen=0 consumed=40

        Correction correction = correctionService.create("C1", "E1", new BigDecimal("55"));

        QuotaAccount mid = accountService.getAccount(account.getId());
        assertThat(correction.getDirection()).isEqualTo(CorrectionDirection.INCREASE);
        assertThat(correction.getDelta()).isEqualByComparingTo("15");
        assertThat(correction.getStatus()).isEqualTo(CorrectionStatus.PENDING);
        assertThat(mid.getAvailable()).isEqualByComparingTo("45");
        assertThat(mid.getFrozen()).isEqualByComparingTo("15");
        assertThat(mid.getConsumed()).isEqualByComparingTo("40");
        assertThat(mid.total()).isEqualByComparingTo("100");
        assertThat(accountService.getLedger(account.getId()))
                .extracting(LedgerEvent::getType)
                .contains(LedgerEventType.CORRECTION_FREEZE);

        Correction confirmed = correctionService.confirm("C1", "CR1", "port-2");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(confirmed.getStatus()).isEqualTo(CorrectionStatus.CONFIRMED);
        assertThat(after.getAvailable()).isEqualByComparingTo("45");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("55");
        assertThat(after.total()).isEqualByComparingTo("100");
        assertThat(accountService.getLedger(account.getId()))
                .extracting(LedgerEvent::getType)
                .endsWith(LedgerEventType.CORRECTION_CONSUME);

        // 原申报状态转为 CORRECTED，当前有效重量更新，但原申报重量仍为 40
        var landing = landingService.getByEventId("E1");
        assertThat(landing.getStatus()).isEqualTo(LandingStatus.CORRECTED);
        assertThat(landing.getConfirmedWeight()).isEqualByComparingTo("55");
        assertThat(landing.getWeight()).isEqualByComparingTo("40");
    }

    @Test
    void increaseCreationFailsWhenAvailableInsufficientAndWritesNothing() {
        QuotaAccount account = setupConfirmedLanding("K2", "A", "E2", "R2", "50", "50");
        // available=0，增重 10 需要冻结 10，失败

        assertThatThrownBy(() -> correctionService.create("C2", "E2", new BigDecimal("60")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("0");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("50");
        assertThat(accountService.getLedger(account.getId())).hasSize(3);
        assertThatThrownBy(() -> correctionService.getByCorrectionId("C2"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void decreaseDoesNotTouchBalancesOnCreateAndRefundsActualDeltaOnConfirm() {
        QuotaAccount account = setupConfirmedLanding("K3", "A", "E3", "R3", "100", "60");
        // available=40 frozen=0 consumed=60

        Correction correction = correctionService.create("C3", "E3", new BigDecimal("35"));

        QuotaAccount mid = accountService.getAccount(account.getId());
        assertThat(correction.getDirection()).isEqualTo(CorrectionDirection.DECREASE);
        assertThat(correction.getDelta()).isEqualByComparingTo("25");
        // 创建减重不碰余额
        assertThat(mid.getAvailable()).isEqualByComparingTo("40");
        assertThat(mid.getFrozen()).isEqualByComparingTo("0");
        assertThat(mid.getConsumed()).isEqualByComparingTo("60");

        correctionService.confirm("C3", "CR3", "port-2");

        QuotaAccount after = accountService.getAccount(account.getId());
        // 只归还实际差额 25
        assertThat(after.getAvailable()).isEqualByComparingTo("65");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("35");
        assertThat(after.total()).isEqualByComparingTo("100");
        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger).extracting(LedgerEvent::getType)
                .endsWith(LedgerEventType.CORRECTION_REFUND);
        assertThat(ledger).extracting(LedgerEvent::getType)
                .doesNotContain(LedgerEventType.CORRECTION_FREEZE);
        assertThat(landingService.getByEventId("E3").getConfirmedWeight()).isEqualByComparingTo("35");
    }

    @Test
    void rejectedIncreaseReleasesFrozenAndDoesNotConsume() {
        QuotaAccount account = setupConfirmedLanding("K4", "A", "E4", "R4", "100", "40");
        correctionService.create("C4", "E4", new BigDecimal("60"));
        // available=20 frozen=20 consumed=40

        correctionService.reject("C4", "CR4", "port-2");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("60");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("40");
        assertThat(accountService.getLedger(account.getId()))
                .extracting(LedgerEvent::getType)
                .endsWith(LedgerEventType.CORRECTION_RELEASE);
        assertThat(landingService.getByEventId("E4").getStatus()).isEqualTo(LandingStatus.CONFIRMED);
    }

    @Test
    void rejectedDecreaseLeavesBalancesUntouched() {
        QuotaAccount account = setupConfirmedLanding("K5", "A", "E5", "R5", "100", "60");
        correctionService.create("C5", "E5", new BigDecimal("30"));

        correctionService.reject("C5", "CR5", "port-2");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("40");
        assertThat(after.getConsumed()).isEqualByComparingTo("60");
        assertThat(landingService.getByEventId("E5").getConfirmedWeight()).isEqualByComparingTo("60");
    }

    @Test
    void correctionCreateIsIdempotent() {
        setupConfirmedLanding("K6", "A", "E6", "R6", "100", "40");
        Correction first = correctionService.create("C6", "E6", new BigDecimal("50"));

        Correction replay = correctionService.create("C6", "E6", new BigDecimal("50.000"));

        assertThat(replay.getId()).isEqualTo(first.getId());
        // 只冻结一次
        QuotaAccount after = accountService.getAccount(accountService.listAccounts("K6", "COD").get(0).getId());
        assertThat(after.getFrozen()).isEqualByComparingTo("10");
    }

    @Test
    void correctionCreateReplayWithDifferentContentConflicts() {
        setupConfirmedLanding("K7", "A", "E7", "R7", "100", "40");
        correctionService.create("C7", "E7", new BigDecimal("50"));

        assertThatThrownBy(() -> correctionService.create("C7", "E7", new BigDecimal("51")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void duplicateCorrectionReviewIsIdempotentAndDoesNotSettleOrRefundTwice() {
        QuotaAccount account = setupConfirmedLanding("K8", "A", "E8", "R8", "100", "40");
        correctionService.create("C8", "E8", new BigDecimal("55"));
        correctionService.confirm("C8", "CR8", "port-2");

        Correction replay = correctionService.confirm("C8", "CR8", "port-3");

        assertThat(replay.getStatus()).isEqualTo(CorrectionStatus.CONFIRMED);
        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getConsumed()).isEqualByComparingTo("55");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        long consumeEvents = accountService.getLedger(account.getId()).stream()
                .filter(e -> e.getType() == LedgerEventType.CORRECTION_CONSUME).count();
        assertThat(consumeEvents).isEqualTo(1);
    }

    @Test
    void sameCorrectionReviewEventOnDifferentCorrectionConflicts() {
        setupConfirmedLanding("K9", "A", "E9A", "R9A", "100", "20");
        setupConfirmedLanding("K9", "B", "E9B", "R9B", "100", "20");
        correctionService.create("C9A", "E9A", new BigDecimal("25"));
        correctionService.create("C9B", "E9B", new BigDecimal("25"));
        correctionService.confirm("C9A", "CR9", "port-2");

        assertThatThrownBy(() -> correctionService.confirm("C9B", "CR9", "port-2"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        // C9B 仍 PENDING，冻结仍在
        QuotaAccount b = accountService.getAccount(accountService.listAccounts("K9", "COD").stream()
                .filter(a -> a.getHolder().equals("B")).findFirst().orElseThrow().getId());
        assertThat(b.getFrozen()).isEqualByComparingTo("5");
        assertThat(correctionService.getByCorrectionId("C9B").getStatus())
                .isEqualTo(CorrectionStatus.PENDING);
    }

    @Test
    void cannotCorrectUnconfirmedLanding() {
        QuotaAccount account = accountService.createAccount("K10", "COD", "A", new BigDecimal("100"));
        landingService.declare("E10", "V1", "A", "COD", "K10", new BigDecimal("30"));

        assertThatThrownBy(() -> correctionService.create("C10", "E10", new BigDecimal("40")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        QuotaAccount after = accountService.getAccount(account.getId());
        // 仍处于待复核冻结，未被更正影响
        assertThat(after.getFrozen()).isEqualByComparingTo("30");
        assertThat(after.getConsumed()).isEqualByComparingTo("0");
    }

    @Test
    void staleLandingVersionRejectsOldDecisionAndAppliesNoDelta() {
        QuotaAccount account = setupConfirmedLanding("K11", "A", "E11", "R11", "100", "40");
        // C11：增重到 50，冻结 10
        correctionService.create("C11", "E11", new BigDecimal("50"));
        // 先由另一笔已确认的更正改变了原申报版本
        correctionService.create("C11-OTHER", "E11", new BigDecimal("45"));
        correctionService.confirm("C11-OTHER", "CR11-OTHER", "port-2");
        QuotaAccount before = accountService.getAccount(account.getId());
        assertThat(landingService.getByEventId("E11").getConfirmedWeight()).isEqualByComparingTo("45");

        // 旧决定基于的申报版本已变化 → 拒绝，且不核销 C11 冻结的 10
        assertThatThrownBy(() -> correctionService.confirm("C11", "CR11", "port-2"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("版本");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(correctionService.getByCorrectionId("C11").getStatus())
                .isEqualTo(CorrectionStatus.PENDING);
        // 余额与拒绝前完全一致：C11 的 10 仍冻结，consumed=45，未写入 C11 核销台账
        assertThat(after.getFrozen()).isEqualTo(before.getFrozen());
        assertThat(after.getConsumed()).isEqualTo(before.getConsumed());
        assertThat(after.getAvailable()).isEqualTo(before.getAvailable());
        long c11Consume = accountService.getLedgerByReference(account.getId(), "C11").stream()
                .filter(e -> e.getType() == LedgerEventType.CORRECTION_CONSUME).count();
        assertThat(c11Consume).isZero();
        // 旧决定可被驳回以释放冻结
        correctionService.reject("C11", "CR11R", "port-2");
        assertThat(accountService.getAccount(account.getId()).getFrozen()).isEqualByComparingTo("0");
    }

    @Test
    void equalWeightCorrectionIsRejected() {
        setupConfirmedLanding("K12", "A", "E12", "R12", "100", "40");
        assertThatThrownBy(() -> correctionService.create("C12", "E12", new BigDecimal("40")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void versionChainReflectsOriginalAndCorrections() {
        setupConfirmedLanding("K13", "A", "E13", "R13", "100", "40");
        correctionService.create("C13A", "E13", new BigDecimal("50"));
        correctionService.confirm("C13A", "CR13A", "port-2");
        correctionService.create("C13B", "E13", new BigDecimal("35"));
        correctionService.confirm("C13B", "CR13B", "port-2");

        LandingService.VersionChain chain = landingService.getVersionChain("E13");

        assertThat(chain.effectiveWeight()).isEqualByComparingTo("35");
        assertThat(chain.entries()).hasSize(3);
        assertThat(chain.entries().get(0).nodeType()).isEqualTo("LANDING");
        assertThat(chain.entries().get(0).weight()).isEqualByComparingTo("40");
        assertThat(chain.entries().get(1).refId()).isEqualTo("C13A");
        assertThat(chain.entries().get(1).weight()).isEqualByComparingTo("50");
        assertThat(chain.entries().get(2).refId()).isEqualTo("C13B");
        assertThat(chain.entries().get(2).weight()).isEqualByComparingTo("35");
    }

    @Test
    void ledgerByReferenceReturnsOnlyThatDeltaTrail() {
        QuotaAccount account = setupConfirmedLanding("K14", "A", "E14", "R14", "100", "40");
        correctionService.create("C14", "E14", new BigDecimal("55"));
        correctionService.confirm("C14", "CR14", "port-2");

        List<LedgerEvent> c14 = accountService.getLedgerByReference(account.getId(), "C14");
        assertThat(c14).extracting(LedgerEvent::getType)
                .containsExactly(LedgerEventType.CORRECTION_FREEZE, LedgerEventType.CORRECTION_CONSUME);
    }
}
