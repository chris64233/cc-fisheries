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
 * 已核销申报的称重更正：更正号全局唯一，引用原申报但绝不覆盖原台账。
 *
 * <p>创建时记录所基于的申报版本（{@code expectedLandingVersion}）与配额账户版本
 * （{@code expectedAccountVersion}）；确认时两者任一已变化即拒绝旧决定（409），
 * 整个差额处理在单事务内完成，失败不写入任何部分差额台账。
 */
@Entity
@Table(name = "landing_correction",
        uniqueConstraints = @UniqueConstraint(name = "uk_correction_no", columnNames = "correctionNo"),
        indexes = @Index(name = "idx_correction_landing", columnList = "landingId"))
public class LandingCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 64)
    private String correctionNo;

    @Column(nullable = false, updatable = false)
    private Long landingId;

    /** 创建时申报的当前生效重量（更正基准）。 */
    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal originalWeight;

    /** 更正后重量。 */
    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal correctedWeight;

    /** 创建时申报版本：确认时不一致则拒绝。 */
    @Column(nullable = false, updatable = false)
    private long expectedLandingVersion;

    /** 创建时配额账户版本：确认时不一致则拒绝。 */
    @Column(nullable = false, updatable = false)
    private long expectedAccountVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LandingCorrectionStatus status = LandingCorrectionStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column
    private Instant confirmedAt;

    protected LandingCorrection() {
    }

    public LandingCorrection(String correctionNo, Long landingId, BigDecimal originalWeight,
                             BigDecimal correctedWeight, long expectedLandingVersion,
                             long expectedAccountVersion) {
        this.correctionNo = correctionNo;
        this.landingId = landingId;
        this.originalWeight = originalWeight;
        this.correctedWeight = correctedWeight;
        this.expectedLandingVersion = expectedLandingVersion;
        this.expectedAccountVersion = expectedAccountVersion;
        this.createdAt = Instant.now();
    }

    public void markConfirmed() {
        this.status = LandingCorrectionStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
    }

    public boolean isPending() {
        return status == LandingCorrectionStatus.PENDING;
    }

    public boolean isConfirmed() {
        return status == LandingCorrectionStatus.CONFIRMED;
    }

    public Long getId() {
        return id;
    }

    public String getCorrectionNo() {
        return correctionNo;
    }

    public Long getLandingId() {
        return landingId;
    }

    public BigDecimal getOriginalWeight() {
        return originalWeight;
    }

    public BigDecimal getCorrectedWeight() {
        return correctedWeight;
    }

    public long getExpectedLandingVersion() {
        return expectedLandingVersion;
    }

    public long getExpectedAccountVersion() {
        return expectedAccountVersion;
    }

    public LandingCorrectionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
