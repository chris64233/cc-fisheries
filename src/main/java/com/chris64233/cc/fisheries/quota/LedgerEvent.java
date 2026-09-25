package com.chris64233.cc.fisheries.quota;

import java.math.BigDecimal;
import java.time.Instant;

import com.chris64233.cc.fisheries.common.Quantities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 不可变台账事件：只在余额变化成功时写入，记录变化后的三个余额快照。
 */
@Entity
@Table(name = "ledger_event", indexes = @Index(name = "idx_ledger_account", columnList = "accountId, id"))
public class LedgerEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long accountId;

    @Column(nullable = false, updatable = false, length = 32)
    private String season;

    @Column(nullable = false, updatable = false, length = 64)
    private String species;

    @Column(nullable = false, updatable = false, length = 64)
    private String holder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private LedgerEventType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal availableAfter;

    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal frozenAfter;

    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal consumedAfter;

    /** 关联业务单号：转让 ID 或卸港事件号 */
    @Column(updatable = false, length = 64)
    private String reference;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected LedgerEvent() {
    }

    public LedgerEvent(QuotaAccount account, LedgerEventType type, BigDecimal quantity, String reference) {
        this.accountId = account.getId();
        this.season = account.getSeason();
        this.species = account.getSpecies();
        this.holder = account.getHolder();
        this.type = type;
        this.quantity = quantity;
        this.availableAfter = account.getAvailable();
        this.frozenAfter = account.getFrozen();
        this.consumedAfter = account.getConsumed();
        this.reference = reference;
        this.occurredAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getSeason() {
        return season;
    }

    public String getSpecies() {
        return species;
    }

    public String getHolder() {
        return holder;
    }

    public LedgerEventType getType() {
        return type;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getAvailableAfter() {
        return availableAfter;
    }

    public BigDecimal getFrozenAfter() {
        return frozenAfter;
    }

    public BigDecimal getConsumedAfter() {
        return consumedAfter;
    }

    public String getReference() {
        return reference;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
