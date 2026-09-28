package com.chris64233.cc.fisheries.transfer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.chris64233.cc.fisheries.common.BusinessException;
import com.chris64233.cc.fisheries.common.IdempotentDecision;
import com.chris64233.cc.fisheries.common.IdempotentDecisionRepository;
import com.chris64233.cc.fisheries.common.Quantities;
import com.chris64233.cc.fisheries.hold.HoldStatus;
import com.chris64233.cc.fisheries.hold.HoldType;
import com.chris64233.cc.fisheries.hold.QuotaHold;
import com.chris64233.cc.fisheries.hold.QuotaHoldRepository;
import com.chris64233.cc.fisheries.quota.LedgerEvent;
import com.chris64233.cc.fisheries.quota.LedgerEventRepository;
import com.chris64233.cc.fisheries.quota.LedgerEventType;
import com.chris64233.cc.fisheries.quota.QuotaAccount;
import com.chris64233.cc.fisheries.quota.QuotaAccountRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 配额转让：发起时冻结转让方可用量；受让方接受时在同一事务内扣减转让方、增加受让方；
 * 拒绝、转让方取消或到期时释放冻结量。所有状态迁移都在转让单行锁 + 账户行锁内完成，
 * 冻结量只会被释放或结算一次。
 *
 * <p>发起、接受、拒绝、取消均可携带外部请求号（requestId），请求号与动作组成幂等键，
 * 重复或并发重放只生效一次：不会重复冻结、重复结算或重复释放；同一请求号作用于
 * 不同转让单或携带不同申请内容时返回 409。
 */
@Service
public class TransferService {

    static final Duration DEFAULT_TTL = Duration.ofHours(24);

    static final String CREATE_KEY_PREFIX = "TRANSFER-CREATE:";
    static final String ACCEPT_KEY_PREFIX = "TRANSFER-ACCEPT:";
    static final String REJECT_KEY_PREFIX = "TRANSFER-REJECT:";
    static final String CANCEL_KEY_PREFIX = "TRANSFER-CANCEL:";

    private final TransferRepository transferRepository;
    private final QuotaAccountRepository accountRepository;
    private final LedgerEventRepository ledgerRepository;
    private final QuotaHoldRepository holdRepository;
    private final IdempotentDecisionRepository decisionRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<TransferService> selfProvider;

