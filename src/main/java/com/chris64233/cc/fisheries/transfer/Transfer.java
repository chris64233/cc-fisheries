package com.chris64233.cc.fisheries.transfer;

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

@Entity
@Table(name = "quota_transfer")
public class Transfer {

    public enum Status {
        PENDING,
        ACCEPTED,
        REJECTED,
        EXPIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String season;

    @Column(nullable = false, length = 64)
    private String species;

    @Column(nullable = false, length = 64)
    private String fromHolder;

    @Column(nullable = false, length = 64)
    private String toHolder;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant expiresAt;

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
        this.expiresAt = expiresAt;
    }

    public boolean isPending() {
        return this.status == Status.PENDING;
    }

    public void markAccepted() {
        this.status = Status.ACCEPTED;
        this.resolvedAt = Instant.now();
    }

    public void markRejected() {
        this.status = Status.REJECTED;
        this.resolvedAt = Instant.now();
    }

    public void markExpired() {
        this.status = Status.EXPIRED;
        this.resolvedAt = Instant.now();
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

    public Status getStatus() {
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
}
