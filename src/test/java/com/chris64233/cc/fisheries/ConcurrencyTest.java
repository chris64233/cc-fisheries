package com.chris64233.cc.fisheries;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.landing.LandingService;
import com.chris64233.cc.fisheries.quota.LedgerEventType;
import com.chris64233.cc.fisheries.quota.LedgerEventRepository;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;
import com.chris64233.cc.fisheries.quota.QuotaAccountService;
import com.chris64233.cc.fisheries.transfer.Transfer;
import com.chris64233.cc.fisheries.transfer.TransferService;
import com.chris64233.cc.fisheries.transfer.TransferStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发场景：转让接受、卸港复核确认、更正确认同时发生时，
 * 可用/冻结/已核销三量必须守恒，任何时刻账户不得为负，
 * 不重复核销、不重复归还、不重复释放。
 */
@SpringBootTest
class ConcurrencyTest {

    @Autowired
    QuotaAccountService accountService;

    @Autowired
    TransferService transferService;

    @Autowired
    LandingService landingService;

    @Autowired
    QuotaAccountRepository accountRepository;

    @Autowired
    LedgerEventRepository ledgerRepository;

    @Test
    void concurrentDeclaresNeverOverFreeze() throws Exception {
        QuotaAccount account = accountService.createAccount("C1", "COD", "A", new BigDecimal("100"));
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(20, i -> {
            try {
                landingService.declare("C1-EVT-" + i, "V" + i, "A", "COD", "C1", new BigDecimal("10"));
                succeeded.incrementAndGet();
            } catch (BusinessException expected) {
                // 可用配额不足，冻结失败
            }
        });

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(after.getAvailable()).isEqualByComparingTo("0");
        assertThat(after.getFrozen()).isEqualByComparingTo("100");
        assertThat(after.getConsumed()).isEqualByComparingTo("0");
        assertThat(after.total()).isEqualByComparingTo("100");
        assertThat(ledgerRepository.countByAccountIdAndType(account.getId(), LedgerEventType.LANDING_FREEZE))
                .isEqualTo(10);
    }

