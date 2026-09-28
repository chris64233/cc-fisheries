package com.chris64233.cc.fisheries.transfer;

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
import jakarta.persistence.UniqueConstraint;

/**
 * 配额转让单：转让双方必须属于同一捕捞季和物种（由账户自然键保证）。
 *
 * <p>{@code requestId} 是发起方提供的外部请求号，全局唯一：相同请求号重放幂等返回原单，
 * 相同请求号但内容不同返回 409，并发下由唯一约束兜底，只产生一笔冻结。
 */
@Entity
@Table(name = "quota_transfer",
        uniqueConstraints = @UniqueConstraint(name = "uk_transfer_request", columnNames = "requestId"),
        indexes = {
                @Index(name = "idx_transfer_status_expiry", columnList = "status, expiresAt"),
                @Index(name = "idx_transfer_holders", columnList = "fromHolder, toHolder")})
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发起方外部请求号，全局唯一，保证发起幂等。 */
    @Column(nullable = false, updatable = false, length = 64)
    private String requestId;

    @Column(nullable = false, updatable = false, length = 32)
    private String season;

    @Column(nullable = false, updatable = false, length = 64)
    private String species;

    @Column(nullable = false, updatable = false, length = 64)
    private String fromHolder;

    @Column(nullable = false, updatable = false, length = 64)
    private String toHolder;

    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferStatus status = TransferStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column
    private Instant resolvedAt;

    /** 接受方接受时使用的外部请求号，保证接受操作幂等。 */
    @Column(updatable = false, length = 64)
    private String acceptRequestId;

    /** 拒绝操作使用的外部请求号（拒绝方）。 */
    @Column(updatable = false, length = 64)
    private String rejectRequestId;

    /** 转让方取消操作使用的外部请求号。 */
    @Column(updatable = false, length = 64)
    private String cancelRequestId;

    protected Transfer() {
    }

    public Transfer(String requestId, String season, String species, String fromHolder, String toHolder,
                    BigDecimal quantity, Instant expiresAt) {
        this.requestId = requestId;
        this.season = season;
        this.species = species;
        this.fromHolder = fromHolder;
        this.toHolder = toHolder;
        this.quantity = quantity;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getSeason() {
        return season;
    }

    public String getSpecies() {
        return species;
    }

    public String getFromHolder() {
        return fromHolder;
    }

    public String getToHolder() {
        return toHolder;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public boolean isPending() {
        return status == TransferStatus.PENDING;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** 发起请求重放时校验业务内容一致（不含过期时间，外部重放通常不再携带 TTL）。 */
    public boolean matchesContent(String season, String species, String fromHolder, String toHolder,
                                  BigDecimal quantity) {
        return this.season.equals(season)
                && this.species.equals(species)
                && this.fromHolder.equals(fromHolder)
                && this.toHolder.equals(toHolder)
                && this.quantity.compareTo(quantity) == 0;
    }

    public void markAccepted(String acceptRequestId) {
        this.status = TransferStatus.ACCEPTED;
        this.acceptRequestId = acceptRequestId;
        this.resolvedAt = Instant.now();
    }

    public void markRejected(String rejectRequestId) {
        this.status = TransferStatus.REJECTED;
        this.rejectRequestId = rejectRequestId;
        this.resolvedAt = Instant.now();
    }

    public void markCancelled(String cancelRequestId) {
        this.status = TransferStatus.CANCELLED;
        this.cancelRequestId = cancelRequestId;
        this.resolvedAt = Instant.now();
    }

    public void markExpired() {
        this.status = TransferStatus.EXPIRED;
        this.resolvedAt = Instant.now();
    }

    public String getAcceptRequestId() {
        return acceptRequestId;
    }

    public String getRejectRequestId() {
        return rejectRequestId;
    }

    public String getCancelRequestId() {
        return cancelRequestId;
    }
}
