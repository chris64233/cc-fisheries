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
import com.chris64233.cc.fisheries.landing.LandingRecordRepository;
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
 * 配额转让：发起时冻结转让方的<b>可转余额</b>，接受时同事务扣减转让方、增加受让方，
 * 拒绝、转让方取消或到期时完整释放冻结量。
 *
 * <p>申请期间把来源账户额度拆分为三个互斥口径：
 * <ul>
 *   <li><b>可转余额（transferable）</b>＝账户可用余额 available，未被任何单据占用，可直接发起转让；</li>
 *   <li><b>已上岸（landed）</b>＝已核销 consumed，即已确认/已更正卸港重量，永久占用、不能转让；</li>
 *   <li><b>被占用（reserved）</b>＝冻结 frozen，进一步区分被其它待处理转让占用
 *       （reservedByTransfers）与被待复核卸港/增重更正占用（reservedByPendingLanding）。</li>
 * </ul>
 *
 * <p>发起、接受、拒绝、取消均以外部请求号幂等：相同请求号重放返回原单据，不重复冻结、
 * 结算或释放；相同请求号作用于不同单据返回 409；并发下由唯一约束兜底。
 * 冻结量只会被释放或结算一次，任何失败整体回滚，绝无只改一方余额的情况。
 */
@Service
public class TransferService {

    static final Duration DEFAULT_TTL = Duration.ofHours(24);

    static final String ACCEPT_KEY_PREFIX = "TRANSFER-ACCEPT:";
    static final String REJECT_KEY_PREFIX = "TRANSFER-REJECT:";
    static final String CANCEL_KEY_PREFIX = "TRANSFER-CANCEL:";

    private final TransferRepository transferRepository;
    private final QuotaAccountRepository accountRepository;
    private final LedgerEventRepository ledgerRepository;
    private final QuotaHoldRepository holdRepository;
    private final LandingRecordRepository landingRepository;
    private final IdempotentDecisionRepository decisionRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<TransferService> selfProvider;

