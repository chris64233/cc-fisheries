package com.chris64233.cc.fisheries.landing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LandingRecordRepository extends JpaRepository<LandingRecord, Long> {

    Optional<LandingRecord> findByEventId(String eventId);

    /**
     * 悲观写锁读取申报记录，复核与更正决策都必须先拿行锁，防止并发重复处理。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LandingRecord l where l.id = :id")
    Optional<LandingRecord> findByIdForUpdate(@Param("id") Long id);

    List<LandingRecord> findBySeasonAndSpeciesAndHolder(String season, String species, String holder);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LandingRecord l where l.eventId = :eventId")
    Optional<LandingRecord> findByEventIdForUpdate(@Param("eventId") String eventId);

    /**
     * 某账户已上岸（已确认/已更正，即已核销）的重量合计，按当前有效确认重量计。
     * 这部分额度对应账户的 consumed 余额，不能再转让。
     */
    @Query("select coalesce(sum(l.confirmedWeight), 0) from LandingRecord l "
            + "where l.season = :season and l.species = :species and l.holder = :holder "
            + "and l.status in (com.chris64233.cc.fisheries.landing.LandingStatus.CONFIRMED, "
            + "com.chris64233.cc.fisheries.landing.LandingStatus.CORRECTED)")
    BigDecimal sumConfirmedWeight(@Param("season") String season,
                                  @Param("species") String species,
                                  @Param("holder") String holder);
}
