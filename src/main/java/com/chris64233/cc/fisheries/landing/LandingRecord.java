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
 * <p>申报原始内容（船、权利人、物种、季节、申报重量）创建后不可覆盖；
 * 复核与更正只推进状态机、版本号和当前有效重量，原始台账保留。
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

    /** 申报重量：原始申报值，任何更正都不会覆盖。 */
    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal weight;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LandingStatus status = LandingStatus.PENDING_REVIEW;

    /** 复核/更正每次生效后递增，用于并发决策的乐观版本校验。 */
    @Version
    @Column(nullable = false)
    private long version;

    /** 港口复核确认的重量（原申报确认时等于申报重量；更正链上以当前有效重量为准）。 */
    @Column(precision = 19, scale = Quantities.SCALE)
    private BigDecimal confirmedWeight;

    @Column
    private Instant confirmedAt;

    @Column(length = 64)
    private String reviewer;

    @Column
    private Instant rejectedAt;

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
        this.recordedAt = Instant.now();
    }

    public boolean matches(String vessel, String holder, String species, String season, BigDecimal weight) {
        return this.vessel.equals(vessel)
                && this.holder.equals(holder)
                && this.species.equals(species)
                && this.season.equals(season)
                && this.weight.compareTo(weight) == 0;
    }

    /** 复核通过：冻结量转为已核销。 */
    public void markConfirmed(BigDecimal confirmedWeight, String reviewer) {
        this.status = LandingStatus.CONFIRMED;
        this.confirmedWeight = confirmedWeight;
        this.confirmedAt = Instant.now();
        this.reviewer = reviewer;
    }

    /** 复核驳回：冻结量释放回可用。 */
    public void markRejected(String reviewer) {
        this.status = LandingStatus.REJECTED;
        this.rejectedAt = Instant.now();
        this.reviewer = reviewer;
    }

    /**
     * 更正生效：更新当前有效重量。第一次更正时状态由 CONFIRMED 转为 CORRECTED，
     * 后续更正保持 CORRECTED。
     */
    public void applyCorrection(BigDecimal newConfirmedWeight) {
        this.confirmedWeight = newConfirmedWeight;
        this.status = LandingStatus.CORRECTED;
    }

    public boolean isPendingReview() {
        return status == LandingStatus.PENDING_REVIEW;
    }

    public boolean isConfirmed() {
        return status == LandingStatus.CONFIRMED || status == LandingStatus.CORRECTED;
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

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public LandingStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public BigDecimal getConfirmedWeight() {
        return confirmedWeight;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public String getReviewer() {
        return reviewer;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }
}
