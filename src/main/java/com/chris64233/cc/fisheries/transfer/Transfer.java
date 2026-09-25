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

/**
 * 配额转让单：转让双方必须属于同一捕捞季和物种（由账户自然键保证）。
 */
@Entity
@Table(name = "quota_transfer", indexes = {
        @Index(name = "idx_transfer_status_expiry", columnList = "status, expiresAt"),
        @Index(name = "idx_transfer_holders", columnList = "fromHolder, toHolder")})
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    protected Transfer() {
    }

    public Transfer(String season, String species, String fromHolder, String toHolder,
                    BigDecimal quantity, Instant expiresAt) {
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

    public void markAccepted() {
        this.status = TransferStatus.ACCEPTED;
        this.resolvedAt = Instant.now();
    }

    public void markRejected() {
        this.status = TransferStatus.REJECTED;
        this.resolvedAt = Instant.now();
    }

    public void markExpired() {
        this.status = TransferStatus.EXPIRED;
        this.resolvedAt = Instant.now();
    }
}
