package com.chris64233.cc.fisheries.transfer;

import com.chris64233.cc.fisheries.common.ApiException;
import com.chris64233.cc.fisheries.common.Quantities;
import com.chris64233.cc.fisheries.quota.LedgerEntry;
import com.chris64233.cc.fisheries.quota.LedgerEntryRepository;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;
import com.chris64233.cc.fisheries.quota.QuotaAccountService;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private static final long DEFAULT_TTL_SECONDS = 24 * 3600;

    private final TransferRepository transferRepository;
    private final QuotaAccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;

    public TransferService(TransferRepository transferRepository,
                           QuotaAccountRepository accountRepository,
                           LedgerEntryRepository ledgerRepository) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional
    public Transfer initiate(String season, String species, String fromHolder, String toHolder,
                             BigDecimal quantity, Long ttlSeconds) {
        Quantities.requireText(season, "捕捞季");
        Quantities.requireText(species, "物种");
        Quantities.requireText(fromHolder, "转让方");
        Quantities.requireText(toHolder, "受让方");
        if (fromHolder.equals(toHolder)) {
            throw ApiException.badRequest("转让方与受让方不能相同");
        }
        BigDecimal amount = Quantities.requirePositive(quantity, "转让数量");
        long ttl = ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds;
        if (ttl <= 0) {
            throw ApiException.badRequest("转让有效期必须大于 0 秒");
        }
        QuotaAccount from = lockAccount(season, species, fromHolder);
        from.freeze(amount);
        Transfer transfer = transferRepository.save(
                new Transfer(season, species, fromHolder, toHolder, amount, Instant.now().plusSeconds(ttl)));
        ledgerRepository.save(new LedgerEntry(from, LedgerEntry.Type.TRANSFER_FREEZE, amount, "TRANSFER", transfer.getId()));
        return transfer;
    }

    @Transactional
    public Transfer accept(Long transferId, String holder) {
        Transfer transfer = lockTransfer(transferId);
        requirePending(transfer);
        if (!transfer.getToHolder().equals(holder)) {
            throw ApiException.conflict("只有受让方可以接受转让");
        }
        if (Instant.now().isAfter(transfer.getExpiresAt())) {
            // 到期释放需要随事务提交，由调用方根据终结状态向客户端报告冲突
            releaseFrozen(transfer);
            transfer.markExpired();
            return transfer;
        }
        QuotaAccount from = lockAccount(transfer.getSeason(), transfer.getSpecies(), transfer.getFromHolder());
        QuotaAccount to = accountRepository
                .findForUpdate(transfer.getSeason(), transfer.getSpecies(), transfer.getToHolder())
                .orElseGet(() -> accountRepository.save(new QuotaAccount(
                        transfer.getSeason(), transfer.getSpecies(), transfer.getToHolder())));
        from.settleTransferOut(transfer.getQuantity());
        to.credit(transfer.getQuantity());
        ledgerRepository.save(new LedgerEntry(from, LedgerEntry.Type.TRANSFER_OUT, transfer.getQuantity(), "TRANSFER", transfer.getId()));
        ledgerRepository.save(new LedgerEntry(to, LedgerEntry.Type.TRANSFER_IN, transfer.getQuantity(), "TRANSFER", transfer.getId()));
        transfer.markAccepted();
        return transfer;
    }

    @Transactional
    public Transfer reject(Long transferId, String holder) {
        Transfer transfer = lockTransfer(transferId);
        requirePending(transfer);
        if (!transfer.getToHolder().equals(holder) && !transfer.getFromHolder().equals(holder)) {
            throw ApiException.conflict("只有转让双方可以拒绝或撤销转让");
        }
        releaseFrozen(transfer);
        transfer.markRejected();
        return transfer;
    }

    @Transactional
    public Transfer expire(Long transferId) {
        Transfer transfer = lockTransfer(transferId);
        if (!transfer.isPending()) {
            return transfer;
        }
        if (Instant.now().isBefore(transfer.getExpiresAt())) {
            throw ApiException.conflict("转让尚未到期，不能执行到期释放");
        }
        releaseFrozen(transfer);
        transfer.markExpired();
        return transfer;
    }

    @Transactional(readOnly = true)
    public Transfer getTransfer(Long transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> ApiException.notFound("转让不存在: " + transferId));
    }

    private Transfer lockTransfer(Long transferId) {
        return transferRepository.findWithLockById(transferId)
                .orElseThrow(() -> ApiException.notFound("转让不存在: " + transferId));
    }

    private void requirePending(Transfer transfer) {
        if (!transfer.isPending()) {
            throw ApiException.conflict("转让已终结，当前状态: " + transfer.getStatus());
        }
    }

    private void releaseFrozen(Transfer transfer) {
        QuotaAccount from = lockAccount(transfer.getSeason(), transfer.getSpecies(), transfer.getFromHolder());
        from.releaseFrozen(transfer.getQuantity());
        ledgerRepository.save(new LedgerEntry(from, LedgerEntry.Type.TRANSFER_RELEASE, transfer.getQuantity(), "TRANSFER", transfer.getId()));
    }

    private QuotaAccount lockAccount(String season, String species, String holder) {
        return accountRepository.findForUpdate(season, species, holder)
                .orElseThrow(() -> ApiException.notFound("转让方配额账户不存在"));
    }
}