    public TransferService(TransferRepository transferRepository,
                           QuotaAccountRepository accountRepository,
                           LedgerEventRepository ledgerRepository,
                           QuotaHoldRepository holdRepository,
                           LandingRecordRepository landingRepository,
                           IdempotentDecisionRepository decisionRepository,
                           TransactionTemplate transactionTemplate,
                           ObjectProvider<TransferService> selfProvider) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.holdRepository = holdRepository;
        this.landingRepository = landingRepository;
        this.decisionRepository = decisionRepository;
        this.transactionTemplate = transactionTemplate;
        this.selfProvider = selfProvider;
    }

    /**
     * 发起转让：从转让方可转余额冻结相应数量（不足则拒绝、不落任何记录）。
     * 相同外部请求号 + 相同内容重放幂等返回原单；内容不同返回 409；并发同请求号由唯一约束兜底。
     */
    public Transfer initiate(String requestId, String season, String species, String fromHolder,
                             String toHolder, BigDecimal quantity, Duration ttl) {
        Quantities.requireText(requestId, "外部请求号");
        BigDecimal amount = Quantities.requirePositive(quantity, "转让数量");
        if (fromHolder.equals(toHolder)) {
            throw BusinessException.unprocessable("转让方与受让方不能相同");
        }
        try {
            return transactionTemplate.execute(
                    status -> doInitiate(requestId, season, species, fromHolder, toHolder, amount, ttl));
        } catch (DataIntegrityViolationException e) {
            // 并发下两个相同请求号同时插入：唯一约束拦截，本事务已回滚，按已存在单据处理
            return resolveExistingRequest(requestId, season, species, fromHolder, toHolder, amount);
        }
    }

    private Transfer doInitiate(String requestId, String season, String species, String fromHolder,
                                String toHolder, BigDecimal amount, Duration ttl) {
        var existing = transferRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return verifyInitiateReplay(existing.get(), season, species, fromHolder, toHolder, amount);
        }
        QuotaAccount from = lockAccount(season, species, fromHolder);
        if (from.getAvailable().compareTo(amount) < 0) {
            throw BusinessException.unprocessable(
                    "可转余额不足：可转 " + from.getAvailable() + "，申请 " + amount);
        }
        from.freeze(amount);
        assertConsistent(from);
        Transfer transfer = transferRepository.save(new Transfer(
                requestId, season, species, fromHolder, toHolder, amount,
                Instant.now().plus(ttl == null ? DEFAULT_TTL : ttl)));
        String reference = transfer.getId().toString();
        ledgerRepository.save(new LedgerEvent(from, LedgerEventType.TRANSFER_FREEZE, amount, reference));
        holdRepository.save(new QuotaHold(from, HoldType.TRANSFER, reference, amount));
        return transfer;
    }

    private Transfer resolveExistingRequest(String requestId, String season, String species,
                                            String fromHolder, String toHolder, BigDecimal amount) {
        // 触发唯一约束时，获胜事务可能尚未提交，短暂重试等待其可见
        for (int attempt = 0; attempt < 20; attempt++) {
            var transfer = transferRepository.findByRequestId(requestId);
            if (transfer.isPresent()) {
                return verifyInitiateReplay(transfer.get(), season, species, fromHolder, toHolder, amount);
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

    private Transfer verifyInitiateReplay(Transfer transfer, String season, String species,
                                          String fromHolder, String toHolder, BigDecimal amount) {
        if (!transfer.matchesContent(season, species, fromHolder, toHolder, amount)) {
            throw BusinessException.conflict("转让请求号已存在且内容不一致: " + transfer.getRequestId());
        }
        return transfer;
    }

    /**
     * 受让方接受：同事务内扣减转让方冻结量、增加受让方可用量，两边台账成对落库。
     * 接受请求号幂等；失败整体回滚，不会只改一方。
     */
    public Transfer accept(Long transferId, String requestId, String reviewer) {
        Quantities.requireText(requestId, "外部请求号");
        Quantities.requireText(reviewer, "确认人");
        try {
            return transactionTemplate.execute(status -> doAccept(transferId, requestId));
        } catch (DataIntegrityViolationException e) {
            return replayDecisionAfterKeyClash(ACCEPT_KEY_PREFIX, requestId, transferId);
        }
    }

    private Transfer doAccept(Long transferId, String requestId) {
        String key = ACCEPT_KEY_PREFIX + requestId;
        // 加锁前用标量投影预判到期：此时不持有转让行锁，到期释放可在独立事务中拿到同一行锁
        var meta = transferRepository.findMetaById(transferId)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
        if (meta.getStatus() == TransferStatus.PENDING
                && !meta.getExpiresAt().isAfter(Instant.now())) {
            self().expireOne(transferId);
            throw BusinessException.conflict("转让已到期，冻结量已释放: " + transferId);
        }
        // 再锁转让行并查幂等决策：并发同请求号在 winner 提交后串行读到已登记决策，按重放返回
        Transfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
        var prior = decisionRepository.findByIdempotencyKey(key);
        if (prior.isPresent()) {
            return replayDecisionLocked(prior.get(), transfer);
        }
        if (!transfer.isPending()) {
            throw BusinessException.conflict("转让已终结，当前状态: " + transfer.getStatus());
        }
        if (transfer.isExpired(Instant.now())) {
            // 持锁期间不可自锁释放，交由到期任务/expire-due 处理
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
        transfer.markAccepted(requestId);
        String reference = transfer.getId().toString();
        holdFor(reference).markSettled();
        ledgerRepository.save(new LedgerEvent(from, LedgerEventType.TRANSFER_OUT, transfer.getQuantity(), reference));
        ledgerRepository.save(new LedgerEvent(to, LedgerEventType.TRANSFER_IN, transfer.getQuantity(), reference));
        decisionRepository.save(new IdempotentDecision(key, transfer.getStatus().name(), reference));
        return transfer;
    }

    /**
     * 受让方拒绝：完整释放转让方冻结量，只生效一次。拒绝请求号幂等。
     */
    public Transfer reject(Long transferId, String requestId, String reviewer) {
        Quantities.requireText(requestId, "外部请求号");
        Quantities.requireText(reviewer, "确认人");
        try {
            return transactionTemplate.execute(status -> doReject(transferId, requestId));
        } catch (DataIntegrityViolationException e) {
            return replayDecisionAfterKeyClash(REJECT_KEY_PREFIX, requestId, transferId);
        }
    }

    private Transfer doReject(Long transferId, String requestId) {
        String key = REJECT_KEY_PREFIX + requestId;
        Transfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
        var prior = decisionRepository.findByIdempotencyKey(key);
        if (prior.isPresent()) {
            return replayDecisionLocked(prior.get(), transfer);
        }
        if (!transfer.isPending()) {
            throw BusinessException.conflict("转让已终结，当前状态: " + transfer.getStatus());
        }
        releaseFrozen(transfer);
        transfer.markRejected(requestId);
        decisionRepository.save(new IdempotentDecision(key, transfer.getStatus().name(),
                transfer.getId().toString()));
        return transfer;
    }

    /**
     * 转让方取消：完整释放冻结量，只生效一次。取消请求号幂等。
     * 仅转让发起人本人可取消；非 PENDING 单据不能取消。
     */
    public Transfer cancel(Long transferId, String requestId, String operator) {
        Quantities.requireText(requestId, "外部请求号");
        Quantities.requireText(operator, "操作人");
        try {
            return transactionTemplate.execute(status -> doCancel(transferId, requestId, operator));
        } catch (DataIntegrityViolationException e) {
            return replayDecisionAfterKeyClash(CANCEL_KEY_PREFIX, requestId, transferId);
        }
    }

    private Transfer doCancel(Long transferId, String requestId, String operator) {
        String key = CANCEL_KEY_PREFIX + requestId;
        Transfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
        var prior = decisionRepository.findByIdempotencyKey(key);
        if (prior.isPresent()) {
            return replayDecisionLocked(prior.get(), transfer);
        }
        if (!transfer.isPending()) {
            throw BusinessException.conflict("转让已终结，当前状态: " + transfer.getStatus());
        }
        if (!transfer.getFromHolder().equals(operator)) {
            throw BusinessException.unprocessable("只有转让发起方可以取消: " + transfer.getFromHolder());
        }
        if (transfer.isExpired(Instant.now())) {
            throw BusinessException.conflict("转让已到期，等待到期处理释放: " + transferId);
        }
        releaseFrozen(transfer);
        transfer.markCancelled(requestId);
        decisionRepository.save(new IdempotentDecision(key, transfer.getStatus().name(),
                transfer.getId().toString()));
        return transfer;
    }

    /**
     * 已在转让行锁内：幂等重放校验请求号作用于同一笔转让后返回当前单据，不再变更余额。
     */
    private Transfer replayDecisionLocked(IdempotentDecision decision, Transfer transfer) {
        if (!decision.getTargetRef().equals(transfer.getId().toString())) {
            throw BusinessException.conflict("外部请求号已用于其他转让: " + decision.getIdempotencyKey());
        }
        return transfer;
    }

    private Transfer replayDecisionAfterKeyClash(String prefix, String requestId, Long transferId) {
        return transactionTemplate.execute(status -> {
            var decision = decisionRepository.findByIdempotencyKey(prefix + requestId);
            if (decision.isEmpty()) {
                throw BusinessException.conflict("转让决策处理冲突，请重试: " + requestId);
            }
            Transfer transfer = transferRepository.findById(transferId)
                    .orElseThrow(() -> BusinessException.notFound("转让不存在: " + transferId));
            return replayDecisionLocked(decision.get(), transfer);
        });
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

    /**
     * 来源账户的额度分解：可转余额、已上岸数量、被占用数量（区分其它转让占用与待复核占用）。
     * 在账户行锁内读取（只读事务内同样持有写锁），保证返回的各口径与同一时刻的余额一致。
     */
    @Transactional
    public Availability getAvailability(String season, String species, String holder) {
        QuotaAccount account = lockAccount(season, species, holder);
        return buildAvailability(account);
    }

    /** 锁内构建额度分解；可在发起失败时复用同一账户快照。 */
    private Availability buildAvailability(QuotaAccount account) {
        BigDecimal transferHolds = scale(holdRepository.sumHeldByAccountAndType(
                account.getId(), HoldType.TRANSFER, HoldStatus.HELD));
        BigDecimal landingHolds = scale(holdRepository.sumHeldByAccountAndType(
                        account.getId(), HoldType.LANDING, HoldStatus.HELD))
                .add(holdRepository.sumHeldByAccountAndType(
                        account.getId(), HoldType.CORRECTION, HoldStatus.HELD));
        landingHolds = scale(landingHolds);
        BigDecimal landed = scale(landingRepository.sumConfirmedWeight(
                account.getSeason(), account.getSpecies(), account.getHolder()));
        return new Availability(account.getSeason(), account.getSpecies(), account.getHolder(),
                account.getAvailable(), scale(account.getFrozen()), scale(account.getConsumed()),
                landed, transferHolds, landingHolds);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? Quantities.ZERO : value.setScale(Quantities.SCALE);
    }

    /**
     * 转让详情视图：状态、时间、双方账户转让前后余额快照及对应台账事件，
     * 并校验双方台账与单据数量是否相互对应。
     */
    @Transactional(readOnly = true)
    public TransferView getTransferView(Long id) {
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("转让不存在: " + id));
        String reference = transfer.getId().toString();

        QuotaAccount from = accountRepository.findBySeasonAndSpeciesAndHolder(
                transfer.getSeason(), transfer.getSpecies(), transfer.getFromHolder())
                .orElseThrow(() -> BusinessException.notFound(
                        "转让方账户不存在: " + transfer.getFromHolder()));
        List<LedgerEvent> fromLedger = ledgerRepository.findByAccountIdOrderByIdAsc(from.getId());
        PartySide fromSide = buildPartySide(from, fromLedger, transfer, true);

        // 接受前受让方账户可能尚不存在（接受时要求存在）；无账户则该侧快照为空
        PartySide toSide = accountRepository.findBySeasonAndSpeciesAndHolder(
                        transfer.getSeason(), transfer.getSpecies(), transfer.getToHolder())
                .map(to -> buildPartySide(to,
                        ledgerRepository.findByAccountIdOrderByIdAsc(to.getId()), transfer, false))
                .orElse(null);

        return new TransferView(TransferSummary.from(transfer), fromSide, toSide,
                checkCorrespondence(transfer, fromSide, toSide));
    }

    /**
     * 还原一方账户与该转让相关的前后余额快照。
     *
     * @param outgoing 转让方为 true（冻结/转出），受让方为 false（转入）
     */
    private PartySide buildPartySide(QuotaAccount account, List<LedgerEvent> ledger,
                                     Transfer transfer, boolean outgoing) {
        String reference = transfer.getId().toString();
        List<LedgerEventView> events = ledger.stream()
                .filter(e -> reference.equals(e.getReference()))
                .map(LedgerEventView::from)
                .toList();

        BalanceSnapshot before = null;
        BalanceSnapshot after = null;
        if (outgoing) {
            LedgerEvent freeze = findEvent(ledger, reference, LedgerEventType.TRANSFER_FREEZE);
            if (freeze != null) {
                before = previousSnapshot(ledger, freeze);
                after = BalanceSnapshot.from(freeze);
            }
            LedgerEvent out = findEvent(ledger, reference, LedgerEventType.TRANSFER_OUT);
            if (out != null) {
                after = BalanceSnapshot.from(out);
            }
            LedgerEvent release = findEvent(ledger, reference, LedgerEventType.TRANSFER_RELEASE);
            if (release != null) {
                // 释放后恢复到转让发起前的可用口径
                after = BalanceSnapshot.from(release);
            }
        } else {
            LedgerEvent in = findEvent(ledger, reference, LedgerEventType.TRANSFER_IN);
            if (in != null) {
                before = previousSnapshot(ledger, in);
                after = BalanceSnapshot.from(in);
            }
        }
        return new PartySide(account.getId(), account.getHolder(), before, after, events);
    }

    private LedgerEvent findEvent(List<LedgerEvent> ledger, String reference, LedgerEventType type) {
        return ledger.stream()
                .filter(e -> reference.equals(e.getReference()) && e.getType() == type)
                .findFirst().orElse(null);
    }

    private BalanceSnapshot previousSnapshot(List<LedgerEvent> ledger, LedgerEvent event) {
        List<LedgerEvent> previous =
                ledgerRepository.findTop1ByAccountIdAndIdBeforeOrderByIdDesc(event.getAccountId(), event.getId());
        if (previous.isEmpty()) {
            // 账户首笔台账事件之前三个余额均为 0（账户开立前）
            return new BalanceSnapshot(Quantities.ZERO, Quantities.ZERO, Quantities.ZERO, null);
        }
        return BalanceSnapshot.from(previous.get(0));
    }

    /**
     * 双方余额与转让记录必须相互对应：
     * 转让方必须有冻结；终结态下双方转入/转出成对且数量等于单据数量，或存在一次完整释放。
     */
    private boolean checkCorrespondence(Transfer transfer, PartySide fromSide, PartySide toSide) {
        boolean fromHasFreeze = fromSide.events().stream()
                .anyMatch(e -> e.type() == LedgerEventType.TRANSFER_FREEZE.name()
                        && e.quantity().compareTo(transfer.getQuantity()) == 0);
        if (!fromHasFreeze) {
            return false;
        }
        boolean released = fromSide.events().stream()
                .anyMatch(e -> e.type() == LedgerEventType.TRANSFER_RELEASE.name());
        if (transfer.getStatus() == TransferStatus.PENDING) {
            // 待处理：只冻结、未结算、未释放
            return !released
                    && fromSide.events().stream()
                            .noneMatch(e -> e.type() == LedgerEventType.TRANSFER_OUT.name())
                    && (toSide == null || toSide.events().isEmpty());
        }
        if (transfer.getStatus() == TransferStatus.ACCEPTED) {
            boolean out = fromSide.events().stream()
                    .anyMatch(e -> e.type() == LedgerEventType.TRANSFER_OUT.name()
                            && e.quantity().compareTo(transfer.getQuantity()) == 0);
            boolean in = toSide != null && toSide.events().stream()
                    .anyMatch(e -> e.type() == LedgerEventType.TRANSFER_IN.name()
                            && e.quantity().compareTo(transfer.getQuantity()) == 0);
            return out && in && !released;
        }
        // 拒绝 / 取消 / 到期：恰好一次完整释放，受让方无入账
        return released
                && (toSide == null || toSide.events().stream()
                        .noneMatch(e -> e.type() == LedgerEventType.TRANSFER_IN.name()));
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

    /**
     * 来源账户额度分解（申请期间口径）。
     *
     * @param available                可转余额：即账户可用量，未被任何单据占用
     * @param frozen                   被占用总量（= reservedByTransfers + reservedByPendingLanding）
     * @param consumed                 已核销总量
     * @param landed                   已上岸数量（已确认/已更正卸港，已核销，不能转让）
     * @param reservedByTransfers      被其它待处理转让申请暂时占用的数量
     * @param reservedByPendingLanding 被待复核卸港 / 增重更正暂时占用的数量
     */
    public record Availability(String season, String species, String holder,
                               BigDecimal available, BigDecimal frozen, BigDecimal consumed,
                               BigDecimal landed, BigDecimal reservedByTransfers,
                               BigDecimal reservedByPendingLanding) {
    }

    /** 某一时点的三余额快照。 */
    public record BalanceSnapshot(BigDecimal available, BigDecimal frozen, BigDecimal consumed,
                                  String at) {
        static BalanceSnapshot from(LedgerEvent e) {
            return new BalanceSnapshot(e.getAvailableAfter(), e.getFrozenAfter(), e.getConsumedAfter(),
                    e.getOccurredAt().toString());
        }
    }

    /** 台账事件视图。 */
    public record LedgerEventView(String type, BigDecimal quantity, BigDecimal availableAfter,
                                  BigDecimal frozenAfter, BigDecimal consumedAfter, String at) {
        static LedgerEventView from(LedgerEvent e) {
            return new LedgerEventView(e.getType().name(), e.getQuantity(), e.getAvailableAfter(),
                    e.getFrozenAfter(), e.getConsumedAfter(), e.getOccurredAt().toString());
        }
    }

    /** 转让一方（转让方/受让方）的前后余额与台账轨迹。 */
    public record PartySide(Long accountId, String holder, BalanceSnapshot before, BalanceSnapshot after,
                            List<LedgerEventView> events) {
    }

    /**
     * 转让详情：单据状态/时间 + 双方前后余额 + 台账轨迹 + 双方台账与单据是否相互对应。
     */
    public record TransferView(TransferSummary transfer, PartySide from, PartySide to,
                               boolean balancesCorrespond) {
    }

    /** 转让单据摘要：状态与关键时间。 */
    public record TransferSummary(Long id, String requestId, String season, String species,
                                  String fromHolder, String toHolder, BigDecimal quantity,
                                  String status, String createdAt, String expiresAt, String resolvedAt) {
        static TransferSummary from(Transfer t) {
            return new TransferSummary(t.getId(), t.getRequestId(), t.getSeason(), t.getSpecies(),
                    t.getFromHolder(), t.getToHolder(), t.getQuantity(), t.getStatus().name(),
                    t.getCreatedAt().toString(), t.getExpiresAt().toString(),
                    t.getResolvedAt() == null ? null : t.getResolvedAt().toString());
        }
    }
}
