package com.chris64233.cc.fisheries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.landing.LandingRecord;
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
    void declareDeductsAvailableAndWritesLedger() {
        QuotaAccount account = accountService.createAccount("L1", "COD", "A", new BigDecimal("100"));

        LandingRecord record = landingService.declare("EVT-1", "VESSEL-1", "A", "COD", "L1",
                new BigDecimal("25.5"));

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("74.5");
        assertThat(after.getConsumed()).isEqualByComparingTo("25.5");
        assertThat(record.getEventId()).isEqualTo("EVT-1");

        List<LedgerEvent> ledger = accountService.getLedger(account.getId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(1).getType()).isEqualTo(LedgerEventType.LANDING_DEDUCT);
        assertThat(ledger.get(1).getReference()).isEqualTo("EVT-1");
        assertThat(ledger.get(1).getConsumedAfter()).isEqualByComparingTo("25.5");
    }

    @Test
    void replayWithSameContentIsIdempotent() {
        QuotaAccount account = accountService.createAccount("L2", "COD", "A", new BigDecimal("100"));
        LandingRecord first = landingService.declare("EVT-2", "V1", "A", "COD", "L2", new BigDecimal("10"));

        LandingRecord replay = landingService.declare("EVT-2", "V1", "A", "COD", "L2", new BigDecimal("10.000"));

        assertThat(replay.getId()).isEqualTo(first.getId());
        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getConsumed()).isEqualByComparingTo("10");
        assertThat(accountService.getLedger(account.getId())).hasSize(2);
    }

    @Test
    void replayWithDifferentContentConflicts() {
        accountService.createAccount("L3", "COD", "A", new BigDecimal("100"));
        landingService.declare("EVT-3", "V1", "A", "COD", "L3", new BigDecimal("10"));

        assertThatThrownBy(() -> landingService.declare("EVT-3", "V1", "A", "COD", "L3", new BigDecimal("11")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> landingService.declare("EVT-3", "V2", "A", "COD", "L3", new BigDecimal("10")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void declareFailsWhenInsufficientAndWritesNoLedger() {
        QuotaAccount account = accountService.createAccount("L4", "COD", "A", new BigDecimal("5"));

        assertThatThrownBy(() -> landingService.declare("EVT-4", "V1", "A", "COD", "L4", new BigDecimal("6")))
                .isInstanceOf(BusinessException.class);

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("5");
        assertThat(after.getConsumed()).isEqualByComparingTo("0");
        assertThat(accountService.getLedger(account.getId())).hasSize(1);
        assertThatThrownBy(() -> landingService.getByEventId("EVT-4"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void declareFailsWhenAccountMissing() {
        assertThatThrownBy(() -> landingService.declare("EVT-5", "V1", "NOBODY", "COD", "L5",
                new BigDecimal("1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void weightPrecisionBeyondScaleIsRejected() {
        accountService.createAccount("L6", "COD", "A", new BigDecimal("10"));
        assertThatThrownBy(() -> landingService.declare("EVT-6", "V1", "A", "COD", "L6",
                new BigDecimal("1.0001")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
