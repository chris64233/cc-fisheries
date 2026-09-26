package com.chris64233.cc.fisheries.quota;

import java.math.BigDecimal;

import com.chris64233.cc.fisheries.common.Quantities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * 配额账户：按捕捞季 + 物种 + 权利人唯一，分别维护可用、转让冻结、已核销数量。
 */
@Entity
@Table(name = "quota_account", uniqueConstraints = @UniqueConstraint(
        name = "uk_quota_account_natural", columnNames = {"season", "species", "holder"}))
public class QuotaAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String season;

    @Column(nullable = false, length = 64)
    private String species;

    @Column(nullable = false, length = 64)
    private String holder;

    @Column(nullable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal available = Quantities.ZERO;

    @Column(nullable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal frozen = Quantities.ZERO;

    @Column(nullable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal consumed = Quantities.ZERO;

    /**
     * 乐观版本号：账户行每次更新递增。调用方在悲观锁内比对决策时看到的版本，
     * 版本已变化说明依据过期，拒绝旧决定（见更正复核）。
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected QuotaAccount() {
    }

    public QuotaAccount(String season, String species, String holder) {
        this.season = season;
        this.species = species;
        this.holder = holder;
    }

    public Long getId() {
        return id;
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

    public BigDecimal getAvailable() {
        return available;
    }

    public BigDecimal getFrozen() {
        return frozen;
    }

    public BigDecimal getConsumed() {
        return consumed;
    }

    /** 可用 + 冻结 + 已核销 = 账户持有总量，任何操作后必须守恒。 */
    public BigDecimal total() {
        return available.add(frozen).add(consumed);
    }

    public void credit(BigDecimal quantity) {
        this.available = this.available.add(quantity);
    }

    public void freeze(BigDecimal quantity) {
        this.available = this.available.subtract(quantity);
        this.frozen = this.frozen.add(quantity);
    }

    public void releaseFrozen(BigDecimal quantity) {
        this.frozen = this.frozen.subtract(quantity);
        this.available = this.available.add(quantity);
    }

    public void settleFrozen(BigDecimal quantity) {
        this.frozen = this.frozen.subtract(quantity);
    }

    /**
     * 复核通过：持有的冻结量正式核销（冻结减少、已核销增加），总量不变。
     */
    public void settleFrozenToConsumed(BigDecimal quantity) {
        this.frozen = this.frozen.subtract(quantity);
        this.consumed = this.consumed.add(quantity);
    }

    public void consume(BigDecimal quantity) {
        this.available = this.available.subtract(quantity);
        this.consumed = this.consumed.add(quantity);
    }

    /**
     * 更正减重：把已核销中实际多核销的差额归还可用（已核销减少、可用增加），总量不变。
     */
    public void refundConsumed(BigDecimal quantity) {
        this.consumed = this.consumed.subtract(quantity);
        this.available = this.available.add(quantity);
    }

    public long getVersion() {
        return version;
    }

    public boolean hasNegativeBalance() {
        return available.signum() < 0 || frozen.signum() < 0 || consumed.signum() < 0;
    }
}