    public TransferService(TransferRepository transferRepository,
                           QuotaAccountRepository accountRepository,
                           LedgerEventRepository ledgerRepository,
                           QuotaHoldRepository holdRepository,
                           IdempotentDecisionRepository decisionRepository,
                           TransactionTemplate transactionTemplate,
                           ObjectProvider<TransferService> selfProvider) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.holdRepository = holdRepository;
        this.decisionRepository = decisionRepository;
        this.transactionTemplate = transactionTemplate;
        this.selfProvider = selfProvider;
    }

    /** 无外部请求号的发起入口（服务内部调用 / 向后兼容）。 */
    public Transfer initiate(String season, String species, String fromHolder, String toHolder,
                             BigDecimal quantity, Duration ttl) {
        return initiate(null, season, species, fromHolder, toHolder, quantity, ttl);
    }

    /**
     * 发起转让并冻结转让方可用量。requestId 非空时保证幂等：相同请求号 + 相同内容
     * 重放返回原转让单，不重复冻结；内容不同返回 409；并发同请求号由唯一约束兜底。
     */
    public Transfer initiate(String requestId, String season, String species, String fromHolder, String toHolder,
                             BigDecimal quantity, Duration ttl) {
        BigDecimal amount = Quantities.requirePositive(quantity, "转让数量");
        if (fromHolder.equals(toHolder)) {
            throw BusinessException.unprocessable("转让方与受让方不能相同");
        }
        Duration timeToLive = ttl == null ? DEFAULT_TTL : ttl;
        try {
            return transactionTemplate.execute(status ->
                    doInitiate(requestId, season, species, fromHolder, toHolder, amount, timeToLive));
        } catch (DataIntegrityViolationException e) {
            // 并发下两个相同请求号同时登记：唯一约束拦截，本事务回滚后按幂等重放处理
            return resolveExistingInitiate(requestId, season, species, fromHolder, toHolder, amount);
        }
    }

    private Transfer doInitiate(String requestId, String season, String species, String fromHolder,
                                String toHolder, BigDecimal amount, Duration ttl) {
        // 先锁来源账户行：同账户的并发发起在此串行，第二个请求能读到第一个已登记的幂等决策
        QuotaAccount from = lockAccount(season, species, fromHolder);
        if (requestId != null) {
            var decision = decisionRepository.findByIdempotencyKey(CREATE_KEY_PREFIX + requestId);
            if (decision.isPresent()) {
                Transfer existing = transferRepository.findById(Long.valueOf(decision.get().getTargetRef()))
                        .orElseThrow(() -> BusinessException.notFound(
                                "转让不存在: " + decision.get().getTargetRef()));
                return verifyInitiateReplay(existing, season, species, fromHolder, toHolder, amount);
            }
        }
        if (from.getAvailable().compareTo(amount) < 0) {
            throw BusinessException.unprocessable("可转配额不足，无法冻结 " + amount
                    + "（当前可转 " + from.getAvailable() + "）");
        }
        from.freeze(amount);
        assertConsistent(from);
        Transfer transfer = transferRepository.save(new Transfer(
                season, species, fromHolder, toHolder, amount, Instant.now().plus(ttl)));
        String reference = transfer.getId().toString();
        ledgerRepository.save(new LedgerEvent(from, LedgerEventType.TRANSFER_FREEZE, amount, reference));
        holdRepository.save(new QuotaHold(from, HoldType.TRANSFER, reference, amount));
        if (requestId != null) {
            decisionRepository.save(new IdempotentDecision(
                    CREATE_KEY_PREFIX + requestId, transfer.getStatus().name(), reference));
        }
        return transfer;
    }

    private Transfer resolveExistingInitiate(String requestId, String season, String species, String fromHolder,
                                             String toHolder, BigDecimal amount) {
        if (requestId == null) {
            throw BusinessException.conflict("转让发起冲突，请重试");
        }
        // 获胜事务可能尚未提交，短暂重试等待其决策可见
        for (int attempt = 0; attempt < 20; attempt++) {
            var decision = decisionRepository.findByIdempotencyKey(CREATE_KEY_PREFIX + requestId);
            if (decision.isPresent()) {
                Transfer existing = transferRepository.findById(Long.valueOf(decision.get().getTargetRef()))
                        .orElseThrow(() -> BusinessException.notFound(
                                "转让不存在: " + decision.get().getTargetRef()));
                return verifyInitiateReplay(existing, season, species, fromHolder, toHolder, amount);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw BusinessException.conflict("转让请求处理冲突，请重试: " + requestId);
    }

    private Transfer verifyInitiateReplay(Transfer existing, String season, String species, String fromHolder,
                                          String toHolder, BigDecimal amount) {
        if (!existing.getSeason().equals(season) || !existing.getSpecies().equals(species)
                || !existing.getFromHolder().equals(fromHolder) || !existing.getToHolder().equals(toHolder)
                || existing.getQuantity().compareTo(amount) != 0) {
            throw BusinessException.conflict("请求号已用于内容不同的转让申请");
        }
        return existing;
    }

    /** 无外部请求号的接受入口（服务内部调用 / 向后兼容）。 */
    public Transfer accept(Long transferId) {
        return accept(transferId, null);
    }

    /**
     * 受让方接受：同事务内扣减转让方冻结量、增加受让方可用量。请求号幂等，
     * 重复接受不重复划转；请求号已用于其他转让单返回 409。
     */
    public Transfer accept(Long transferId, String requestId) {
        Transfer snapshot = transferRepository.findById(transferId)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
        if (snapshot.isPending() && snapshot.isExpired(Instant.now())) {
            // 到期释放在独立事务中提交，不因本请求失败而回滚
            self().expireOne(transferId);
            throw BusinessException.conflict("转让已到期，冻结量已释放: " + transferId);
        }
        try {
            return transactionTemplate.execute(status -> doAccept(transferId, requestId));
        } catch (DataIntegrityViolationException e) {
            return replayAction(ACCEPT_KEY_PREFIX, requestId, transferId);
        }
    }

    private Transfer doAccept(Long transferId, String requestId) {
        Transfer transfer = lockTransfer(transferId);
        // 幂等重放优先于状态校验：单据已终结时，原请求号仍应返回当前单据而非冲突
        if (requestId != null) {
            var decision = decisionRepository.findByIdempotencyKey(ACCEPT_KEY_PREFIX + requestId);
            if (decision.isPresent()) {
                return requireSameTarget(decision.get(), transferId);
            }
        }
        requirePending(transfer);
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
        holdFor(reference).markSettled();
        ledgerRepository.save(new LedgerEvent(from, LedgerEventType.TRANSFER_OUT, transfer.getQuantity(), reference));
        ledgerRepository.save(new LedgerEvent(to, LedgerEventType.TRANSFER_IN, transfer.getQuantity(), reference));
        recordDecision(requestId, ACCEPT_KEY_PREFIX, transfer);
        return transfer;
    }

    /** 无外部请求号的拒绝入口（服务内部调用 / 向后兼容）。 */
    public Transfer reject(Long transferId) {
        return reject(transferId, null);
    }

    /**
     * 受让方拒绝：释放转让方冻结量，只生效一次。请求号幂等，重复拒绝不重复释放。
     */
    public Transfer reject(Long transferId, String requestId) {
        try {
            return transactionTemplate.execute(status -> doReject(transferId, requestId));
        } catch (DataIntegrityViolationException e) {
            return replayAction(REJECT_KEY_PREFIX, requestId, transferId);
        }
    }

    private Transfer doReject(Long transferId, String requestId) {
        Transfer transfer = lockTransfer(transferId);
        if (requestId != null) {
            var decision = decisionRepository.findByIdempotencyKey(REJECT_KEY_PREFIX + requestId);
            if (decision.isPresent()) {
                return requireSameTarget(decision.get(), transferId);
            }
        }
        requirePending(transfer);
        releaseFrozen(transfer);
        transfer.markRejected();
        recordDecision(requestId, REJECT_KEY_PREFIX, transfer);
        return transfer;
    }

    /**
     * 转让方在受让方接受前主动取消：完整释放冻结量，只生效一次。
     * 只有来源权利人本人可以取消；请求号幂等。
     */
    public Transfer cancel(Long transferId, String requestId, String requesterHolder) {
        Quantities.requireText(requesterHolder, "取消申请人");
        try {
            return transactionTemplate.execute(status -> doCancel(transferId, requestId, requesterHolder));
        } catch (DataIntegrityViolationException e) {
            return replayAction(CANCEL_KEY_PREFIX, requestId, transferId);
        }
    }

    private Transfer doCancel(Long transferId, String requestId, String requesterHolder) {
        Transfer transfer = lockTransfer(transferId);
        if (requestId != null) {
            var decision = decisionRepository.findByIdempotencyKey(CANCEL_KEY_PREFIX + requestId);
            if (decision.isPresent()) {
                return requireSameTarget(decision.get(), transferId);
            }
        }
        requirePending(transfer);
        if (!transfer.getFromHolder().equals(requesterHolder)) {
            throw BusinessException.forbidden("只有转让方可以取消转让: " + transferId);
        }
        releaseFrozen(transfer);
        transfer.markCancelled();
        recordDecision(requestId, CANCEL_KEY_PREFIX, transfer);
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

    /**
     * 来源许可证（账户）的可转余额视图：区分可转余额、已上岸（已核销）数量、
     * 以及被其他申请暂时占用的冻结量（按转让 / 卸港待复核 / 更正待复核拆分）。
     *
     * <p>{@code excludeTransferId} 用于查看某笔转让时把该笔自身的冻结算回可转量，
     * 这样占用量只统计“其他申请”。
     */
    @Transactional(readOnly = true)
    public AvailabilityView getAvailability(String season, String species, String holder, Long excludeTransferId) {
        QuotaAccount account = accountRepository.findBySeasonAndSpeciesAndHolder(season, species, holder)
                .orElseThrow(() -> BusinessException.notFound(
                        "配额账户不存在: " + season + "/" + species + "/" + holder));
        BigDecimal transferHeld = Quantities.ZERO;
        BigDecimal landingHeld = Quantities.ZERO;
        BigDecimal correctionHeld = Quantities.ZERO;
        BigDecimal excluded = Quantities.ZERO;
        String excludedReference = excludeTransferId == null ? null : excludeTransferId.toString();
        for (QuotaHold hold : holdRepository.findByAccountIdAndStatusOrderByIdAsc(
                account.getId(), HoldStatus.HELD)) {
            BigDecimal quantity = hold.getQuantity();
            switch (hold.getHoldType()) {
                case TRANSFER -> {
                    transferHeld = transferHeld.add(quantity);
                    if (hold.getReferenceId().equals(excludedReference)) {
                        excluded = excluded.add(quantity);
                    }
                }
                case LANDING -> landingHeld = landingHeld.add(quantity);
                case CORRECTION -> correctionHeld = correctionHeld.add(quantity);
            }
        }
        BigDecimal occupied = transferHeld.add(landingHeld).add(correctionHeld);
        return new AvailabilityView(season, species, holder, account.getId(), account.getVersion(),
                account.getAvailable(), account.getFrozen(), account.getConsumed(),
                account.getAvailable().add(excluded),
                account.getConsumed(), occupied.subtract(excluded),
                transferHeld.subtract(excluded), landingHeld, correctionHeld, excludeTransferId);
    }

    /**
     * 转让详情：单据状态与时间，加上双方许可证在转让前 / 转让后的三余额快照、
     * 与本转让相关的台账事件和冻结明细，用于核对双方余额与转让记录相互对应。
     */
    @Transactional(readOnly = true)
    public TransferDetail getDetail(Long transferId) {
        Transfer transfer = getTransfer(transferId);
        String reference = transferId.toString();
        PartyView from = buildParty(transfer.getSeason(), transfer.getSpecies(),
                transfer.getFromHolder(), reference);
        PartyView to = buildParty(transfer.getSeason(), transfer.getSpecies(),
                transfer.getToHolder(), reference);
        return new TransferDetail(transfer.getId(), transfer.getSeason(), transfer.getSpecies(),
                transfer.getFromHolder(), transfer.getToHolder(), transfer.getQuantity(),
                transfer.getStatus().name(), transfer.getCreatedAt().toString(),
                transfer.getExpiresAt().toString(),
                transfer.getResolvedAt() == null ? null : transfer.getResolvedAt().toString(),
                from, to);
    }

    private PartyView buildParty(String season, String species, String holder, String reference) {
        var account = accountRepository.findBySeasonAndSpeciesAndHolder(season, species, holder);
        if (account.isEmpty()) {
            // 发起转让时不要求受让方已持有该季/物种账户，接受前可能仍不存在
            return new PartyView(holder, null, false, null, null, List.of(), null);
        }
        QuotaAccount current = account.get();
        List<LedgerEvent> events = ledgerRepository
                .findByAccountIdAndReferenceOrderByIdAsc(current.getId(), reference);
        List<LedgerEntryView> entryViews = events.stream().map(this::toEntryView).toList();

        BalanceSnapshot after;
        BalanceSnapshot before;
        if (events.isEmpty()) {
            after = snapshot(current);
            before = after;
        } else {
            LedgerEvent last = events.get(events.size() - 1);
            after = new BalanceSnapshot(last.getAvailableAfter(), last.getFrozenAfter(),
                    last.getConsumedAfter());
            before = beforeOf(events.get(0));
        }
        QuotaHold hold = holdRepository
                .findByAccountIdAndHoldTypeAndReferenceId(current.getId(), HoldType.TRANSFER, reference)
                .orElse(null);
        HoldView holdView = hold == null ? null : new HoldView(hold.getHoldType().name(),
                hold.getReferenceId(), hold.getQuantity(), hold.getStatus().name(),
                hold.getCreatedAt().toString(),
                hold.getResolvedAt() == null ? null : hold.getResolvedAt().toString());
        return new PartyView(holder, current.getId(), true, before, after, entryViews, holdView);
    }

    private LedgerEntryView toEntryView(LedgerEvent event) {
        return new LedgerEntryView(event.getId(), event.getType().name(), event.getQuantity(),
                beforeOf(event), new BalanceSnapshot(event.getAvailableAfter(), event.getFrozenAfter(),
                        event.getConsumedAfter()),
                event.getOccurredAt().toString());
    }

    /**
     * 由单笔台账事件反推事件前余额：事件只在两个余额间等量平移，按事件类型逆运算即可。
     */
    private BalanceSnapshot beforeOf(LedgerEvent event) {
        BigDecimal available = event.getAvailableAfter();
        BigDecimal frozen = event.getFrozenAfter();
        BigDecimal consumed = event.getConsumedAfter();
        BigDecimal qty = event.getQuantity();
        switch (event.getType()) {
            case TRANSFER_FREEZE -> { // available -= q, frozen += q
                available = available.add(qty);
                frozen = frozen.subtract(qty);
            }
            case TRANSFER_RELEASE -> { // frozen -= q, available += q
                frozen = frozen.add(qty);
                available = available.subtract(qty);
            }
            case TRANSFER_OUT -> frozen = frozen.add(qty); // frozen -= q
            case TRANSFER_IN -> available = available.subtract(qty); // available += q
            default -> throw new IllegalStateException("转让台账出现非转让事件: " + event.getType());
        }
        return new BalanceSnapshot(available, frozen, consumed);
    }

    private BalanceSnapshot snapshot(QuotaAccount account) {
        return new BalanceSnapshot(account.getAvailable(), account.getFrozen(), account.getConsumed());
    }

    private void recordDecision(String requestId, String keyPrefix, Transfer transfer) {
        if (requestId != null) {
            decisionRepository.save(new IdempotentDecision(
                    keyPrefix + requestId, transfer.getStatus().name(), transfer.getId().toString()));
        }
    }

    /**
     * 唯一约束冲突后的幂等重放：请求号必须作用于同一转让单，返回单据当前状态；
     * 已用于其他转让单则返回 409。
     */
    private Transfer replayAction(String keyPrefix, String requestId, Long transferId) {
        if (requestId == null) {
            throw BusinessException.conflict("转让处理冲突，请重试: " + transferId);
        }
        return transactionTemplate.execute(status -> {
            var decision = decisionRepository.findByIdempotencyKey(keyPrefix + requestId);
            if (decision.isPresent()) {
                return requireSameTarget(decision.get(), transferId);
            }
            throw BusinessException.conflict("转让请求处理冲突，请重试: " + requestId);
        });
    }

    private Transfer requireSameTarget(IdempotentDecision decision, Long transferId) {
        if (!decision.getTargetRef().equals(transferId.toString())) {
            throw BusinessException.conflict("请求号已用于其他转让单");
        }
        return getTransfer(transferId);
    }

    private Transfer lockTransfer(Long transferId) {
        return transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
    }

    private void requirePending(Transfer transfer) {
        if (!transfer.isPending()) {
            throw BusinessException.conflict("转让已终结，当前状态: " + transfer.getStatus());
        }
    }

    private void doExpire(Transfer transfer) {
        releaseFrozen(transfer);
        transfer.markExpired();
    }

    private void releaseFrozen(Transfer transfer) {
        QuotaAccount from = lockAccount(transfer.getSeason(), transfer.getSpecies(), transfer.getFromHolder());
        from.releaseFrozen(transfer.getQuantity());
        assertConsistent(from);
        String reference = transfer.getId().toString();
        holdFor(reference).markReleased();
        ledgerRepository.save(new LedgerEvent(from, LedgerEventType.TRANSFER_RELEASE,
                transfer.getQuantity(), reference));
    }

    private QuotaHold holdFor(String reference) {
        return holdRepository.findByHoldTypeAndReferenceId(HoldType.TRANSFER, reference)
                .orElseThrow(() -> BusinessException.notFound("转让冻结明细不存在: " + reference));
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

    /** 账户某一时刻的三余额快照。 */
    public record BalanceSnapshot(BigDecimal available, BigDecimal frozen, BigDecimal consumed) {
    }

    /** 与一笔转让相关的单条台账事件，含事件前后余额快照与发生时间。 */
    public record LedgerEntryView(Long id, String type, BigDecimal quantity, BalanceSnapshot before,
                                  BalanceSnapshot after, String occurredAt) {
    }

    /** 转让冻结明细视图。 */
    public record HoldView(String holdType, String referenceId, BigDecimal quantity, String status,
                           String createdAt, String resolvedAt) {
    }

    /** 转让一方（转让方 / 受让方）许可证的前后余额、台账事件与冻结明细。 */
    public record PartyView(String holder, Long accountId, boolean accountExists, BalanceSnapshot before,
                            BalanceSnapshot after, List<LedgerEntryView> events, HoldView hold) {
    }

    /** 转让单详情：状态、时间与双方核对视图。 */
    public record TransferDetail(Long id, String season, String species, String fromHolder, String toHolder,
                                 BigDecimal quantity, String status, String createdAt, String expiresAt,
                                 String resolvedAt, PartyView from, PartyView to) {
    }

    /**
     * 可转余额分解视图。
     *
     * @param available           当前可用余额（即可转余额）
     * @param frozen              当前冻结总额
     * @param consumed            已核销（已上岸）总量
     * @param transferable        可转余额；排除本笔转让冻结时把该笔冻结算回
     * @param landed              已上岸数量（= consumed）
     * @param occupiedByOthers    被其他申请暂时占用的冻结量
     * @param transferHeld        其中：被其他转让申请占用
     * @param landingHeld         其中：被卸港待复核占用
     * @param correctionHeld      其中：被更正待复核占用
     */
    public record AvailabilityView(String season, String species, String holder, Long accountId, long version,
                                   BigDecimal available, BigDecimal frozen, BigDecimal consumed,
                                   BigDecimal transferable, BigDecimal landed, BigDecimal occupiedByOthers,
                                   BigDecimal transferHeld, BigDecimal landingHeld, BigDecimal correctionHeld,
                                   Long excludedTransferId) {
    }
}
