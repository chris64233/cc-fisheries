package com.chris64233.cc.fisheries.hold;

import java.math.BigDecimal;
import java.time.Instant;

import com.chris64233.cc.fisheries.common.Quantities;
import com.chris64233.cc.fisheries.quota.QuotaAccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 冻结明细：每一笔从可用量转入冻结量的数量都对应一条明细，
 * 同一冻结只能被核销（SETTLED）或释放（RELEASED）一次。
 *
 * <p>按 {@code (holdType, referenceId)} 唯一：转让单、卸港申报事件、更正号各对应一条。
 * 任一账户所有 {@code HELD} 明细数量之和必须等于该账户的 frozen 余额。
 */
@Entity
@Table(name = "quota_hold",
        uniqueConstraints = @UniqueConstraint(name = "uk_hold_reference",
                columnNames = {"holdType", "referenceId"}),
        indexes = {
                @Index(name = "idx_hold_account", columnList = "accountId, status"),
                @Index(name = "idx_hold_reference", columnList = "holdType, referenceId")})
public class QuotaHold {

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
    @Column(nullable = false, updatable = false, length = 16)
    private HoldType holdType;

    /** 关联单号：转让 ID、卸港事件号或更正号。 */
    @Column(nullable = false, updatable = false, length = 64)
    private String referenceId;

    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HoldStatus status = HoldStatus.HELD;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column
    private Instant resolvedAt;

    protected QuotaHold() {
    }

    public QuotaHold(QuotaAccount account, HoldType holdType, String referenceId, BigDecimal quantity) {
        this.accountId = account.getId();
        this.season = account.getSeason();
        this.species = account.getSpecies();
        this.holder = account.getHolder();
        this.holdType = holdType;
        this.referenceId = referenceId;
        this.quantity = quantity;
        this.createdAt = Instant.now();
    }

    /** 冻结量正式核销（不返回可用）。 */
    public void markSettled() {
        this.status = HoldStatus.SETTLED;
        this.resolvedAt = Instant.now();
    }

    /** 冻结量释放回可用。 */
    public void markReleased() {
        this.status = HoldStatus.RELEASED;
        this.resolvedAt = Instant.now();
    }

    public boolean isHeld() {
        return status == HoldStatus.HELD;
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

    public HoldType getHoldType() {
        return holdType;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
