package com.chris64233.cc.fisheries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.cc.fisheries.common.ApiException;
import com.chris64233.cc.fisheries.landing.LandingRecord;
import com.chris64233.cc.fisheries.landing.LandingRecordRepository;
import com.chris64233.cc.fisheries.landing.LandingService;
import com.chris64233.cc.fisheries.quota.LedgerEntry;
import com.chris64233.cc.fisheries.quota.LedgerEntryRepository;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;
import com.chris64233.cc.fisheries.quota.QuotaAccountService;
import com.chris64233.cc.fisheries.transfer.Transfer;
import com.chris64233.cc.fisheries.transfer.TransferService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class QuotaTransferLandingServiceTests {

    private static final String SEASON = "2026-S1";
    private static final String SPECIES = "SALMON";

    @Autowired
    private QuotaAccountService accountService;
    @Autowired
    private TransferService transferService;
    @Autowired
    private LandingService landingService;
    @Autowired
    private QuotaAccountRepository accountRepository;
    @Autowired
    private LedgerEntryRepository ledgerRepository;
    @Autowired
    private LandingRecordRepository landingRepository;

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private QuotaAccount account(String holder) {
        return accountRepository.findBySeasonAndSpeciesAndHolder(SEASON, SPECIES, holder).orElseThrow();
    }

    private long ledgerCount(Long accountId, LedgerEntry.Type type) {
        return ledgerRepository.findByAccountIdOrderByIdAsc(accountId).stream()
                .filter(entry -> entry.getType() == type)
                .count();
    }

    @Test
    void accountIsUniqueBySeasonSpeciesHolderAndUsesFixedScale() {
        QuotaAccount account = accountService.createAccount(SEASON, SPECIES, "A01", bd("100"));
        assertThat(account.getAvailable()).isEqualByComparingTo("100.000");

        assertThatThrownBy(() -> accountService.createAccount(SEASON, SPECIES, "A01", bd("5")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus().value())
                .isEqualTo(409);
    }

    @Test
    void quantityMustNotUseMoreThanThreeDecimals() {
        assertThatThrownBy(() -> accountService.createAccount(SEASON, SPECIES, "A02", bd("1.0001")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("3 位小数");
    }

    @Test
    void acceptMovesQuotaAtomicallyBetweenParties() {
        QuotaAccount alice = accountService.createAccount(SEASON, SPECIES, "ALICE", bd("100"));

        Transfer transfer = transferService.initiate(SEASON, SPECIES, "ALICE", "BOB", bd("30"), null);
        alice = account("ALICE");
        assertThat(alice.getAvailable()).isEqualByComparingTo("70");
        assertThat(alice.getTransferFrozen()).isEqualByComparingTo("30");

        transferService.accept(transfer.getId(), "BOB");
        alice = account("ALICE");
        QuotaAccount bob = account("BOB");
        assertThat(alice.getAvailable()).isEqualByComparingTo("70");
        assertThat(alice.getTransferFrozen()).isEqualByComparingTo("0");
        assertThat(bob.getAvailable()).isEqualByComparingTo("30");

        assertThat(ledgerCount(alice.getId(), LedgerEntry.Type.TRANSFER_FREEZE)).isEqualTo(1);
        assertThat(ledgerCount(alice.getId(), LedgerEntry.Type.TRANSFER_OUT)).isEqualTo(1);
        assertThat(ledgerCount(bob.getId(), LedgerEntry.Type.TRANSFER_IN)).isEqualTo(1);

        // 已终结的转让不能再接受
        assertThatThrownBy(() -> transferService.accept(transfer.getId(), "BOB"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectReleasesFrozenExactlyOnce() {
        QuotaAccount alice = accountService.createAccount(SEASON, SPECIES, "CARL", bd("100"));
        Transfer transfer = transferService.initiate(SEASON, SPECIES, "CARL", "DORA", bd("40"), null);

        transferService.reject(transfer.getId(), "DORA");
        assertThat(account("CARL").getAvailable()).isEqualByComparingTo("100");
        assertThat(account("CARL").getTransferFrozen()).isEqualByComparingTo("0");
        assertThat(ledgerCount(alice.getId(), LedgerEntry.Type.TRANSFER_RELEASE)).isEqualTo(1);

        assertThatThrownBy(() -> transferService.reject(transfer.getId(), "DORA"))
                .isInstanceOf(ApiException.class);
        assertThat(ledgerCount(alice.getId(), LedgerEntry.Type.TRANSFER_RELEASE)).isEqualTo(1);
    }

    @Test
    void expireReleasesFrozenExactlyOnceAndIsIdempotent() throws InterruptedException {
        QuotaAccount alice = accountService.createAccount(SEASON, SPECIES, "EVAN", bd("100"));
        Transfer transfer = transferService.initiate(SEASON, SPECIES, "EVAN", "FIONA", bd("25"), 1L);

        assertThatThrownBy(() -> transferService.expire(transfer.getId()))
                .isInstanceOf(ApiException.class);

        Thread.sleep(1100);
        transferService.expire(transfer.getId());
        transferService.expire(transfer.getId());

        assertThat(account("EVAN").getAvailable()).isEqualByComparingTo("100");
        assertThat(account("EVAN").getTransferFrozen()).isEqualByComparingTo("0");
        assertThat(ledgerCount(alice.getId(), LedgerEntry.Type.TRANSFER_RELEASE)).isEqualTo(1);

        // 到期后接受会执行一次释放，终结状态不重复处理
        assertThatThrownBy(() -> transferService.accept(transfer.getId(), "FIONA"))
                .isInstanceOf(ApiException.class);
        assertThat(ledgerCount(alice.getId(), LedgerEntry.Type.TRANSFER_RELEASE)).isEqualTo(1);
    }

    @Test
    void cannotFreezeMoreThanAvailable() {
        QuotaAccount alice = accountService.createAccount(SEASON, SPECIES, "GARY", bd("10"));

        assertThatThrownBy(() -> transferService.initiate(SEASON, SPECIES, "GARY", "HELEN", bd("10.001"), null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> transferService.initiate(SEASON, SPECIES, "GARY", "HELEN", bd("11"), null))
                .isInstanceOf(ApiException.class);

        // 失败请求不写台账、不改变余额
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(alice.getId()))
                .extracting(LedgerEntry::getType)
                .containsExactly(LedgerEntry.Type.APPROVE);
        assertThat(account("GARY").getAvailable()).isEqualByComparingTo("10");
    }

    @Test
    void cannotTransferAcrossUnknownSeasonOrSpecies() {
        accountService.createAccount(SEASON, SPECIES, "IVAN", bd("10"));
        assertThatThrownBy(() -> transferService.initiate("2027-S1", SPECIES, "IVAN", "JUDY", bd("1"), null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> transferService.initiate(SEASON, "TUNA", "IVAN", "JUDY", bd("1"), null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void onlyReceiverMayAccept() {
        accountService.createAccount(SEASON, SPECIES, "KARL", bd("10"));
        Transfer transfer = transferService.initiate(SEASON, SPECIES, "KARL", "LUCY", bd("5"), null);
        assertThatThrownBy(() -> transferService.accept(transfer.getId(), "KARL"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void landingConsumesAvailableQuotaAndIsIdempotent() {
        QuotaAccount alice = accountService.createAccount(SEASON, SPECIES, "MIA", bd("100"));

        LandingService.LandingResult first = landingService.declare("EVT-1", "VESSEL-1", "MIA", SPECIES, SEASON, bd("20.5"));
        assertThat(first.replayed()).isFalse();
        assertThat(account("MIA").getAvailable()).isEqualByComparingTo("79.500");
        assertThat(account("MIA").getConsumed()).isEqualByComparingTo("20.500");

        // 相同事件相同内容重放不重复核销
        LandingService.LandingResult replay = landingService.declare("EVT-1", "VESSEL-1", "MIA", SPECIES, SEASON, bd("20.50"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.record().getId()).isEqualTo(first.record().getId());
        assertThat(account("MIA").getConsumed()).isEqualByComparingTo("20.500");
        assertThat(ledgerCount(alice.getId(), LedgerEntry.Type.LANDING_CONSUME)).isEqualTo(1);

        // 相同事件不同内容冲突
        assertThatThrownBy(() -> landingService.declare("EVT-1", "VESSEL-2", "MIA", SPECIES, SEASON, bd("20.5")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> landingService.declare("EVT-1", "VESSEL-1", "MIA", SPECIES, SEASON, bd("21")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void landingOverAvailableQuotaFailsWithoutLedgerOrRecord() {
        QuotaAccount alice = accountService.createAccount(SEASON, SPECIES, "NORA", bd("5"));

        assertThatThrownBy(() -> landingService.declare("EVT-X", "V", "NORA", SPECIES, SEASON, bd("6")))
                .isInstanceOf(ApiException.class);

        assertThat(account("NORA").getAvailable()).isEqualByComparingTo("5");
        assertThat(account("NORA").getConsumed()).isEqualByComparingTo("0");
        assertThat(ledgerCount(alice.getId(), LedgerEntry.Type.LANDING_CONSUME)).isZero();
        assertThat(landingRepository.findByEventId("EVT-X")).isEmpty();
    }

    @Test
    void ledgerSnapshotsReflectEveryBalanceChange() {
        QuotaAccount alice = accountService.createAccount(SEASON, SPECIES, "OLGA", bd("100"));
        Transfer transfer = transferService.initiate(SEASON, SPECIES, "OLGA", "PATTY", bd("30"), null);
        transferService.accept(transfer.getId(), "PATTY");
        landingService.declare("EVT-L", "V", "PATTY", SPECIES, SEASON, bd("10"));

        QuotaAccount patty = account("PATTY");
        var pattyLedger = ledgerRepository.findByAccountIdOrderByIdAsc(patty.getId());
        assertThat(pattyLedger).extracting(LedgerEntry::getType)
                .containsExactly(LedgerEntry.Type.TRANSFER_IN, LedgerEntry.Type.LANDING_CONSUME);
        LedgerEntry consume = pattyLedger.get(1);
        assertThat(consume.getAvailableAfter()).isEqualByComparingTo("20");
        assertThat(consume.getConsumedAfter()).isEqualByComparingTo("10");
        assertThat(consume.getReferenceType()).isEqualTo("LANDING");
        assertThat(consume.getReferenceId()).isEqualTo(landingRepository.findByEventId("EVT-L").map(LandingRecord::getId).orElseThrow());
    }
}
