package com.chris64233.cc.fisheries.transfer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.common.Quantities;
import com.chris64233.cc.fisheries.quota.LedgerEvent;
import com.chris64233.cc.fisheries.quota.LedgerEventRepository;
import com.chris64233.cc.fisheries.quota.LedgerEventType;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配额转让：发起时冻结转让方可用量；接受时同事务扣减转让方、增加受让方；
 * 拒绝或到期时释放冻结量。所有状态迁移都在转让单行锁 + 账户行锁内完成，
 * 冻结量只会被释放或结算一次。
 */
@Service
public class TransferService {

    static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final TransferRepository transferRepository;
    private final QuotaAccountRepository accountRepository;
    private final LedgerEventRepository ledgerRepository;
    private final ObjectProvider<TransferService> selfProvider;

    public TransferService(TransferRepository transferRepository,
                           QuotaAccountRepository accountRepository,
                           LedgerEventRepository ledgerRepository,
                           ObjectProvider<TransferService> selfProvider) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.selfProvider = selfProvider;
    }

    @Transactional
    public Transfer initiate(String season, String species, String fromHolder, String toHolder,
                             BigDecimal quantity, Duration ttl) {
        BigDecimal amount = Quantities.requirePositive(quantity, "转让数量");
        if (fromHolder.equals(toHolder)) {
            throw BusinessException.unprocessable("转让方与受让方不能相同");
        }
        QuotaAccount from = lockAccount(season, species, fromHolder);
        if (from.getAvailable().compareTo(amount) < 0) {
            throw BusinessException.unprocessable("可用配额不足，无法冻结 " + amount);
        }
        from.freeze(amount);
        assertConsistent(from);
        Transfer transfer = transferRepository.save(new Transfer(
                season, species, fromHolder, toHolder, amount,
                Instant.now().plus(ttl == null ? DEFAULT_TTL : ttl)));
        ledgerRepository.save(new LedgerEvent(from, LedgerEventType.TRANSFER_FREEZE, amount,
                transfer.getId().toString()));
        return transfer;
    }

    /**
     * 受让方接受：同事务内扣减转让方冻结量、增加受让方可用量。
     */
    @Transactional
    public Transfer accept(Long transferId) {
        Transfer snapshot = transferRepository.findById(transferId)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
        if (snapshot.isPending() && snapshot.isExpired(Instant.now())) {
            // 到期释放在独立事务中提交，不因本请求失败而回滚
            self().expireOne(transferId);
            throw BusinessException.conflict("转让已到期，冻结量已释放: " + transferId);
        }
        Transfer transfer = lockPendingTransfer(transferId);
        if (transfer.isExpired(Instant.now())) {
            throw BusinessException.conflict("转让已到期，等待到期处理释放: " + transferId);
        }
        // 固定加锁顺序，避免双向转让并发时死锁
        boolean fromFirst = transfer.getFromHolder().compareTo(transfer.getToHolder()) <= 0;
        QuotaAccount first = lockAccount(transfer.getSeason(), transfer.getSpecies(),
                fromFirst ? transfer.getFromHolder() : transfer.getToHolder());
        QuotaAccount second = lockAccount(transfer.getSeason(), transfer.getSpecies(),
                fromFirst ? transfer.getToHolder() : transfer.getFromHolder());
        QuotaAccount from = fromFirst ? first : second;
        QuotaAccount to = fromFirst ? second : first;

        from.settleFrozen(transfer.getQuantity());
        to.credit(transfer.getQuantity());
        assertConsistent(from);
        assertConsistent(to);
        transfer.markAccepted();
        String reference = transfer.getId().toString();
        ledgerRepository.save(new LedgerEvent(from, LedgerEventType.TRANSFER_OUT, transfer.getQuantity(), reference));
        ledgerRepository.save(new LedgerEvent(to, LedgerEventType.TRANSFER_IN, transfer.getQuantity(), reference));
        return transfer;
    }

    /**
     * 受让方拒绝：释放转让方冻结量，只生效一次。
     */
    @Transactional
    public Transfer reject(Long transferId) {
        Transfer transfer = lockPendingTransfer(transferId);
        releaseFrozen(transfer);
        transfer.markRejected();
        return transfer;
    }

    /**
     * 到期处理：释放冻结量，只生效一次。返回本次到期的转让数量。
     */
    public int expireDue() {
        List<Transfer> due = transferRepository.findByStatusAndExpiresAtBefore(
                TransferStatus.PENDING, Instant.now());
        int expired = 0;
        for (Transfer transfer : due) {
            if (self().expireOne(transfer.getId())) {
                expired++;
            }
        }
        return expired;
    }

    /**
     * 单笔到期处理，独立事务提交（调用方事务回滚不影响释放）；返回是否真正执行了释放。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireOne(Long transferId) {
        Transfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
        if (!transfer.isPending()) {
            return false;
        }
        doExpire(transfer);
        return true;
    }

    @Transactional(readOnly = true)
    public Transfer getTransfer(Long id) {
        return transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<Transfer> listTransfers(String season, String species) {
        if (season != null && species != null) {
            return transferRepository.findBySeasonAndSpecies(season, species);
        }
        return transferRepository.findAll();
    }

    private Transfer lockPendingTransfer(Long transferId) {
        Transfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
        if (!transfer.isPending()) {
            throw BusinessException.conflict("转让已终结，当前状态: " + transfer.getStatus());
        }
        return transfer;
    }

    private void doExpire(Transfer transfer) {
        releaseFrozen(transfer);
        transfer.markExpired();
    }

    private void releaseFrozen(Transfer transfer) {
        QuotaAccount from = lockAccount(transfer.getSeason(), transfer.getSpecies(), transfer.getFromHolder());
        from.releaseFrozen(transfer.getQuantity());
        assertConsistent(from);
        ledgerRepository.save(new LedgerEvent(from, LedgerEventType.TRANSFER_RELEASE,
                transfer.getQuantity(), transfer.getId().toString()));
    }

    private QuotaAccount lockAccount(String season, String species, String holder) {
        return accountRepository.findForUpdate(season, species, holder)
                .orElseThrow(() -> BusinessException.notFound(
                        "配额账户不存在: " + season + "/" + species + "/" + holder));
    }

    private void assertConsistent(QuotaAccount account) {
        if (account.hasNegativeBalance()) {
            throw BusinessException.unprocessable("操作会导致账户余额为负: " + account.getHolder());
        }
    }

    private TransferService self() {
        return selfProvider.getObject();
    }
}
