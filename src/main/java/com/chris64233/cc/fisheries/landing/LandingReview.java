package com.chris64233.cc.fisheries.landing;

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
 * 港口复核记录：复核事件号全局唯一，重复提交幂等返回；同一申报只能有一条复核。
 */
@Entity
@Table(name = "landing_review",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_review_event", columnNames = "reviewEventId"),
                @UniqueConstraint(name = "uk_review_landing", columnNames = "landingId")},
        indexes = @Index(name = "idx_review_holder", columnList = "season, species, holder"))
public class LandingReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 64)
    private String reviewEventId;

    @Column(nullable = false, updatable = false)
    private Long landingId;

    /** 冗余自然键，便于冻结明细与台账核对。 */
    @Column(nullable = false, updatable = false, length = 64)
    private String holder;

    @Column(nullable = false, updatable = false, length = 64)
    private String species;

    @Column(nullable = false, updatable = false, length = 32)
    private String season;

    @Column(nullable = false, updatable = false, length = 64)
    private String reviewer;

    /** 申报重量（复核时快照）。 */
    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal declaredWeight;

    /** 复核结论：CONFIRMED / REJECTED。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private LandingStatus decision;

    /** 确认重量；拒绝时为空。 */
    @Column(updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal confirmedWeight;

    @Column(nullable = false, updatable = false)
    private Instant reviewedAt;

    protected LandingReview() {
    }

    public LandingReview(String reviewEventId, Long landingId, String holder, String species, String season,
                         String reviewer, BigDecimal declaredWeight, LandingStatus decision,
                         BigDecimal confirmedWeight) {
        this.reviewEventId = reviewEventId;
        this.landingId = landingId;
        this.holder = holder;
        this.species = species;
        this.season = season;
        this.reviewer = reviewer;
        this.declaredWeight = declaredWeight;
        this.decision = decision;
        this.confirmedWeight = confirmedWeight;
        this.reviewedAt = Instant.now();
    }

    public boolean sameDecision(LandingStatus decision, BigDecimal confirmedWeight) {
        if (this.decision != decision) {
            return false;
        }
        if (decision == LandingStatus.REJECTED) {
            return confirmedWeight == null;
        }
        return confirmedWeight != null && this.confirmedWeight.compareTo(confirmedWeight) == 0;
    }

    public Long getId() {
        return id;
    }

    public String getReviewEventId() {
        return reviewEventId;
    }

    public Long getLandingId() {
        return landingId;
    }

    public String getHolder() {
        return holder;
    }

    public String getSpecies() {
        return species;
    }

    public String getSeason() {
        return season;
    }

    public String getReviewer() {
        return reviewer;
    }

    public BigDecimal getDeclaredWeight() {
        return declaredWeight;
    }

    public LandingStatus getDecision() {
        return decision;
    }

    public BigDecimal getConfirmedWeight() {
        return confirmedWeight;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
