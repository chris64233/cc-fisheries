package com.chris64233.cc.fisheries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.hold.HoldStatus;
import com.chris64233.cc.fisheries.hold.HoldType;
import com.chris64233.cc.fisheries.hold.QuotaHold;
import com.chris64233.cc.fisheries.landing.LandingRecord;
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
class LandingServiceTest {

    @Autowired
    QuotaAccountService accountService;

    @Autowired
    LandingService landingService;

    @Test
    void declareFreezesAvailableAndWritesHoldWithoutConsuming() {
        QuotaAccount account = accountService.createAccount("L1", "COD", "A", new BigDecimal("100"));

        LandingRecord record = landingService.declare("EVT-1", "VESSEL-1", "A", "COD", "L1",
                new BigDecimal("25.5"));

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(record.getStatus()).isEqualTo(LandingStatus.PENDING_REVIEW);
        assertThat(after.getAvailable()).isEqualByComparingTo("74.5");
        assertThat(after.getFrozen()).isEqualByComparingTo("25.5");
        assertThat(after.getConsumed()).isEqualByComparingTo("0");
        // 总量守恒
        assertThat(after.total()).isEqualByComparingTo("100");

        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(1).getType()).isEqualTo(LedgerEventType.LANDING_FREEZE);
        assertThat(ledger.get(1).getReference()).isEqualTo("EVT-1");
        assertThat(ledger.get(1).getFrozenAfter()).isEqualByComparingTo("25.5");