    @Test
    void concurrentDuplicateEventFreezesOnlyOnce() throws Exception {
        QuotaAccount account = accountService.createAccount("C2", "COD", "A", new BigDecimal("100"));
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(10, i -> {
            landingService.declare("C2-EVT", "V1", "A", "COD", "C2", new BigDecimal("10"));
            succeeded.incrementAndGet();
        });

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(after.getFrozen()).isEqualByComparingTo("10");
        assertThat(after.getAvailable()).isEqualByComparingTo("90");
        assertThat(ledgerRepository.countByAccountIdAndType(account.getId(), LedgerEventType.LANDING_FREEZE))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateReviewConfirmsSettleOnlyOnce() throws Exception {
        QuotaAccount account = accountService.createAccount("C6", "COD", "A", new BigDecimal("100"));
        landingService.declare("C6-EVT", "V1", "A", "COD", "C6", new BigDecimal("10"));
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(10, i -> {
            landingService.confirmReview("C6-REV", "C6-EVT", "PORT-1", new BigDecimal("10"));
            succeeded.incrementAndGet();
        });

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("10");
        assertThat(after.getAvailable()).isEqualByComparingTo("90");
        assertThat(ledgerRepository.countByAccountIdAndType(account.getId(), LedgerEventType.LANDING_SETTLE))
                .isEqualTo(1);
    }

    @Test
    void concurrentReleaseAndExpireReleaseOnlyOnce() throws Exception {
        QuotaAccount account = accountService.createAccount("C3", "COD", "A", new BigDecimal("50"));
        accountService.createAccount("C3", "COD", "B", new BigDecimal("1"));
        Transfer transfer = transferService.initiate("C3", "COD", "A", "B", new BigDecimal("20"),
                Duration.ofMillis(1));
        Thread.sleep(50);

        AtomicInteger releases = new AtomicInteger();
        runConcurrently(12, i -> {
            try {
                if (i % 2 == 0) {
                    transferService.reject(transfer.getId());
                    releases.incrementAndGet();
                } else {
                    if (transferService.expireOne(transfer.getId())) {
                        releases.incrementAndGet();
                    }
                }
            } catch (BusinessException expected) {
                // 已终结的转让不能再次释放
            }
        });

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(releases.get()).isEqualTo(1);
        assertThat(after.getAvailable()).isEqualByComparingTo("50");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(ledgerRepository.countByAccountIdAndType(account.getId(), LedgerEventType.TRANSFER_RELEASE))
                .isEqualTo(1);
        assertThat(transferService.getTransfer(transfer.getId()).getStatus())
                .isIn(TransferStatus.REJECTED, TransferStatus.EXPIRED);
    }

    @Test
    void concurrentAcceptAndLandingConserveBalances() throws Exception {
        QuotaAccount from = accountService.createAccount("C4", "COD", "A", new BigDecimal("100"));
        QuotaAccount to = accountService.createAccount("C4", "COD", "B", new BigDecimal("0.001"));
        Transfer transfer = transferService.initiate("C4", "COD", "A", "B", new BigDecimal("40"), null);

        runConcurrently(11, i -> {
            try {
                if (i == 0) {
                    transferService.accept(transfer.getId());
                } else {
                    landingService.declare("C4-EVT-" + i, "V" + i, "A", "COD", "C4", new BigDecimal("10"));
                }
            } catch (BusinessException expected) {
                // 配额不足时冻结失败属正常竞争结果
            }
        });

        QuotaAccount fromAfter = accountService.getAccount(from.getId());
        QuotaAccount toAfter = accountService.getAccount(to.getId());
        assertThat(transferService.getTransfer(transfer.getId()).getStatus()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(fromAfter.hasNegativeBalance()).isFalse();
        assertThat(toAfter.hasNegativeBalance()).isFalse();
        // 总量守恒：两个账户合计仍等于初始核准总量
        assertThat(fromAfter.total().add(toAfter.total())).isEqualByComparingTo("100.001");
        assertThat(toAfter.getAvailable()).isEqualByComparingTo("40.001");
    }

    @Test
    void concurrentInitiateNeverOverFreezes() throws Exception {
        QuotaAccount account = accountService.createAccount("C5", "COD", "A", new BigDecimal("100"));
        accountService.createAccount("C5", "COD", "B", new BigDecimal("1"));
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(10, i -> {
            try {
                transferService.initiate("C5", "COD", "A", "B", new BigDecimal("30"), null);
                succeeded.incrementAndGet();
            } catch (BusinessException expected) {
                // 可用不足，冻结失败
            }
        });

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(succeeded.get()).isEqualTo(3);
        assertThat(after.getFrozen()).isEqualByComparingTo("90");
        assertThat(after.getAvailable()).isEqualByComparingTo("10");
        assertThat(after.total()).isEqualByComparingTo("100");
    }

    /**
     * 需求 3：转让接受、卸港复核确认、更正确认并发。
     * 转让接受与复核确认必须成功；更正若先于账户变化完成则生效，
     * 否则作为旧决定被拒绝（账户版本已变化）。两种结果下账户都不得为负、三量守恒。
     */
    @Test
    void concurrentAcceptReviewAndCorrectionNeverNegativeAndConserved() throws Exception {
        QuotaAccount a = accountService.createAccount("C7", "COD", "A", new BigDecimal("100"));
        QuotaAccount b = accountService.createAccount("C7", "COD", "B", new BigDecimal("1"));
        // L1 已核销 30：avail 70, consumed 30
        landingService.declare("C7-L1", "V1", "A", "COD", "C7", new BigDecimal("30"));
        landingService.confirmReview("C7-R1", "C7-L1", "PORT-1", new BigDecimal("30"));
        // L2 待复核冻结 20：avail 50, frozen 20
        landingService.declare("C7-L2", "V2", "A", "COD", "C7", new BigDecimal("20"));
        // 转让冻结 25：avail 25, frozen 45
        Transfer transfer = transferService.initiate("C7", "COD", "A", "B", new BigDecimal("25"), null);
        // 更正 L1 30 → 35，确认时需追扣 5；与并发账户变更竞争
        landingService.createCorrection("C7-COR", "C7-L1", new BigDecimal("35"));

        AtomicInteger correctionConfirmed = new AtomicInteger();
        AtomicInteger correctionStale = new AtomicInteger();
        runConcurrently(3, i -> {
            if (i == 0) {
                transferService.accept(transfer.getId());
            } else if (i == 1) {
                // 复核确认增重到 30，需从可用追扣 10
                landingService.confirmReview("C7-R2", "C7-L2", "PORT-1", new BigDecimal("30"));
            } else {
                try {
                    landingService.confirmCorrection("C7-COR");
                    correctionConfirmed.incrementAndGet();
                } catch (BusinessException e) {
                    // 账户版本已被并发的接受/复核推进：旧决定被拒绝
                    correctionStale.incrementAndGet();
                }
            }
        });

        QuotaAccount aAfter = accountService.getAccount(a.getId());
        QuotaAccount bAfter = accountService.getAccount(b.getId());
        assertThat(correctionConfirmed.get() + correctionStale.get()).isEqualTo(1);
        assertThat(aAfter.hasNegativeBalance()).isFalse();
        assertThat(bAfter.hasNegativeBalance()).isFalse();
        // 转让接受与复核确认必然生效：avail 25-10=15，若更正先生效则再 -5
        assertThat(aAfter.getAvailable()).isIn(new BigDecimal("15.000"), new BigDecimal("10.000"));
        assertThat(aAfter.getConsumed()).isIn(new BigDecimal("60.000"), new BigDecimal("65.000"));
        assertThat(aAfter.getFrozen()).isEqualByComparingTo("0");
        assertThat(aAfter.total()).isEqualByComparingTo("75");
        assertThat(bAfter.getAvailable()).isEqualByComparingTo("26");
        assertThat(aAfter.total().add(bAfter.total())).isEqualByComparingTo("101");
        // 更正生效与台账严格一致：生效则有且仅有一条 CORRECTION_DEDUCT
        long correctionLedger = ledgerRepository.findByAccountIdOrderByIdAsc(a.getId()).stream()
                .filter(e -> e.getType() == LedgerEventType.CORRECTION_DEDUCT).count();
        assertThat(correctionLedger).isEqualTo(correctionConfirmed.get());
        assertThat(landingService.getCorrection("C7-COR").getStatus().name())
                .isEqualTo(correctionConfirmed.get() == 1 ? "CONFIRMED" : "PENDING");
    }

    /**
     * 需求 3：可用量不足时，并发的卸港确认与更正确认必须失败且账户不为负，
     * 失败不写部分差额台账；转让接受不受影响。
     */
    @Test
    void concurrentConfirmationsBeyondAvailableFailWithoutPartialLedger() throws Exception {
        QuotaAccount a = accountService.createAccount("C8", "COD", "A", new BigDecimal("100"));
        QuotaAccount b = accountService.createAccount("C8", "COD", "B", new BigDecimal("1"));
        // L1 已核销 80：avail 20, consumed 80
        landingService.declare("C8-L1", "V1", "A", "COD", "C8", new BigDecimal("80"));
        landingService.confirmReview("C8-R1", "C8-L1", "PORT-1", new BigDecimal("80"));
        // L2 待复核冻结 10：avail 10, frozen 10
        landingService.declare("C8-L2", "V2", "A", "COD", "C8", new BigDecimal("10"));
        // 转让冻结 8：avail 2, frozen 18
        Transfer transfer = transferService.initiate("C8", "COD", "A", "B", new BigDecimal("8"), null);
        // 更正 L1 80 → 95，确认需追扣 15（可用只有 2，必失败）
        landingService.createCorrection("C8-COR", "C8-L1", new BigDecimal("95"));

        runConcurrently(3, i -> {
            try {
                if (i == 0) {
                    transferService.accept(transfer.getId());
                } else if (i == 1) {
                    // 复核确认增重到 15，需追扣 5（可用 2，必失败）
                    landingService.confirmReview("C8-R2", "C8-L2", "PORT-1", new BigDecimal("15"));
                } else {
                    landingService.confirmCorrection("C8-COR");
                }
            } catch (BusinessException expected) {
                // 可用不足：拒绝，且不写任何差额台账
            }
        });

        QuotaAccount aAfter = accountService.getAccount(a.getId());
        QuotaAccount bAfter = accountService.getAccount(b.getId());
        assertThat(aAfter.hasNegativeBalance()).isFalse();
        assertThat(bAfter.hasNegativeBalance()).isFalse();
        // 只有转让接受生效：avail 2, frozen 10(L2 仍待复核), consumed 80
        assertThat(aAfter.getAvailable()).isEqualByComparingTo("2");
        assertThat(aAfter.getFrozen()).isEqualByComparingTo("10");
        assertThat(aAfter.getConsumed()).isEqualByComparingTo("80");
        assertThat(aAfter.total().add(bAfter.total())).isEqualByComparingTo("101");
        // 失败的决定保持待处理，未写差额台账
        assertThat(landingService.getByEventId("C8-L2").getStatus().name()).isEqualTo("PENDING_REVIEW");
        assertThat(landingService.getCorrection("C8-COR").getStatus().name()).isEqualTo("PENDING");
        assertThat(ledgerRepository.countByAccountIdAndType(a.getId(), LedgerEventType.CORRECTION_DEDUCT))
                .isZero();
        assertThat(ledgerRepository.countByAccountIdAndType(a.getId(), LedgerEventType.REVIEW_CONSUME))
                .isZero();
    }

    /**
     * 需求 3：同一申报的两笔更正并发确认，基于同一版本，只有一笔能生效，
     * 另一笔作为旧决定被拒绝；账户不出现负余额或双重差额。
     */
    @Test
    void concurrentCorrectionConfirmsOnlyOneWins() throws Exception {
        QuotaAccount account = accountService.createAccount("C9", "COD", "A", new BigDecimal("100"));
        landingService.declare("C9-L1", "V1", "A", "COD", "C9", new BigDecimal("30"));
        landingService.confirmReview("C9-R1", "C9-L1", "PORT-1", new BigDecimal("30"));
        landingService.createCorrection("C9-COR-A", "C9-L1", new BigDecimal("40"));
        landingService.createCorrection("C9-COR-B", "C9-L1", new BigDecimal("25"));

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger staleRejected = new AtomicInteger();
        runConcurrently(2, i -> {
            try {
                landingService.confirmCorrection(i == 0 ? "C9-COR-A" : "C9-COR-B");
                succeeded.incrementAndGet();
            } catch (BusinessException e) {
                staleRejected.incrementAndGet();
            }
        });

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(staleRejected.get()).isEqualTo(1);
        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.hasNegativeBalance()).isFalse();
        // 只有一笔差额生效：已核销要么 40 要么 25
        assertThat(after.getConsumed()).isIn(new BigDecimal("40.000"), new BigDecimal("25.000"));
        assertThat(after.total()).isEqualByComparingTo("100");
        long correctionEvents = ledgerRepository.findByAccountIdOrderByIdAsc(account.getId()).stream()
                .filter(e -> e.getType() == LedgerEventType.CORRECTION_DEDUCT
                        || e.getType() == LedgerEventType.CORRECTION_RETURN)
                .count();
        assertThat(correctionEvents).isEqualTo(1);
    }

    private void runConcurrently(int threads, ThrowingTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                task.run(index);
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();
    }

    @FunctionalInterface
    interface ThrowingTask {
        void run(int index) throws Exception;
    }
}
