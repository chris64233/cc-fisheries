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
import com.chris64233.cc.fisheries.transfer.Transfer;
import com.chris64233.cc.fisheries.transfer.TransferService.LedgerEntryView;
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

    private static BigDecimal qty(String value) {
        return new BigDecimal(value);
    }

    @Test
    void initiateFreezesAvailableAndWritesLedger() {
        QuotaAccount from = accountService.createAccount("S1", "COD", "A", qty("100"));

        Transfer transfer = transferService.initiate("S1", "COD", "A", "B", qty("30"), null);

        QuotaAccount reloaded = accountService.getAccount(from.getId());
        assertThat(reloaded.getAvailable()).isEqualByComparingTo("70");
        assertThat(reloaded.getFrozen()).isEqualByComparingTo("30");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);

        List<LedgerEvent> ledger = accountService.getLedger(from.getId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(1).getType()).isEqualTo(LedgerEventType.TRANSFER_FREEZE);
        assertThat(ledger.get(1).getQuantity()).isEqualByComparingTo("30");
        assertThat(ledger.get(1).getReference()).isEqualTo(transfer.getId().toString());
    }

    @Test
    void initiateFailsWhenInsufficientAndWritesNoLedger() {
        QuotaAccount from = accountService.createAccount("S2", "COD", "A", qty("10"));

        assertThatThrownBy(() -> transferService.initiate("S2", "COD", "A", "B", qty("20"), null))
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
        Transfer transfer = transferService.initiate("S3", "COD", "A", "B", qty("40"), null);

        Transfer accepted = transferService.accept(transfer.getId());

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
        Transfer transfer = transferService.initiate("S4", "COD", "A", "B", qty("10"), null);
        transferService.reject(transfer.getId());

        assertThatThrownBy(() -> transferService.accept(transfer.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已终结");
    }

    @Test
    void rejectReleasesFrozenExactlyOnce() {
        QuotaAccount from = accountService.createAccount("S5", "COD", "A", qty("80"));
        Transfer transfer = transferService.initiate("S5", "COD", "A", "B", qty("25"), null);

        transferService.reject(transfer.getId());

        QuotaAccount after = accountService.getAccount(from.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("80");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> transferService.reject(transfer.getId()))
                .isInstanceOf(BusinessException.class);

        List<LedgerEvent> ledger = accountService.getLedger(from.getId());
        assertThat(ledger).filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_RELEASE).hasSize(1);
        assertThat(after.total()).isEqualByComparingTo("80");
    }

    @Test
    void expireDueReleasesFrozenExactlyOnce() {
        QuotaAccount from = accountService.createAccount("S6", "COD", "A", qty("60"));
        transferService.initiate("S6", "COD", "A", "B", qty("15"), Duration.ofMillis(1));

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
        Transfer transfer = transferService.initiate("S7", "COD", "A", "B", qty("15"), Duration.ofMillis(1));

        awaitExpiry();
        assertThatThrownBy(() -> transferService.accept(transfer.getId()))
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
        assertThatThrownBy(() -> transferService.initiate("S8", "COD", "A", "A", qty("1"), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void transferRequiresAccountsInSameSeasonAndSpecies() {
        QuotaAccount from = accountService.createAccount("S9", "COD", "A", qty("10"));
        // 受让方在该捕捞季/物种下没有账户，只在其他捕捞季有账户
        accountService.createAccount("S9B", "COD", "B", qty("10"));
        Transfer transfer = transferService.initiate("S9", "COD", "A", "B", qty("1"), null);

        assertThatThrownBy(() -> transferService.accept(transfer.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");

        // 接受失败后冻结量保持不变，可正常拒绝释放
        transferService.reject(transfer.getId());
        assertThat(accountService.getAccount(from.getId()).getAvailable()).isEqualByComparingTo("10");
    }

    @Test
    void initiateWithRequestIdIsIdempotentAndFreezesOnce() {
        QuotaAccount from = accountService.createAccount("S10", "COD", "A", qty("100"));

        Transfer first = transferService.initiate("REQ-10", "S10", "COD", "A", "B", qty("30"), null);
        Transfer replay = transferService.initiate("REQ-10", "S10", "COD", "A", "B", qty("30"), null);

        assertThat(replay.getId()).isEqualTo(first.getId());
        QuotaAccount after = accountService.getAccount(from.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("70");
        assertThat(after.getFrozen()).isEqualByComparingTo("30");
        assertThat(accountService.getLedger(from.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_FREEZE).hasSize(1);

        // 同请求号不同内容冲突
        assertThatThrownBy(() -> transferService.initiate("REQ-10", "S10", "COD", "A", "B", qty("31"), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptWithRequestIdIsIdempotentAndMovesOnce() {
        QuotaAccount from = accountService.createAccount("S11", "COD", "A", qty("100"));
        QuotaAccount to = accountService.createAccount("S11", "COD", "B", qty("5"));
        Transfer transfer = transferService.initiate("REQ-11", "S11", "COD", "A", "B", qty("40"), null);

        transferService.accept(transfer.getId(), "REQ-11-ACC");
        // 同接受请求号作用于同一转让：返回当前单据，不重复划转
        Transfer replay = transferService.accept(transfer.getId(), "REQ-11-ACC");
        assertThat(replay.getStatus()).isEqualTo(TransferStatus.ACCEPTED);

        QuotaAccount toAfter = accountService.getAccount(to.getId());
        assertThat(toAfter.getAvailable()).isEqualByComparingTo("45");
        assertThat(accountService.getLedger(to.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_IN).hasSize(1);

        // 同请求号作用于其他转让单冲突
        Transfer other = transferService.initiate("REQ-11B", "S11", "COD", "A", "B", qty("1"), null);
        assertThatThrownBy(() -> transferService.accept(other.getId(), "REQ-11-ACC"))
                .isInstanceOf(BusinessException.class);
        // 余额未被二次影响
        assertThat(accountService.getAccount(from.getId()).getAvailable()).isEqualByComparingTo("59");
    }

    @Test
    void cancelByFromHolderReleasesFrozenExactlyOnce() {
        QuotaAccount from = accountService.createAccount("S12", "COD", "A", qty("80"));
        Transfer transfer = transferService.initiate("REQ-12", "S12", "COD", "A", "B", qty("25"), null);

        Transfer cancelled = transferService.cancel(transfer.getId(), "REQ-12-CANCEL", "A");
        assertThat(cancelled.getStatus()).isEqualTo(TransferStatus.CANCELLED);

        QuotaAccount after = accountService.getAccount(from.getId());
        assertThat(after.getAvailable()).isEqualByComparingTo("80");
        assertThat(after.getFrozen()).isEqualByComparingTo("0");

        // 取消幂等：同请求号重放不重复释放
        Transfer replay = transferService.cancel(transfer.getId(), "REQ-12-CANCEL", "A");
        assertThat(replay.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(accountService.getLedger(from.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_RELEASE).hasSize(1);

        // 取消后再接受 / 拒绝均被拒绝
        assertThatThrownBy(() -> transferService.accept(transfer.getId(), "REQ-12-ACC"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> transferService.reject(transfer.getId(), "REQ-12-REJ"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancelByOtherHolderIsForbiddenAndKeepsFrozen() {
        QuotaAccount from = accountService.createAccount("S13", "COD", "A", qty("80"));
        Transfer transfer = transferService.initiate("REQ-13", "S13", "COD", "A", "B", qty("25"), null);

        // 受让方不能取消转让
        assertThatThrownBy(() -> transferService.cancel(transfer.getId(), "REQ-13-CANCEL", "B"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("转让方");

        QuotaAccount after = accountService.getAccount(from.getId());
        assertThat(after.getFrozen()).isEqualByComparingTo("25");
        assertThat(transferService.getTransfer(transfer.getId()).getStatus())
                .isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void rejectWithRequestIdIsIdempotent() {
        QuotaAccount from = accountService.createAccount("S14", "COD", "A", qty("80"));
        Transfer transfer = transferService.initiate("REQ-14", "S14", "COD", "A", "B", qty("25"), null);

        transferService.reject(transfer.getId(), "REQ-14-REJ");
        Transfer replay = transferService.reject(transfer.getId(), "REQ-14-REJ");
        assertThat(replay.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(accountService.getLedger(from.getId()))
                .filteredOn(e -> e.getType() == LedgerEventType.TRANSFER_RELEASE).hasSize(1);
    }

    @Test
    void availabilitySplitsTransferableLandedAndOccupied() {
        QuotaAccount a = accountService.createAccount("S15", "COD", "A", qty("100"));
        Transfer t1 = transferService.initiate("REQ-15-1", "S15", "COD", "A", "B", qty("30"), null);
        transferService.initiate("REQ-15-2", "S15", "COD", "A", "B", qty("10"), null);

        var view = transferService.getAvailability("S15", "COD", "A", null);
        assertThat(view.accountId()).isEqualTo(a.getId());
        assertThat(view.available()).isEqualByComparingTo("60");
        assertThat(view.transferable()).isEqualByComparingTo("60");
        assertThat(view.landed()).isEqualByComparingTo("0");
        assertThat(view.occupiedByOthers()).isEqualByComparingTo("40");
        assertThat(view.transferHeld()).isEqualByComparingTo("40");
        assertThat(view.landingHeld()).isEqualByComparingTo("0");
        assertThat(view.correctionHeld()).isEqualByComparingTo("0");

        // 排除本笔转让冻结后：可转 90，其他转让占用只剩 10
        var selfView = transferService.getAvailability("S15", "COD", "A", t1.getId());
        assertThat(selfView.transferable()).isEqualByComparingTo("90");
        assertThat(selfView.occupiedByOthers()).isEqualByComparingTo("10");
        assertThat(selfView.transferHeld()).isEqualByComparingTo("10");
    }

    @Test
    void detailReportsBeforeAfterBalancesForBothParties() {
        QuotaAccount from = accountService.createAccount("S16", "COD", "A", qty("100"));
        QuotaAccount to = accountService.createAccount("S16", "COD", "B", qty("7"));
        Transfer transfer = transferService.initiate("REQ-16", "S16", "COD", "A", "B", qty("40"), null);
        transferService.accept(transfer.getId(), "REQ-16-ACC");

        var detail = transferService.getDetail(transfer.getId());
        assertThat(detail.status()).isEqualTo("ACCEPTED");

        var fromParty = detail.from();
        assertThat(fromParty.before().available()).isEqualByComparingTo("100");
        assertThat(fromParty.before().frozen()).isEqualByComparingTo("0");
        assertThat(fromParty.after().available()).isEqualByComparingTo("60");
        assertThat(fromParty.after().frozen()).isEqualByComparingTo("0");
        assertThat(fromParty.events()).extracting(LedgerEntryView::type)
                .containsExactly("TRANSFER_FREEZE", "TRANSFER_OUT");
        assertThat(fromParty.hold().status()).isEqualTo("SETTLED");
        // 冻结 / 释放是账户内平移（总量守恒）；转出使账户总量减少等量
        for (var event : fromParty.events()) {
            BigDecimal beforeTotal = event.before().available().add(event.before().frozen())
                    .add(event.before().consumed());
            BigDecimal afterTotal = event.after().available().add(event.after().frozen())
                    .add(event.after().consumed());
            if (event.type().equals("TRANSFER_OUT")) {
                assertThat(afterTotal).isEqualByComparingTo(beforeTotal.subtract(event.quantity()));
            } else {
                assertThat(afterTotal).isEqualByComparingTo(beforeTotal);
            }
        }

        var toParty = detail.to();
        assertThat(toParty.accountId()).isEqualTo(to.getId());
        assertThat(toParty.before().available()).isEqualByComparingTo("7");
        assertThat(toParty.after().available()).isEqualByComparingTo("47");
        assertThat(toParty.events()).hasSize(1);
        assertThat(toParty.events().get(0).type()).isEqualTo("TRANSFER_IN");
    }

    @Test
    void pendingDetailHasFromHoldAndNoToAccount() {
        accountService.createAccount("S17", "COD", "A", qty("50"));
        // B 在该季/物种下无账户
        Transfer transfer = transferService.initiate("REQ-17", "S17", "COD", "A", "B", qty("20"), null);

        var detail = transferService.getDetail(transfer.getId());
        assertThat(detail.from().after().frozen()).isEqualByComparingTo("20");
        assertThat(detail.from().hold().status()).isEqualTo("HELD");
        assertThat(detail.to().accountExists()).isFalse();
        assertThat(detail.to().events()).isEmpty();
    }

    private static void awaitExpiry() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