        List<QuotaHold> activeHolds = accountService.getHolds(account.getId(), true);
        assertThat(activeHolds).hasSize(1);
        assertThat(activeHolds.get(0).getHoldType()).isEqualTo(HoldType.LANDING);
        assertThat(activeHolds.get(0).getQuantity()).isEqualByComparingTo("25.5");
        assertThat(activeHolds.get(0).getStatus()).isEqualTo(HoldStatus.HELD);
    }

    @Test
    void confirmSettlesFrozenIntoConsumed() {
        QuotaAccount account = accountService.createAccount("L2", "COD", "A", new BigDecimal("100"));
        LandingRecord pending = landingService.declare("EVT-2", "V1", "A", "COD", "L2", new BigDecimal("30"));
        LandingRecord confirmed = landingService.confirm("EVT-2", "REV-2", "port-1");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(confirmed.getStatus()).isEqualTo(LandingStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedWeight()).isEqualByComparingTo("30");
        assertThat(confirmed.getVersion()).isGreaterThan(pending.getVersion());
        assertThat(after.getAvailable()).isEqualByComparingTo("70");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("30");
        assertThat(after.total()).isEqualByComparingTo("100");

        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger).extracting(LedgerEvent::getType)
                .containsExactly(LedgerEventType.GRANT, LedgerEventType.LANDING_FREEZE,
                        LedgerEventType.LANDING_CONSUME);
        // 持有中的冻结明细归零，frozen 余额与 HELD 明细一致
        assertThat(accountService.getHolds(account.getId(), true)).isEmpty();
        assertThat(accountService.getHolds(account.getId(), false).get(0).getStatus())
                .isEqualTo(HoldStatus.SETTLED);
    }

    @Test
    void rejectReleasesFrozenBackToAvailable() {
        QuotaAccount account = accountService.createAccount("L3", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-3", "V1", "A", "COD", "L3", new BigDecimal("40"));

        LandingRecord rejected = landingService.reject("EVT-3", "REV-3", "port-1");

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(rejected.getStatus()).isEqualTo(LandingStatus.REJECTED);
        assertThat(after.getAvailable()).isEqualByComparingTo("100");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("0");
        assertThat(accountService.getLedger(account.getId()))
                .extracting(LedgerEvent::getType)
                .containsExactly(LedgerEventType.GRANT, LedgerEventType.LANDING_FREEZE,
                        LedgerEventType.LANDING_RELEASE);
        assertThat(accountService.getHolds(account.getId(), false).get(0).getStatus())
                .isEqualTo(HoldStatus.RELEASED);
    }

    @Test
    void duplicateReviewEventIsIdempotentAndDoesNotSettleTwice() {
        QuotaAccount account = accountService.createAccount("L4", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-4", "V1", "A", "COD", "L4", new BigDecimal("20"));

        LandingRecord first = landingService.confirm("EVT-4", "REV-4", "port-1");
        LandingRecord replay = landingService.confirm("EVT-4", "REV-4", "port-2");

        assertThat(replay.getVersion()).isEqualTo(first.getVersion());
        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getConsumed()).isEqualByComparingTo("20");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        long consumes = accountService.getLedger(account.getId()).stream()
                .filter(e -> e.getType() == LedgerEventType.LANDING_CONSUME).count();
        assertThat(consumes).isEqualTo(1);
    }

    @Test
    void sameReviewEventOnDifferentLandingConflicts() {
        accountService.createAccount("L5", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-5A", "V1", "A", "COD", "L5", new BigDecimal("10"));
        landingService.declare("EVT-5B", "V1", "A", "COD", "L5", new BigDecimal("10"));
        landingService.confirm("EVT-5A", "REV-5", "port-1");

        assertThatThrownBy(() -> landingService.confirm("EVT-5B", "REV-5", "port-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        // 第二笔仍处待复核，冻结量未被动
        QuotaAccount after = accountService.getAccount(accountService.listAccounts("L5", "COD").get(0).getId());
        assertThat(after.getFrozen()).isEqualByComparingTo("10");
    }

    @Test
    void confirmAlreadyReviewedLandingConflicts() {
        accountService.createAccount("L6", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-6", "V1", "A", "COD", "L6", new BigDecimal("10"));
        landingService.confirm("EVT-6", "REV-6A", "port-1");

        assertThatThrownBy(() -> landingService.confirm("EVT-6", "REV-6B", "port-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void replayDeclareWithSameContentIsIdempotent() {
        QuotaAccount account = accountService.createAccount("L7", "COD", "A", new BigDecimal("100"));
        LandingRecord first = landingService.declare("EVT-7", "V1", "A", "COD", "L7", new BigDecimal("10"));

        LandingRecord replay = landingService.declare("EVT-7", "V1", "A", "COD", "L7", new BigDecimal("10.000"));

        assertThat(replay.getId()).isEqualTo(first.getId());
        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getFrozen()).isEqualByComparingTo("10");
        assertThat(accountService.getLedger(account.getId())).hasSize(2);
    }

    @Test
    void replayDeclareWithDifferentContentConflicts() {
        accountService.createAccount("L8", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-8", "V1", "A", "COD", "L8", new BigDecimal("10"));

        assertThatThrownBy(() -> landingService.declare("EVT-8", "V1", "A", "COD", "L8", new BigDecimal("11")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void declareFailsWhenInsufficientAndWritesNothing() {
        QuotaAccount account = accountService.createAccount("L9", "COD", "A", new BigDecimal("5"));

        assertThatThrownBy(() -> landingService.declare("EVT-9", "V1", "A", "COD", "L9", new BigDecimal("6")))
                .isInstanceOf(BusinessException.class);

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("5");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(accountService.getLedger(account.getId())).hasSize(1);
        assertThat(accountService.getHolds(account.getId(), false)).isEmpty();
        assertThatThrownBy(() -> landingService.getByEventId("EVT-9"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void declareFailsWhenAccountMissing() {
        assertThatThrownBy(() -> landingService.declare("EVT-10", "V1", "NOBODY", "COD", "L10",
                new BigDecimal("1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void weightPrecisionBeyondScaleIsRejected() {
        accountService.createAccount("L11", "COD", "A", new BigDecimal("10"));
        assertThatThrownBy(() -> landingService.declare("EVT-11", "V1", "A", "COD", "L11",
                new BigDecimal("1.0001")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
