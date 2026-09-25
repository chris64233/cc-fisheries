package com.chris64233.cc.fisheries.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不可变台账事件：每次余额变化追加一条，记录变化后的三个余额快照。
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    public enum Type {
        APPROVE,
        TRANSFER_FREEZE,
        TRANSFER_OUT,
        TRANSFER_IN,
        TRANSFER_RELEASE,
        LANDING_CONSUME
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal availableAfter;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal transferFrozenAfter;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal consumedAfter;

    @Column(nullable = false, length = 32)
    private String referenceType;

    @Column(nullable = false)
    private Long referenceId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LedgerEntry() {
    }

    public LedgerEntry(QuotaAccount account, Type type, BigDecimal amount, String referenceType, Long referenceId) {
        this.accountId = account.getId();
        this.type = type;
        this.amount = amount;
        this.availableAfter = account.getAvailable();
        this.transferFrozenAfter = account.getTransferFrozen();
        this.consumedAfter = account.getConsumed();
        this.referenceType = referenceType;
        this.referenceId = referenceId;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Type getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getAvailableAfter() {
        return availableAfter;
    }

    public BigDecimal getTransferFrozenAfter() {
        return transferFrozenAfter;
    }

    public BigDecimal getConsumedAfter() {
        return consumedAfter;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
