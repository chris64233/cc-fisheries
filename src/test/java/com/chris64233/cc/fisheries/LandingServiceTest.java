package com.chris64233.cc.fisheries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.landing.LandingCorrection;
import com.chris64233.cc.fisheries.landing.LandingRecord;
import com.chris64233.cc.fisheries.landing.LandingReview;
import com.chris64233.cc.fisheries.landing.LandingService;
import com.chris64233.cc.fisheries.quota.LedgerEvent;
import com.chris64233.cc.fisheries.quota.LedgerEventType;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

@SpringBootTest
class LandingServiceTest {

    @Autowired
    QuotaAccountService accountService;

    @Autowired
    LandingService landingService;

    @Test
    void declareFreezesQuotaAndStaysPendingReview() {
        QuotaAccount account = accountService.createAccount("L1", "COD", "A", new BigDecimal("100"));

        LandingRecord record = landingService.declare("EVT-1", "VESSEL-1", "A", "COD", "L1",
                new BigDecimal("25.5"));

        assertThat(record.getStatus().name()).isEqualTo("PENDING_REVIEW");
        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("74.5");
        assertThat(after.getFrozen()).isEqualByComparingTo("25.5");
        assertThat(after.getConsumed()).isEqualByComparingTo("0");
        assertThat(after.total()).isEqualByComparingTo("100");

        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(1).getType()).isEqualTo(LedgerEventType.LANDING_FREEZE);
        assertThat(ledger.get(1).getReference()).isEqualTo("EVT-1");
        assertThat(ledger.get(1).getFrozenAfter()).isEqualByComparingTo("25.5");
    }

    @Test
    void confirmWithSameWeightSettlesFrozenToConsumed() {
        QuotaAccount account = accountService.createAccount("L2", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-2", "V1", "A", "COD", "L2", new BigDecimal("25.5"));

        LandingReview review = landingService.confirmReview("REV-2", "EVT-2", "PORT-1",
                new BigDecimal("25.5"));

        assertThat(review.getDecision().name()).isEqualTo("CONFIRMED");
        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("74.5");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("25.5");
        assertThat(after.total()).isEqualByComparingTo("100");

        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger).hasSize(3);
        assertThat(ledger.get(2).getType()).isEqualTo(LedgerEventType.LANDING_SETTLE);
        assertThat(ledger.get(2).getQuantity()).isEqualByComparingTo("25.5");
        assertThat(ledger.get(2).getConsumedAfter()).isEqualByComparingTo("25.5");
    }

    @Test
    void confirmWithLowerWeightSettlesActualAndReleasesDifference() {
        QuotaAccount account = accountService.createAccount("L3", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-3", "V1", "A", "COD", "L3", new BigDecimal("30"));

        landingService.confirmReview("REV-3", "EVT-3", "PORT-1", new BigDecimal("22"));

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("78");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("22");
        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger).hasSize(4);
        assertThat(ledger.get(2).getType()).isEqualTo(LedgerEventType.LANDING_SETTLE);
        assertThat(ledger.get(2).getQuantity()).isEqualByComparingTo("22");
        assertThat(ledger.get(3).getType()).isEqualTo(LedgerEventType.LANDING_RELEASE);
        assertThat(ledger.get(3).getQuantity()).isEqualByComparingTo("8");
    }

    @Test
    void confirmWithHigherWeightConsumesExtraFromAvailable() {
        QuotaAccount account = accountService.createAccount("L4", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-4", "V1", "A", "COD", "L4", new BigDecimal("30"));

        landingService.confirmReview("REV-4", "EVT-4", "PORT-1", new BigDecimal("40"));

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("60");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("40");
        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger).hasSize(4);
        assertThat(ledger.get(2).getType()).isEqualTo(LedgerEventType.LANDING_SETTLE);
        assertThat(ledger.get(2).getQuantity()).isEqualByComparingTo("30");
        assertThat(ledger.get(3).getType()).isEqualTo(LedgerEventType.REVIEW_CONSUME);
        assertThat(ledger.get(3).getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void confirmHigherWeightFailsWhenAvailableInsufficientAndWritesNothing() {
        QuotaAccount account = accountService.createAccount("L5", "COD", "A", new BigDecimal("100"));
        // 冻结 95，仅留 5 可用；确认重量 110 需追扣 15
        landingService.declare("EVT-5", "V1", "A", "COD", "L5", new BigDecimal("95"));

        assertThatThrownBy(() -> landingService.confirmReview("REV-5", "EVT-5", "PORT-1",
                new BigDecimal("110")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("5");
        assertThat(after.getFrozen()).isEqualByComparingTo("95");
        assertThat(after.getConsumed()).isEqualByComparingTo("0");
        // 只有入账与冻结两条台账，失败未写任何核销/释放
        assertThat(accountService.getLedger(account.getId())).hasSize(2);
        assertThat(landingService.getByEventId("EVT-5").getStatus().name()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void rejectReviewReleasesFullFreeze() {
        QuotaAccount account = accountService.createAccount("L6", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-6", "V1", "A", "COD", "L6", new BigDecimal("30"));

        landingService.rejectReview("REV-6", "EVT-6", "PORT-1");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("100");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("0");
        assertThat(landingService.getByEventId("EVT-6").getStatus().name()).isEqualTo("REJECTED");
    }

    @Test
    void reviewIsIdempotentAndCannotReviewTwice() {
        accountService.createAccount("L7", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-7", "V1", "A", "COD", "L7", new BigDecimal("10"));

        LandingReview first = landingService.confirmReview("REV-7", "EVT-7", "PORT-1", new BigDecimal("10"));
        LandingReview replay = landingService.confirmReview("REV-7", "EVT-7", "PORT-1",
                new BigDecimal("10.000"));
        assertThat(replay.getId()).isEqualTo(first.getId());

        // 同事件号不同结论冲突
        assertThatThrownBy(() -> landingService.rejectReview("REV-7", "EVT-7", "PORT-1"))
                .isInstanceOf(BusinessException.class);
        // 换一个复核事件号再次复核同一申报：拒绝
        assertThatThrownBy(() -> landingService.confirmReview("REV-7B", "EVT-7", "PORT-1",
                new BigDecimal("10")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void replayDeclareWithSameContentIsIdempotent() {
        QuotaAccount account = accountService.createAccount("L8", "COD", "A", new BigDecimal("100"));
        LandingRecord first = landingService.declare("EVT-8", "V1", "A", "COD", "L8", new BigDecimal("10"));

        LandingRecord replay = landingService.declare("EVT-8", "V1", "A", "COD", "L8", new BigDecimal("10.000"));

        assertThat(replay.getId()).isEqualTo(first.getId());
        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getFrozen()).isEqualByComparingTo("10");
        assertThat(accountService.getLedger(account.getId())).hasSize(2);
    }

    @Test
    void replayDeclareWithDifferentContentConflicts() {
        accountService.createAccount("L9", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-9", "V1", "A", "COD", "L9", new BigDecimal("10"));

        assertThatThrownBy(() -> landingService.declare("EVT-9", "V1", "A", "COD", "L9",
                new BigDecimal("11")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void declareFailsWhenInsufficientAndWritesNoLedger() {
        QuotaAccount account = accountService.createAccount("L10", "COD", "A", new BigDecimal("5"));

        assertThatThrownBy(() -> landingService.declare("EVT-10", "V1", "A", "COD", "L10",
                new BigDecimal("6")))
                .isInstanceOf(BusinessException.class);

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("5");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(accountService.getLedger(account.getId())).hasSize(1);
        assertThatThrownBy(() -> landingService.getByEventId("EVT-10"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void declareFailsWhenAccountMissing() {
        assertThatThrownBy(() -> landingService.declare("EVT-11", "V1", "NOBODY", "COD", "L11",
                new BigDecimal("1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void weightPrecisionBeyondScaleIsRejected() {
        accountService.createAccount("L12", "COD", "A", new BigDecimal("10"));
        assertThatThrownBy(() -> landingService.declare("EVT-12", "V1", "A", "COD", "L12",
                new BigDecimal("1.0001")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ------------------------------------------------------------------
    // 更正
    // ------------------------------------------------------------------

    private LandingRecord confirmedLanding(String season, String holder, String eventId, String weight) {
        accountService.createAccount(season, "COD", holder, new BigDecimal("100"));
        landingService.declare(eventId, "V1", holder, "COD", season, new BigDecimal(weight));
        landingService.confirmReview("REV-" + eventId, eventId, "PORT-1", new BigDecimal(weight));
        return landingService.getByEventId(eventId);
    }

    @Test
    void correctionIncreaseDeductsDifferenceFromAvailable() {
        QuotaAccount account = accountService.createAccount("M1", "COD", "A", new BigDecimal("100"));
        landingService.declare("MEVT-1", "V1", "A", "COD", "M1", new BigDecimal("30"));
        landingService.confirmReview("MREV-1", "MEVT-1", "PORT-1", new BigDecimal("30"));

        LandingCorrection correction =
                landingService.createCorrection("COR-1", "MEVT-1", new BigDecimal("35"));
        assertThat(correction.getStatus().name()).isEqualTo("PENDING");
        // 创建更正不动配额
        assertThat(accountService.getAccount(account.getId()).getConsumed()).isEqualByComparingTo("30");

        landingService.confirmCorrection("COR-1");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("65");
        assertThat(after.getConsumed()).isEqualByComparingTo("35");
        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger.get(ledger.size() - 1).getType()).isEqualTo(LedgerEventType.CORRECTION_DEDUCT);
        assertThat(ledger.get(ledger.size() - 1).getQuantity()).isEqualByComparingTo("5");
        assertThat(landingService.getByEventId("MEVT-1").getConfirmedWeight()).isEqualByComparingTo("35");
        // 原申报重量未被覆盖
        assertThat(landingService.getByEventId("MEVT-1").getWeight()).isEqualByComparingTo("30");
    }

    @Test
    void correctionDecreaseReturnsOnlyActualDifference() {
        QuotaAccount account = accountService.createAccount("M2", "COD", "A", new BigDecimal("100"));
        landingService.declare("MEVT-2", "V1", "A", "COD", "M2", new BigDecimal("30"));
        landingService.confirmReview("MREV-2", "MEVT-2", "PORT-1", new BigDecimal("30"));

        landingService.createCorrection("COR-2", "MEVT-2", new BigDecimal("20"));
        landingService.confirmCorrection("COR-2");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("80");
        assertThat(after.getConsumed()).isEqualByComparingTo("20");
        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger.get(ledger.size() - 1).getType()).isEqualTo(LedgerEventType.CORRECTION_RETURN);
        assertThat(ledger.get(ledger.size() - 1).getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void correctionIncreaseFailsWhenInsufficientAndWritesNoPartialDifference() {
        QuotaAccount account = accountService.createAccount("M3", "COD", "A", new BigDecimal("100"));
        landingService.declare("MEVT-3", "V1", "A", "COD", "M3", new BigDecimal("90"));
        landingService.confirmReview("MREV-3", "MEVT-3", "PORT-1", new BigDecimal("90"));
        // 可用只剩 10，增重到 105 需追扣 15
        landingService.createCorrection("COR-3", "MEVT-3", new BigDecimal("105"));

        assertThatThrownBy(() -> landingService.confirmCorrection("COR-3"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("10");
        assertThat(after.getConsumed()).isEqualByComparingTo("90");
        // 台账止于 LANDING_SETTLE，无更正差额台账；更正仍待确认
        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger.get(ledger.size() - 1).getType()).isEqualTo(LedgerEventType.LANDING_SETTLE);
        assertThat(landingService.getCorrection("COR-3").getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void staleLandingVersionRejectsOldCorrectionDecision() {
        confirmedLanding("M4", "A", "MEVT-4", "30");
        // 先基于 30 创建更正到 35
        landingService.createCorrection("COR-4A", "MEVT-4", new BigDecimal("35"));
        // 另一笔更正先确认，申报版本推进
        landingService.createCorrection("COR-4B", "MEVT-4", new BigDecimal("25"));
        landingService.confirmCorrection("COR-4B");

        // 旧决定（基于 30）必须被拒绝
        assertThatThrownBy(() -> landingService.confirmCorrection("COR-4A"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        // 拒绝旧决定不能改变余额：当前生效 25，已核销 25
        QuotaAccount account = accountService.getAccount(
                accountService.listAccounts("M4", "COD").get(0).getId());
        assertThat(account.getConsumed()).isEqualByComparingTo("25");
        assertThat(account.getAvailable()).isEqualByComparingTo("75");
    }

    @Test
    void correctionOnUnconfirmedLandingIsRejected() {
        accountService.createAccount("M5", "COD", "A", new BigDecimal("100"));
        landingService.declare("MEVT-5", "V1", "A", "COD", "M5", new BigDecimal("10"));
        assertThatThrownBy(() -> landingService.createCorrection("COR-5", "MEVT-5", new BigDecimal("12")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void correctionNoIsIdempotentAndConfirmIsIdempotent() {
        confirmedLanding("M6", "A", "MEVT-6", "30");
        LandingCorrection created = landingService.createCorrection("COR-6", "MEVT-6",
                new BigDecimal("40"));
        LandingCorrection replay = landingService.createCorrection("COR-6", "MEVT-6",
                new BigDecimal("40.000"));
        assertThat(replay.getId()).isEqualTo(created.getId());

        landingService.confirmCorrection("COR-6");
        landingService.confirmCorrection("COR-6");

        QuotaAccount account = accountService.getAccount(
                accountService.listAccounts("M6", "COD").get(0).getId());
        assertThat(account.getConsumed()).isEqualByComparingTo("40");
        assertThat(account.getAvailable()).isEqualByComparingTo("60");
        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        long deductEvents = ledger.stream()
                .filter(e -> e.getType() == LedgerEventType.CORRECTION_DEDUCT).count();
        assertThat(deductEvents).isEqualTo(1);
    }

    @Test
    void correctionChainAccumulatesDifferences() {
        QuotaAccount account = accountService.createAccount("M7", "COD", "A", new BigDecimal("100"));
        landingService.declare("MEVT-7", "V1", "A", "COD", "M7", new BigDecimal("30"));
        landingService.confirmReview("MREV-7", "MEVT-7", "PORT-1", new BigDecimal("30"));

        landingService.createCorrection("COR-7A", "MEVT-7", new BigDecimal("40"));
        landingService.confirmCorrection("COR-7A");
        landingService.createCorrection("COR-7B", "MEVT-7", new BigDecimal("35"));
        landingService.confirmCorrection("COR-7B");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getConsumed()).isEqualByComparingTo("35");
        assertThat(after.getAvailable()).isEqualByComparingTo("65");

        var chain = landingService.getVersionChain("MEVT-7");
        assertThat(chain.corrections()).hasSize(2);
        assertThat(chain.corrections().get(0).getCorrectionNo()).isEqualTo("COR-7A");
        assertThat(chain.corrections().get(1).getCorrectionNo()).isEqualTo("COR-7B");
        assertThat(chain.landing().getWeight()).isEqualByComparingTo("30");
        assertThat(chain.review()).isNotNull();
    }
}
