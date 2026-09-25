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

    private static void awaitExpiry() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
