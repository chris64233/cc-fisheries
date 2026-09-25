package com.chris64233.cc.fisheries.landing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "landing_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_landing_event", columnNames = "eventId"))
public class LandingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false, length = 64)
    private String vessel;

    @Column(nullable = false, length = 64)
    private String holder;

    @Column(nullable = false, length = 64)
    private String species;

    @Column(nullable = false, length = 32)
    private String season;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal weight;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

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
    }

    public boolean sameContent(String vessel, String holder, String species, String season, BigDecimal weight) {
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
