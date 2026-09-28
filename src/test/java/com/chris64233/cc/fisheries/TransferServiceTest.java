package com.chris64233.cc.fisheries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.quota.LedgerEvent;
import com.chris64233.cc.fisheries.quota.LedgerEventType;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountService;
import com.chris64233.cc.fisheries.landing.LandingService;
import com.chris64233.cc.fisheries.transfer.Transfer;
import com.chris64233.cc.fisheries.transfer.TransferService;
import com.chris64233.cc.fisheries.transfer.TransferStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TransferServiceTest {

    @Autowired
    QuotaAccountService accountService;

    @Autowired
    TransferService transferService;

    @Autowired
    LandingService landingService;

    private static BigDecimal qty(String value) {
        return new BigDecimal(value);
    }

    private Transfer newTransfer(String season, String from, String to, String amount) {
        return transferService.initiate("REQ-" + season + "-" + from + to, season, "COD", from, to,
                qty(amount), null);
    }

    @Test
    void initiateFreezesAvailableAndWritesLedger() {
        QuotaAccount from = accountService.createAccount("S1", "COD", "A", qty("100"));

        Transfer transfer = newTransfer("S1", "A", "B", "30");

        QuotaAccount reloaded = accountService.getAccount(from.getId());
        assertThat(reloaded.getAvailable()).isEqualByComparingTo("70");
        assertThat(reloaded.getFrozen()).isEqualByComparingTo("30");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getRequestId()).isEqualTo("REQ-S1-AB");

        List<LedgerEvent> ledger = accountService.getLedger(from.getId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(1).getType()).isEqualTo(LedgerEventType.TRANSFER_FREEZE);
        assertThat(ledger.get(1).getQuantity()).isEqualByComparingTo("30");
        assertThat(ledger.get(1).getReference()).isEqualTo(transfer.getId().toString());
    }

    @Test
    void initiateFailsWhenInsufficientAndWritesNoLedger() {
        QuotaAccount from = accountService.createAccount("S2", "COD", "A", qty("10"));

        assertThatThrownBy(() -> newTransfer("S2", "A", "B", "20"))
                .isInstanceOf(BusinessException.class);

        QuotaAccount reloaded = accountService.getAccount(from.getId());
        assertThat(reloaded.getAvailable()).isEqualByComparingTo("10");
        assertThat(reloaded.getFrozen()).isEqualByComparingTo("0");
        assertThat(accountService.getLedger(from.getId())).hasSize(1);
    }

    @Test
    void acceptMovesQuantityToReceiverInOneTransaction() {
        QuotaAccount from = accountService.createAccount("S3", "COD", "A", qty("100"));
        QuotaAccount to = accountService.createAccount("S3", "COD", "B", qty("5"));
        Transfer transfer = newTransfer("S3", "A", "B", "40");

        Transfer accepted = transferService.accept(transfer.getId(), "ACC-S3", "B");

        assertThat(accepted.getStatus()).isEqualTo(TransferStatus.ACCEPTED);
        QuotaAccount fromAfter = accountService.getAccount(from.getId());
        QuotaAccount toAfter = accountService.getAccount(to.getId());
        assertThat(fromAfter.getAvailable()).isEqualByComparingTo("60");
        assertThat(fromAfter.getFrozen()).isEqualByComparingTo("0");
        assertThat(toAfter.getAvailable()).isEqualByComparingTo("45");

        List<LedgerEvent> fromLedger = accountService.getLedger(from.getId());
        assertThat(fromLedger).extracting(LedgerEvent::getType)
                .containsExactly(LedgerEventType.GRANT, LedgerEventType.TRANSFER_FREEZE,
                        LedgerEventType.TRANSFER_OUT);
        assertThat(accountService.getLedger(to.getId()))
                .extracting(LedgerEvent::getType)
                .containsExactly(LedgerEventType.GRANT, LedgerEventType.TRANSFER_IN);
    }

    @Test
    void acceptOnTerminatedTransferIsRejected() {
        accountService.createAccount("S4", "COD", "A", qty("50"));
        accountService.createAccount("S4", "COD", "B", qty("0.001"));
        Transfer transfer = newTransfer("S4", "A", "B", "10");
        transferService.reject(transfer.getId(), "REJ-S4", "B");

        assertThatThrownBy(() -> transferService.accept(transfer.getId(), "ACC-S4", "B"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已终结");
    }

    @Test
    void rejectReleasesFrozenExactlyOnce() {
        QuotaAccount from = accountService.createAccount("S5", "COD", "A", qty("80"));
        Transfer transfer = newTransfer("S5", "A", "B", "25");

        transferService.reject(transfer.getId(), "REJ-S5", "B");

        QuotaAccount after = accountService.getAccount(from.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("80");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> transferService.reject(transfer.getId(), "REJ-S5-2", "B"))
                .isInstanceOf(BusinessException.class);

        List<LedgerEvent> ledger = accountService.getLedger(from.getId());
        assertThat(ledger).filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_RELEASE).hasSize(1);
        assertThat(after.total()).isEqualByComparingTo("80");
    }

    @Test
    void expireDueReleasesFrozenExactlyOnce() {
        QuotaAccount from = accountService.createAccount("S6", "COD", "A", qty("60"));
        transferService.initiate("REQ-S6-AB", "S6", "COD", "A", "B", qty("15"), Duration.ofMillis(1));

        awaitExpiry();
        assertThat(transferService.expireDue()).isEqualTo(1);
        assertThat(transferService.expireDue()).isZero();

        QuotaAccount after = accountService.getAccount(from.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("60");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(accountService.getLedger(from.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_RELEASE).hasSize(1);
    }

    @Test
    void acceptAfterExpiryReleasesAndConflicts() {
        QuotaAccount from = accountService.createAccount("S7", "COD", "A", qty("60"));
        accountService.createAccount("S7", "COD", "B", qty("1"));
        Transfer transfer = transferService.initiate("REQ-S7-AB", "S7", "COD", "A", "B",
                qty("15"), Duration.ofMillis(1));

        awaitExpiry();
        assertThatThrownBy(() -> transferService.accept(transfer.getId(), "ACC-S7", "B"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("到期");

        QuotaAccount after = accountService.getAccount(from.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("60");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");
        assertThat(transferService.getTransfer(transfer.getId()).getStatus())
                .isEqualTo(TransferStatus.EXPIRED);
    }

    @Test
    void transferToSameHolderIsRejected() {
        accountService.createAccount("S8", "COD", "A", qty("10"));
        assertThatThrownBy(() -> transferService.initiate("REQ-S8-AA", "S8", "COD", "A", "A",
                        qty("1"), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void transferRequiresAccountsInSameSeasonAndSpecies() {
        QuotaAccount from = accountService.createAccount("S9", "COD", "A", qty("10"));
        // 受让方在该捕捞季/物种下没有账户，只在其他捕捞季有账户
        accountService.createAccount("S9B", "COD", "B", qty("10"));
        Transfer transfer = newTransfer("S9", "A", "B", "1");

        assertThatThrownBy(() -> transferService.accept(transfer.getId(), "ACC-S9", "B"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");

        // 接受失败后冻结量保持不变，可正常取消释放
        transferService.cancel(transfer.getId(), "CAN-S9", "A");
        assertThat(accountService.getAccount(from.getId()).getAvailable()).isEqualByComparingTo("10");
    }

    // ---- 外部请求号幂等 ----

    @Test
    void initiateWithSameRequestIdIsIdempotent() {
        accountService.createAccount("I1", "COD", "A", qty("100"));

        Transfer first = transferService.initiate("REQ-I1", "I1", "COD", "A", "B", qty("30"), null);
        Transfer replay = transferService.initiate("REQ-I1", "I1", "COD", "A", "B", qty("30"), null);

        assertThat(replay.getId()).isEqualTo(first.getId());
        QuotaAccount account = accountService.listAccounts("I1", "COD").get(0);
        assertThat(account.getFrozen()).isEqualByComparingTo("30");
        assertThat(accountService.getLedger(account.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_FREEZE).hasSize(1);
    }

    @Test
    void initiateSameRequestIdDifferentContentConflicts() {
        accountService.createAccount("I2", "COD", "A", qty("100"));
        transferService.initiate("REQ-I2", "I2", "COD", "A", "B", qty("30"), null);

        assertThatThrownBy(() -> transferService.initiate("REQ-I2", "I2", "COD", "A", "B",
                qty("40"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
        // 不同受让方同样冲突
        assertThatThrownBy(() -> transferService.initiate("REQ-I2", "I2", "COD", "A", "C",
                qty("30"), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptWithSameRequestIdIsIdempotent() {
        QuotaAccount from = accountService.createAccount("I3", "COD", "A", qty("100"));
        QuotaAccount to = accountService.createAccount("I3", "COD", "B", qty("5"));
        Transfer transfer = newTransfer("I3", "A", "B", "40");

        transferService.accept(transfer.getId(), "ACC-I3", "B");
        Transfer replay = transferService.accept(transfer.getId(), "ACC-I3", "B");

        assertThat(replay.getStatus()).isEqualTo(TransferStatus.ACCEPTED);
        // 只转出 / 转入一次
        assertThat(accountService.getLedger(from.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_OUT).hasSize(1);
        assertThat(accountService.getLedger(to.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_IN).hasSize(1);
        assertThat(accountService.getAccount(to.getId()).getAvailable()).isEqualByComparingTo("45");
    }

    @Test
    void acceptRequestIdUsedForAnotherTransferConflicts() {
        accountService.createAccount("I4", "COD", "A", qty("100"));
        accountService.createAccount("I4", "COD", "B", qty("5"));
        accountService.createAccount("I4", "COD", "C", qty("5"));
        Transfer t1 = newTransfer("I4", "A", "B", "10");
        Transfer t2 = newTransfer("I4", "A", "C", "10");
        transferService.accept(t1.getId(), "SHARED-ACC", "B");

        assertThatThrownBy(() -> transferService.accept(t2.getId(), "SHARED-ACC", "C"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已用于其他转让");
        // t2 未被结算，A 的冻结仍含 t2 的 10
        QuotaAccount a = accountService.listAccounts("I4", "COD").stream()
                .filter(x -> x.getHolder().equals("A")).findFirst().orElseThrow();
        assertThat(a.getFrozen()).isEqualByComparingTo("10");
    }

    // ---- 可转 / 已上岸 / 被占用 分解 ----

    @Test
    void availabilityDistinguishesTransferableLandedAndReserved() {
        // A 持有 100：一笔待处理转让冻结 20；一笔待复核卸港冻结 10；一笔已确认上岸 30（核销）
        accountService.createAccount("AV", "COD", "A", qty("100"));
        accountService.createAccount("AV", "COD", "B", qty("1"));
        newTransfer("AV", "A", "B", "20");
        landingService.declare("AV-LAND-PEND", "V1", "A", "COD", "AV", qty("10"));
        landingService.declare("AV-LAND-DONE", "V1", "A", "COD", "AV", qty("30"));
        landingService.confirm("AV-LAND-DONE", "AV-LAND-DONE-REV", "port-1");

        TransferService.Availability availability = transferService.getAvailability("AV", "COD", "A");

        // 可用 100-20-10-30=40；冻结 20+10=30；已核销 30
        assertThat(availability.available()).isEqualByComparingTo("40");
        assertThat(availability.frozen()).isEqualByComparingTo("30");
        assertThat(availability.consumed()).isEqualByComparingTo("30");
        assertThat(availability.landed()).isEqualByComparingTo("30");
        assertThat(availability.reservedByTransfers()).isEqualByComparingTo("20");
        assertThat(availability.reservedByPendingLanding()).isEqualByComparingTo("10");
    }

    @Test
    void cannotTransferLandedOrReservedQuantity() {
        accountService.createAccount("AV2", "COD", "A", qty("50"));
        accountService.createAccount("AV2", "COD", "B", qty("1"));
        landingService.declare("AV2-LAND", "V1", "A", "COD", "AV2", qty("30"));
        landingService.confirm("AV2-LAND", "AV2-LAND-REV", "port-1");
        // 已上岸核销 30，可转仅 20，申请 25 必须拒绝
        assertThatThrownBy(() -> newTransfer("AV2", "A", "B", "25"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可转余额不足");
    }

    // ---- 转让方取消 ----

    @Test
    void cancelReleasesFrozenAndIsIdempotent() {
        QuotaAccount from = accountService.createAccount("X1", "COD", "A", qty("60"));
        Transfer transfer = newTransfer("X1", "A", "B", "20");

        Transfer cancelled = transferService.cancel(transfer.getId(), "CAN-X1", "A");

        assertThat(cancelled.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(accountService.getAccount(from.getId()).getAvailable()).isEqualByComparingTo("60");
        assertThat(accountService.getLedger(from.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_RELEASE).hasSize(1);

        // 幂等重放不再释放
        Transfer replay = transferService.cancel(transfer.getId(), "CAN-X1", "A");
        assertThat(replay.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(accountService.getLedger(from.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_RELEASE).hasSize(1);
        // 取消后不能再接受
        assertThatThrownBy(() -> transferService.accept(transfer.getId(), "ACC-X1", "B"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nonSenderCannotCancel() {
        accountService.createAccount("X2", "COD", "A", qty("60"));
        Transfer transfer = newTransfer("X2", "A", "B", "20");

        assertThatThrownBy(() -> transferService.cancel(transfer.getId(), "CAN-X2", "B"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有转让发起方");
        // 未取消，冻结仍在
        TransferService.Availability availability = transferService.getAvailability("X2", "COD", "A");
        assertThat(availability.reservedByTransfers()).isEqualByComparingTo("20");
    }

    // ---- 转让前后余额 / 状态 / 时间视图，双方对应 ----

    @Test
    void detailShowsBeforeAfterBalancesAndCorrespondenceForAcceptedTransfer() {
        QuotaAccount from = accountService.createAccount("V1", "COD", "A", qty("100"));
        QuotaAccount to = accountService.createAccount("V1", "COD", "B", qty("5"));
        Transfer transfer = newTransfer("V1", "A", "B", "40");
        transferService.accept(transfer.getId(), "ACC-V1", "B");

        TransferService.TransferView view = transferService.getTransferView(transfer.getId());

        assertThat(view.balancesCorrespond()).isTrue();
        assertThat(view.transfer().status()).isEqualTo("ACCEPTED");
        assertThat(view.transfer().resolvedAt()).isNotNull();

        // 转让方：转让前 100/0/0（GRANT 之后），结算后 60/0/0
        assertThat(view.from().holder()).isEqualTo("A");
        assertThat(view.from().before().available()).isEqualByComparingTo("100");
        assertThat(view.from().after().available()).isEqualByComparingTo("60");
        assertThat(view.from().events()).extracting(TransferService.LedgerEventView::type)
                .containsExactly(LedgerEventType.TRANSFER_FREEZE.name(),
                        LedgerEventType.TRANSFER_OUT.name());
        // 受让方：转入前 5/0/0，转入后 45/0/0
        assertThat(view.to().holder()).isEqualTo("B");
        assertThat(view.to().before().available()).isEqualByComparingTo("5");
        assertThat(view.to().after().available()).isEqualByComparingTo("45");
        assertThat(view.to().events()).extracting(TransferService.LedgerEventView::type)
                .containsExactly(LedgerEventType.TRANSFER_IN.name());

        assertThat(accountService.getAccount(from.getId())).isNotNull();
    }

    @Test
    void detailForPendingTransferShowsFreezeOnlyAndCorresponds() {
        accountService.createAccount("V2", "COD", "A", qty("100"));
        // 不预先创建 B 的账户：待处理期间受让方账户可以尚不存在
        Transfer transfer = transferService.initiate("REQ-V2-AB", "V2", "COD", "A", "B",
                qty("30"), null);

        TransferService.TransferView view = transferService.getTransferView(transfer.getId());

        assertThat(view.transfer().status()).isEqualTo("PENDING");
        assertThat(view.balancesCorrespond()).isTrue();
        assertThat(view.from().before().available()).isEqualByComparingTo("100");
        assertThat(view.from().after().available()).isEqualByComparingTo("70");
        assertThat(view.from().after().frozen()).isEqualByComparingTo("30");
        assertThat(view.to()).isNull();
    }

    @Test
    void detailForCancelledTransferShowsReleaseAndCorrespondence() {
        accountService.createAccount("V3", "COD", "A", qty("80"));
        Transfer transfer = newTransfer("V3", "A", "B", "25");
        transferService.cancel(transfer.getId(), "CAN-V3", "A");

        TransferService.TransferView view = transferService.getTransferView(transfer.getId());

        assertThat(view.transfer().status()).isEqualTo("CANCELLED");
        assertThat(view.balancesCorrespond()).isTrue();
        // 释放后恢复 80/0/0
        assertThat(view.from().after().available()).isEqualByComparingTo("80");
        assertThat(view.from().after().frozen()).isEqualByComparingTo("0");
        assertThat(view.from().events()).extracting(TransferService.LedgerEventView::type)
                .containsExactly(LedgerEventType.TRANSFER_FREEZE.name(),
                        LedgerEventType.TRANSFER_RELEASE.name());
    }

    @Test
    void failedAcceptChangesNeitherSide() {
        QuotaAccount from = accountService.createAccount("V4", "COD", "A", qty("100"));
        Transfer transfer = newTransfer("V4", "A", "B", "40");

        // 受让方无账户：接受失败必须整体回滚，A 侧冻结保留，无 TRANSFER_OUT
        assertThatThrownBy(() -> transferService.accept(transfer.getId(), "ACC-V4", "B"))
                .isInstanceOf(BusinessException.class);

        QuotaAccount fromAfter = accountService.getAccount(from.getId());
        assertThat(fromAfter.getAvailable()).isEqualByComparingTo("60");
        assertThat(fromAfter.getFrozen()).isEqualByComparingTo("40");
        assertThat(accountService.getLedger(from.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_OUT).isEmpty();
        // 单据仍待处理，双方台账仍相互对应（只有冻结）
        assertThat(transferService.getTransferView(transfer.getId()).balancesCorrespond()).isTrue();
    }

    private static void awaitExpiry() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
