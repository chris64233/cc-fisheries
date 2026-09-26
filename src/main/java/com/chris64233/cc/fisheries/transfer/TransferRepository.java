package com.chris64233.cc.fisheries.transfer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") Long id);

    List<Transfer> findByStatusAndExpiresAtBefore(TransferStatus status, Instant now);

    List<Transfer> findBySeasonAndSpecies(String season, String species);

    /** 某账户作为转让方、仍处于冻结中的转让单。 */
    @Query("select t from Transfer t where t.status = com.chris64233.cc.fisheries.transfer.TransferStatus.PENDING"
            + " and t.season = :season and t.species = :species and t.fromHolder = :holder")
    List<Transfer> findPendingFrom(@Param("season") String season, @Param("species") String species,
                                   @Param("holder") String holder);
}
