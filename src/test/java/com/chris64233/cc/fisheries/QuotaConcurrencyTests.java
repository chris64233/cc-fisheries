package com.chris64233.cc.fisheries;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;
import com.chris64233.cc.fisheries.quota.QuotaAccountService;
import com.chris64233.cc.fisheries.transfer.Transfer;
import com.chris64233.cc.fisheries.transfer.TransferService;
import com.chris64233.cc.fisheries.landing.LandingService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class QuotaConcurrencyTests {

    private static final String SEASON = "2026-S1";
    private static final String SPECIES = "TUNA";

    @Autowired
    private QuotaAccountService accountService;
    @Autowired
    private TransferService transferService;
    @Autowired
    private LandingService landingService;
    @Autowired
    private QuotaAccountRepository accountRepository;

    private static final class Outcome {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger failure = new AtomicInteger();
    }

    private void runConcurrently(int threads, Runnable action, Outcome outcome) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    action.run();
                    outcome.success.incrementAndGet();
                } catch (Throwable throwable) {
                    outcome.failure.incrementAndGet();
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private QuotaAccount account(String holder) {
        return accountRepository.findBySeasonAndSpeciesAndHolder(SEASON, SPECIES, holder).orElseThrow();
    }

    @Test
    void concurrentLandingsNeverOvercatchAndNeverDoubleConsume() throws Exception {
        accountService.createAccount(SEASON, SPECIES, "H-LAND", new BigDecimal("100"));
        Outcome outcome = new Outcome();
        int threads = 20;
        runConcurrently(threads, () ->
                landingService.declare("EVT-LAND-001", "V1", "H-LAND", SPECIES, SEASON, new BigDecimal("10")), outcome);

        // 同一事件号：只有一个请求真正核销，其余全部冲突或幂等重放，已核销数量只能是 10
        QuotaAccount holder = account("H-LAND");
        assertThat(holder.getConsumed()).isEqualByComparingTo("10");
        assertThat(holder.getAvailable()).isEqualByComparingTo("90");
        assertThat(outcome.success.get()).isEqualTo(threads);

        // 不同事件号争抢同一账户剩余额度：90 / 每次 10 => 恰好 9 次成功
        Outcome distinct = new Outcome();
        AtomicInteger seq = new AtomicInteger();
        runConcurrently(20, () -> {
            int index = seq.incrementAndGet();
            landingService.declare("EVT-DIST-" + index, "V1", "H-LAND", SPECIES, SEASON, new BigDecimal("10"));
        }, distinct);

        holder = account("H-LAND");
        assertThat(distinct.success.get()).isEqualTo(9);
        assertThat(distinct.failure.get()).isEqualTo(11);
        assertThat(holder.getAvailable()).isEqualByComparingTo("0");
        assertThat(holder.getConsumed()).isEqualByComparingTo("100");
        assertThat(holder.getTransferFrozen()).isEqualByComparingTo("0");
    }

    @Test
    void concurrentInitiationsNeverOverFreeze() throws Exception {
        accountService.createAccount(SEASON, SPECIES, "H-FREEZE", new BigDecimal("100"));
        Outcome outcome = new Outcome();
        AtomicInteger seq = new AtomicInteger();

        runConcurrently(20, () -> {
            int index = seq.incrementAndGet();
            transferService.initiate(SEASON, SPECIES, "H-FREEZE", "H-RECV-" + index,
                    new BigDecimal("10"), 3600L);
        }, outcome);

        QuotaAccount holder = account("H-FREEZE");
        assertThat(outcome.success.get()).isEqualTo(10);
        assertThat(outcome.failure.get()).isEqualTo(10);
        assertThat(holder.getAvailable()).isEqualByComparingTo("0");
        assertThat(holder.getTransferFrozen()).isEqualByComparingTo("100");
    }

    @Test
    void concurrentAcceptRejectAndExpireSettleExactlyOnce() throws Exception {
        accountService.createAccount(SEASON, SPECIES, "H-SEND", new BigDecimal("100"));
        Transfer transfer = transferService.initiate(SEASON, SPECIES, "H-SEND", "H-TARGET",
                new BigDecimal("30"), 3600L);

        Outcome outcome = new Outcome();
        runConcurrently(8, () -> {
            if (Math.random() < 0.5) {
                transferService.accept(transfer.getId(), "H-TARGET");
            } else {
                transferService.reject(transfer.getId(), "H-TARGET");
            }
        }, outcome);

        assertThat(outcome.success.get()).isEqualTo(1);
        assertThat(outcome.failure.get()).isEqualTo(7);

        QuotaAccount sender = account("H-SEND");
        Transfer finalState = transferService.getTransfer(transfer.getId());
        if (finalState.getStatus() == Transfer.Status.ACCEPTED) {
            assertThat(sender.getAvailable()).isEqualByComparingTo("70");
            assertThat(sender.getTransferFrozen()).isEqualByComparingTo("0");
            QuotaAccount receiver = account("H-TARGET");
            assertThat(receiver.getAvailable()).isEqualByComparingTo("30");
        } else {
            assertThat(finalState.getStatus()).isEqualTo(Transfer.Status.REJECTED);
            assertThat(sender.getAvailable()).isEqualByComparingTo("100");
            assertThat(sender.getTransferFrozen()).isEqualByComparingTo("0");
            assertThat(accountRepository.findBySeasonAndSpeciesAndHolder(SEASON, SPECIES, "H-TARGET")).isEmpty();
        }

        // 已到期转让的并发释放只能发生一次
        accountService.createAccount(SEASON, SPECIES, "H-SEND2", new BigDecimal("100"));
        Transfer expired = transferService.initiate(SEASON, SPECIES, "H-SEND2", "H-TARGET2",
                new BigDecimal("30"), 1L);
        Thread.sleep(1100);
        Outcome expireOutcome = new Outcome();
        runConcurrently(8, () -> transferService.expire(expired.getId()), expireOutcome);

        QuotaAccount sender2 = account("H-SEND2");
        assertThat(sender2.getAvailable()).isEqualByComparingTo("100");
        assertThat(sender2.getTransferFrozen()).isEqualByComparingTo("0");
        assertThat(transferService.getTransfer(expired.getId()).getStatus()).isEqualTo(Transfer.Status.EXPIRED);
    }

    @Test
    void concurrentMixedOperationsConserveTotalQuota() throws Exception {
        accountService.createAccount(SEASON, SPECIES, "H-MIX-A", new BigDecimal("100"));
        accountService.createAccount(SEASON, SPECIES, "H-MIX-B", new BigDecimal("100"));

        List<Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            transfers.add(transferService.initiate(SEASON, SPECIES, "H-MIX-A", "H-MIX-B",
                    new BigDecimal("10"), 3600L));
        }

        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Transfer transfer : transfers) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    transferService.accept(transfer.getId(), "H-MIX-B");
                } catch (Throwable ignored) {
                }
            }));
        }
        for (int i = 0; i < 7; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    landingService.declare("EVT-MIX-" + index, "V", "H-MIX-B", SPECIES, SEASON,
                            new BigDecimal("10"));
                } catch (Throwable ignored) {
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        QuotaAccount a = account("H-MIX-A");
        QuotaAccount b = account("H-MIX-B");
        BigDecimal total = a.getAvailable().add(a.getTransferFrozen()).add(a.getConsumed())
                .add(b.getAvailable()).add(b.getTransferFrozen()).add(b.getConsumed());
        assertThat(total).isEqualByComparingTo("200");
        assertThat(a.getTransferFrozen()).isEqualByComparingTo("0");
        assertThat(b.getTransferFrozen()).isEqualByComparingTo("0");
    }
}
