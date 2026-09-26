package com.chris64233.cc.fisheries.correction;

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
 * 卸港称重更正：引用一条已核销（已确认）的原申报，原申报台账不可覆盖。
 *
 * <p>更正记录创建时拍下原申报版本与账户版本；复核确认时若原申报或账户版本已变化，
 * 说明决定依据过期，拒绝旧决定（409）。增重先冻结配额，减重不冻结、确认时只归还实际差额。
 */
@Entity
@Table(name = "landing_correction",
        uniqueConstraints = @UniqueConstraint(name = "uk_correction_id", columnNames = "correctionId"),
        indexes = @Index(name = "idx_correction_original", columnList = "originalEventId"))
public class Correction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 更正号：全局唯一，创建与复核幂等的业务键。 */
    @Column(nullable = false, updatable = false, length = 64)
    private String correctionId;

    /** 原申报事件号。 */
    @Column(nullable = false, updatable = false, length = 64)
    private String originalEventId;

    @Column(nullable = false, updatable = false, length = 32)
    private String season;

    @Column(nullable = false, updatable = false, length = 64)
    private String species;

    @Column(nullable = false, updatable = false, length = 64)
    private String holder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private CorrectionDirection direction;

    /** 更正后的新重量（不是差额）。 */
    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal correctedWeight;

    /** 与原确认重量之差的绝对值（增重要冻结/核销的量，减重要归还的量）。 */
    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CorrectionStatus status = CorrectionStatus.PENDING;

    /** 创建更正时原申报的版本（JPA 版本号）。 */
    @Column(nullable = false, updatable = false)
    private long landingVersionAtCreate;

    /** 创建更正时配额账户的版本。 */
    @Column(nullable = false, updatable = false)
    private long accountVersionAtCreate;

    /** 原申报当时的已确认重量，留存依据。 */
    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal originalConfirmedWeight;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column
    private Instant resolvedAt;

    protected Correction() {
    }

    public Correction(String correctionId, String originalEventId, String season, String species,
                      String holder, CorrectionDirection direction, BigDecimal correctedWeight,
                      BigDecimal delta, long landingVersionAtCreate, long accountVersionAtCreate,
                      BigDecimal originalConfirmedWeight) {
        this.correctionId = correctionId;
        this.originalEventId = originalEventId;
        this.season = season;
        this.species = species;
        this.holder = holder;
        this.direction = direction;
        this.correctedWeight = correctedWeight;
        this.delta = delta;
        this.landingVersionAtCreate = landingVersionAtCreate;
        this.accountVersionAtCreate = accountVersionAtCreate;
        this.originalConfirmedWeight = originalConfirmedWeight;
        this.createdAt = Instant.now();
    }

    public void markConfirmed() {
        this.status = CorrectionStatus.CONFIRMED;
        this.resolvedAt = Instant.now();
    }

    public void markRejected() {
        this.status = CorrectionStatus.REJECTED;
        this.resolvedAt = Instant.now();
    }

    public boolean isPending() {
        return status == CorrectionStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public String getCorrectionId() {
        return correctionId;
    }

    public String getOriginalEventId() {
        return originalEventId;
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

    public CorrectionDirection getDirection() {
        return direction;
    }

    public BigDecimal getCorrectedWeight() {
        return correctedWeight;
    }

    public BigDecimal getDelta() {
        return delta;
    }

    public CorrectionStatus getStatus() {
        return status;
    }

    public long getLandingVersionAtCreate() {
        return landingVersionAtCreate;
    }

    public long getAccountVersionAtCreate() {
        return accountVersionAtCreate;
    }

    public BigDecimal getOriginalConfirmedWeight() {
        return originalConfirmedWeight;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
