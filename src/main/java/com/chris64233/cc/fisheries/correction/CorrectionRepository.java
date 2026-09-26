package com.chris64233.cc.fisheries.correction;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface CorrectionRepository extends JpaRepository<Correction, Long> {

    Optional<Correction> findByCorrectionId(String correctionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Correction c where c.correctionId = :correctionId")
    Optional<Correction> findByCorrectionIdForUpdate(@Param("correctionId") String correctionId);

    List<Correction> findByOriginalEventIdOrderByIdAsc(String originalEventId);
}
