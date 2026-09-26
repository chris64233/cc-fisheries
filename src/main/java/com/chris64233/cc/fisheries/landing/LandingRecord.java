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
import jakarta.persistence.Version;

/**
 * 卸港申报记录：事件号全局唯一，用于幂等重放与冲突检测。
 *
 * <p>原申报重量 {@code weight} 永不覆盖；当前生效重量 {@code confirmedWeight} 只随复核、
 * 更正成功的决定推进，每次推进 {@code version} 自增。更正基于的申报版本变化时，
 * 旧更正决定会因版本不匹配而被拒绝。
 */
@Entity
@Table(name = "landing_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_landing_event", columnNames = "eventId"),
        indexes = @Index(name = "idx_landing_holder", columnList = "season, species, holder"))
public class LandingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 64)
    private String eventId;

    @Column(nullable = false, updatable = false, length = 64)
    private String vessel;

    @Column(nullable = false, updatable = false, length = 64)
    private String holder;

    @Column(nullable = false, updatable = false, length = 64)
    private String species;

    @Column(nullable = false, updatable = false, length = 32)
    private String season;

    /** 原申报重量，永不覆盖。 */
    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal weight;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LandingStatus status = LandingStatus.PENDING_REVIEW;

    /** 当前生效重量：待复核时为申报重量，复核/更正成功后推进。 */
    @Column(nullable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal confirmedWeight;

    @Column
    private Instant reviewedAt;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    /** 申报版本：每被一次成功决定（复核、更正）推进自增，供更正做乐观并发控制。 */
    @Version
    @Column(nullable = false)
    private long version;

    protected LandingRecord() {
    }

    public LandingRecord(String eventId, String vessel, String holder, String species,
                         String season, BigDecimal weight) {
        this.eventId = eventId;
        this.vessel = vessel;
        this.holder = holder;
        this.species = species;
        this.season = season;
        this.weight = weight;
        this.confirmedWeight = weight;
        this.recordedAt = Instant.now();
    }

    public boolean matches(String vessel, String holder, String species, String season, BigDecimal weight) {
        return this.vessel.equals(vessel)
                && this.holder.equals(holder)
                && this.species.equals(species)
                && this.season.equals(season)
                && this.weight.compareTo(weight) == 0;
    }

    /** 复核或更正确认：推进生效重量并置为已核销。 */
    public void markConfirmed(BigDecimal newConfirmedWeight) {
        this.status = LandingStatus.CONFIRMED;
        this.confirmedWeight = newConfirmedWeight;
        this.reviewedAt = Instant.now();
    }

    public void markRejected() {
        this.status = LandingStatus.REJECTED;
        this.reviewedAt = Instant.now();
    }

    public boolean isPendingReview() {
        return status == LandingStatus.PENDING_REVIEW;
    }

    public boolean isConfirmed() {
        return status == LandingStatus.CONFIRMED;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getVessel() {
        return vessel;
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

    public BigDecimal getWeight() {
        return weight;
    }

    public LandingStatus getStatus() {
        return status;
    }

    public BigDecimal getConfirmedWeight() {
        return confirmedWeight;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public long getVersion() {
        return version;
    }
}
