package com.chris64233.cc.fisheries.quota;

import com.chris64233.cc.fisheries.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;

/**
 * 配额账户：按捕捞季 + 物种 + 权利人唯一，维护可用、转让冻结、已核销三个余额。
 */
@Entity
@Table(name = "quota_account",
        uniqueConstraints = @UniqueConstraint(name = "uk_quota_account_combo",
                columnNames = {"season", "species", "holder"}))
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

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal available = BigDecimal.ZERO.setScale(3);

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal transferFrozen = BigDecimal.ZERO.setScale(3);

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal consumed = BigDecimal.ZERO.setScale(3);

    protected QuotaAccount() {
    }

    public QuotaAccount(String season, String species, String holder) {
        this.season = season;
        this.species = species;
        this.holder = holder;
    }

    public void credit(BigDecimal amount) {
        this.available = this.available.add(amount);
    }

    public void freeze(BigDecimal amount) {
        if (this.available.compareTo(amount) < 0) {
            throw ApiException.conflict("可用配额不足，无法冻结");
        }
        this.available = this.available.subtract(amount);
        this.transferFrozen = this.transferFrozen.add(amount);
    }

    public void releaseFrozen(BigDecimal amount) {
        if (this.transferFrozen.compareTo(amount) < 0) {
            throw ApiException.conflict("冻结配额不足，无法释放");
        }
        this.transferFrozen = this.transferFrozen.subtract(amount);
        this.available = this.available.add(amount);
    }

    public void settleTransferOut(BigDecimal amount) {
        if (this.transferFrozen.compareTo(amount) < 0) {
            throw ApiException.conflict("冻结配额不足，无法完成转让");
        }
        this.transferFrozen = this.transferFrozen.subtract(amount);
    }

    public void consume(BigDecimal amount) {
        if (this.available.compareTo(amount) < 0) {
            throw ApiException.conflict("可用配额不足，无法核销卸港重量");
        }
        this.available = this.available.subtract(amount);
        this.consumed = this.consumed.add(amount);
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

    public BigDecimal getTransferFrozen() {
        return transferFrozen;
    }

    public BigDecimal getConsumed() {
        return consumed;
    }
}
