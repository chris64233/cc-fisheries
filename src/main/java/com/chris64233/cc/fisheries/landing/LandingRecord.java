package com.chris64233.cc.fisheries.landing;

import java.math.BigDecimal;
import java.time.Instant;

import com.chris64233.cc.fisheries.common.Quantities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 卸港申报记录：事件号全局唯一，用于幂等重放与冲突检测。
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

    @Column(nullable = false, updatable = false, precision = 19, scale = Quantities.SCALE)
    private BigDecimal weight;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

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
}
