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
 * 并发场景：接受、释放、卸港同时发生时，三个余额必须守恒，
 * 不允许超转、超捕或重复释放。
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
    void concurrentLandingsNeverOverConsume() throws Exception {
        QuotaAccount account = accountService.createAccount("C1", "COD", "A", new BigDecimal("100"));
        int threads = 20;
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(threads, i -> {
            try {
                landingService.declare("C1-EVT-" + i, "V" + i, "A", "COD", "C1", new BigDecimal("10"));
                succeeded.incrementAndGet();
            } catch (BusinessException expected) {
                // 配额不足，核销失败
            }
        });

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(after.getAvailable()).isEqualByComparingTo("0");
        assertThat(after.getConsumed()).isEqualByComparingTo("100");
        assertThat(after.total()).isEqualByComparingTo("100");
        assertThat(ledgerRepository.countByAccountIdAndType(account.getId(), LedgerEventType.LANDING_DEDUCT))
                .isEqualTo(10);
    }

    @Test
    void concurrentDuplicateEventDeductsOnlyOnce() throws Exception {
        QuotaAccount account = accountService.createAccount("C2", "COD", "A", new BigDecimal("100"));
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(10, i -> {
            landingService.declare("C2-EVT", "V1", "A", "COD", "C2", new BigDecimal("10"));
            succeeded.incrementAndGet();
        });

        QuotaAccount after = accountService.getAccount(account.getId());
        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(after.getConsumed()).isEqualByComparingTo("10");
        assertThat(after.getAvailable()).isEqualByComparingTo("90");
        assertThat(ledgerRepository.countByAccountIdAndType(account.getId(), LedgerEventType.LANDING_DEDUCT))
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
                // 配额不足时核销失败属正常竞争结果
            }
        });

        QuotaAccount fromAfter = accountService.getAccount(from.getId());
        QuotaAccount toAfter = accountService.getAccount(to.getId());
        assertThat(transferService.getTransfer(transfer.getId()).getStatus()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(fromAfter.getFrozen()).isEqualByComparingTo("0");
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
