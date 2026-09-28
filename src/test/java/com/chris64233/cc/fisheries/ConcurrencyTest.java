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
import com.chris64233.cc.fisheries.correction.CorrectionService;
import com.chris64233.cc.fisheries.hold.HoldStatus;
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
 * 并发场景：转让接受、卸港申报/确认、更正确认同时发生时，三个余额必须守恒，
 * 任何时刻账户不得为负，且不允许超转、超捕或重复核销/归还。
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
    CorrectionService correctionService;
    @Autowired
    QuotaAccountRepository accountRepository;
    @Autowired
    LedgerEventRepository ledgerRepository;

    @Test
    void concurrentDeclaresNeverOverFreeze() throws Exception {
        QuotaAccount account = accountService.createAccount("C1", "COD", "A", new BigDecimal("100"));
        int threads = 20;
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(threads, i -> {
            try {
                landingService.declare("C1-EVT-" + i, "V" + i, "A", "COD", "C1", new BigDecimal("10"));
                succeeded.incrementAndGet();
            } catch (BusinessException expected) {
                // 可用不足，冻结失败
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
        assertHeldHoldsEqualFrozen(account.getId());
    }

    @Test
    void concurrentDuplicateDeclareFreezesOnlyOnce() throws Exception {
        QuotaAccount account = accountService.createAccount("C2", "COD", "A", new BigDecimal("100"));
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(10, i -> {
            landingService.declare("C2-EVT", "V1", "A", "COD", "C2", new BigDecimal("10"));
            succeeded.incrementAndGet();
        });

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(after.getConsumed()).isEqualByComparingTo("0");
        assertThat(after.getFrozen()).isEqualByComparingTo("10");
        assertThat(after.getAvailable()).isEqualByComparingTo("90");
        assertThat(ledgerRepository.countByAccountIdAndType(account.getId(), LedgerEventType.LANDING_FREEZE))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateLandingReviewSettlesOnlyOnce() throws Exception {
        QuotaAccount account = accountService.createAccount("C2B", "COD", "A", new BigDecimal("100"));
        landingService.declare("C2B-EVT", "V1", "A", "COD", "C2B", new BigDecimal("10"));

        runConcurrently(12, i -> landingService.confirm("C2B-EVT", "C2B-REV", "port-1"));

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("10");
        assertThat(ledgerRepository.countByAccountIdAndType(account.getId(), LedgerEventType.LANDING_CONSUME))
                .isEqualTo(1);
    }

    @Test
    void concurrentReleaseAndExpireReleaseOnlyOnce() throws Exception {
        QuotaAccount account = accountService.createAccount("C3", "COD", "A", new BigDecimal("50"));
        accountService.createAccount("C3", "COD", "B", new BigDecimal("1"));
        Transfer transfer = transferService.initiate("REQ-C3", "C3", "COD", "A", "B",
                new BigDecimal("20"), Duration.ofMillis(1));
        Thread.sleep(50);

        AtomicInteger releases = new AtomicInteger();
        runConcurrently(12, i -> {
            try {
                if (i % 2 == 0) {
                    transferService.reject(transfer.getId(), "REJ-C3-" + i, "B");
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
        assertHeldHoldsEqualFrozen(account.getId());
        assertThat(transferService.getTransfer(transfer.getId()).getStatus())
                .isIn(TransferStatus.REJECTED, TransferStatus.EXPIRED);
    }

    @Test
    void concurrentAcceptLandingConfirmAndCorrectionConfirmNeverGoNegative() throws Exception {
        // A 初始 110：转让冻结 40、卸港待复核冻结 30、增重更正冻结 20，剩可用 20（确认基础卸港后）
        QuotaAccount a = accountService.createAccount("C4", "COD", "A", new BigDecimal("110"));
        QuotaAccount b = accountService.createAccount("C4", "COD", "B", new BigDecimal("0.001"));
        Transfer transfer = transferService.initiate("REQ-C4", "C4", "COD", "A", "B",
                new BigDecimal("40"), null);
        landingService.declare("C4-LAND", "V1", "A", "COD", "C4", new BigDecimal("30"));
        // 先有一笔已核销申报 20，再挂一笔增重 +20 的待确认更正
        landingService.declare("C4-BASE", "V1", "A", "COD", "C4", new BigDecimal("20"));
        landingService.confirm("C4-BASE", "C4-BASE-REV", "port-1");
        correctionService.create("C4-CORR", "C4-BASE", new BigDecimal("40"));

        AtomicInteger correctionRejected = new AtomicInteger();
        runConcurrently(3, i -> {
            try {
                switch (i) {
                    case 0 -> transferService.accept(transfer.getId(), "ACC-C4", "B");
                    case 1 -> landingService.confirm("C4-LAND", "C4-LAND-REV", "port-1");
                    default -> correctionService.confirm("C4-CORR", "C4-CORR-REV", "port-2");
                }
            } catch (BusinessException ex) {
                // 更正确认时账户版本已被其它两个决策推进：旧决定必须被拒绝，且不写入部分差额
                correctionRejected.incrementAndGet();
            }
        });

        QuotaAccount aAfter = accountService.getAccount(a.getId());
        QuotaAccount bAfter = accountService.getAccount(b.getId());
        assertThat(aAfter.hasNegativeBalance()).isFalse();
        assertThat(bAfter.hasNegativeBalance()).isFalse();
        // 转让接受、卸港确认不依赖账户版本，必然成功
        assertThat(transferService.getTransfer(transfer.getId()).getStatus()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(landingService.getByEventId("C4-LAND").getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(bAfter.getAvailable()).isEqualByComparingTo("40.001");

        long correctionConsumeEvents = ledgerRepository.findByAccountIdAndReferenceOrderByIdAsc(
                        a.getId(), "C4-CORR").stream()
                .filter(e -> e.getType() == LedgerEventType.CORRECTION_CONSUME).count();
        if (correctionRejected.get() > 0) {
            // 更正旧决定被拒：保持 PENDING，冻结仍在，差额未核销、未归还
            assertThat(correctionService.getByCorrectionId("C4-CORR").getStatus().name())
                    .isEqualTo("PENDING");
            assertThat(correctionConsumeEvents).isZero();
            // A：可用 0、更正冻结 20、已核销 50（基础20+卸港30）
            assertThat(aAfter.getFrozen()).isEqualByComparingTo("20");
            assertThat(aAfter.getConsumed()).isEqualByComparingTo("50");
        } else {
            // 更正抢到锁先执行：全部结算
            assertThat(correctionService.getByCorrectionId("C4-CORR").getStatus().name())
                    .isEqualTo("CONFIRMED");
            assertThat(aAfter.getFrozen()).isEqualByComparingTo("0");
            assertThat(aAfter.getConsumed()).isEqualByComparingTo("70");
        }
        // 任何顺序下总量守恒：两账户合计仍为初始核准总量
        assertThat(aAfter.total().add(bAfter.total())).isEqualByComparingTo("110.001");
        assertHeldHoldsEqualFrozen(a.getId());
    }

    @Test
    void concurrentAcceptAndDeclareCompeteForAvailableWithoutGoingNegative() throws Exception {
        QuotaAccount from = accountService.createAccount("C6", "COD", "A", new BigDecimal("100"));
        accountService.createAccount("C6", "COD", "B", new BigDecimal("0.001"));
        Transfer transfer = transferService.initiate("REQ-C6", "C6", "COD", "A", "B",
                new BigDecimal("40"), null);

        AtomicInteger declared = new AtomicInteger();
        runConcurrently(11, i -> {
            try {
                if (i == 0) {
                    transferService.accept(transfer.getId(), "ACC-C6", "B");
                } else {
                    landingService.declare("C6-EVT-" + i, "V" + i, "A", "COD", "C6", new BigDecimal("10"));
                    declared.incrementAndGet();
                }
            } catch (BusinessException expected) {
                // 可用不足时冻结失败属正常竞争结果
            }
        });

        QuotaAccount fromAfter = accountService.getAccount(from.getId());
        assertThat(transferService.getTransfer(transfer.getId()).getStatus()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(fromAfter.hasNegativeBalance()).isFalse();
        // 接受后 A 总量 60（40 已转出），最多再冻结 6 笔 10
        assertThat(declared.get()).isLessThanOrEqualTo(6);
        assertThat(fromAfter.getAvailable())
                .isEqualByComparingTo(new BigDecimal("60").subtract(new BigDecimal(declared.get() * 10)));
        assertThat(fromAfter.getFrozen()).isEqualByComparingTo(new BigDecimal(declared.get() * 10));
        assertThat(fromAfter.total()).isEqualByComparingTo("60");
        assertHeldHoldsEqualFrozen(from.getId());
    }

    @Test
    void concurrentInitiateNeverOverFreezes() throws Exception {
        QuotaAccount account = accountService.createAccount("C5", "COD", "A", new BigDecimal("100"));
        accountService.createAccount("C5", "COD", "B", new BigDecimal("1"));
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(10, i -> {
            try {
                transferService.initiate("REQ-C5-" + i, "C5", "COD", "A", "B", new BigDecimal("30"), null);
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
        assertHeldHoldsEqualFrozen(account.getId());
    }

    @Test
    void concurrentDuplicateInitiateFreezesOnlyOnce() throws Exception {
        QuotaAccount account = accountService.createAccount("C7", "COD", "A", new BigDecimal("100"));
        accountService.createAccount("C7", "COD", "B", new BigDecimal("1"));
        AtomicInteger done = new AtomicInteger();
        java.util.Set<Long> transferIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        runConcurrently(12, i -> {
            Transfer t = transferService.initiate("REQ-C7-DUP", "C7", "COD", "A", "B",
                    new BigDecimal("30"), null);
            transferIds.add(t.getId());
            done.incrementAndGet();
        });

        // 所有重放返回同一笔转让，只冻结一次 30
        assertThat(done.get()).isEqualTo(12);
        assertThat(transferIds).hasSize(1);
        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(after.getFrozen()).isEqualByComparingTo("30");
        assertThat(after.getAvailable()).isEqualByComparingTo("70");
        assertThat(ledgerRepository.countByAccountIdAndType(account.getId(), LedgerEventType.TRANSFER_FREEZE))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateAcceptSettlesOnlyOnce() throws Exception {
        QuotaAccount from = accountService.createAccount("C8", "COD", "A", new BigDecimal("100"));
        QuotaAccount to = accountService.createAccount("C8", "COD", "B", new BigDecimal("5"));
        Transfer transfer = transferService.initiate("REQ-C8", "C8", "COD", "A", "B",
                new BigDecimal("40"), null);

        runConcurrently(12, i -> transferService.accept(transfer.getId(), "ACC-C8-DUP", "B"));

        QuotaAccount fromAfter = accountService.getAccount(from.getId());
        QuotaAccount toAfter = accountService.getAccount(to.getId());
        assertThat(fromAfter.getAvailable()).isEqualByComparingTo("60");
        assertThat(fromAfter.getFrozen()).isEqualByComparingTo("0");
        assertThat(toAfter.getAvailable()).isEqualByComparingTo("45");
        assertThat(ledgerRepository.countByAccountIdAndType(from.getId(), LedgerEventType.TRANSFER_OUT))
                .isEqualTo(1);
        assertThat(ledgerRepository.countByAccountIdAndType(to.getId(), LedgerEventType.TRANSFER_IN))
                .isEqualTo(1);
        // 双方台账与转让单据相互对应
        assertThat(transferService.getTransferView(transfer.getId()).balancesCorrespond()).isTrue();
    }

    private void assertHeldHoldsEqualFrozen(Long accountId) {
        QuotaAccount snapshot = accountService.getAccount(accountId);
        BigDecimal heldSum = accountService.getHolds(accountId, true).stream()
                .map(h -> h.getQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(heldSum).isEqualByComparingTo(snapshot.getFrozen());
        // 所有持有中明细状态正确
        assertThat(accountService.getHolds(accountId, true))
                .allMatch(h -> h.getStatus() == HoldStatus.HELD);
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
