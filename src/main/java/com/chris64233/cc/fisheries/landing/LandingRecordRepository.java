package com.chris64233.cc.fisheries.landing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LandingRecordRepository extends JpaRepository<LandingRecord, Long> {

    Optional<LandingRecord> findByEventId(String eventId);

    List<LandingRecord> findBySeasonAndSpeciesAndHolder(String season, String species, String holder);

    /** 某账户下所有待复核（冻结中）的申报。 */
    List<LandingRecord> findBySeasonAndSpeciesAndHolderAndStatus(
            String season, String species, String holder, LandingStatus status);

    /**
     * 悲观写锁读取申报：复核、更正确认都必须先拿申报行锁，
     * 与账户行锁共同串行化所有差额决定。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LandingRecord l where l.id = :id")
    Optional<LandingRecord> findByIdForUpdate(@Param("id") Long id);

    /** 悲观写锁按事件号读取申报，避免先无锁加载再加锁造成的版本冲突。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LandingRecord l where l.eventId = :eventId")
    Optional<LandingRecord> findByEventIdForUpdate(@Param("eventId") String eventId);
}
