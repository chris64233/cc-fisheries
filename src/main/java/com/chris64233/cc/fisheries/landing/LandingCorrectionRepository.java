package com.chris64233.cc.fisheries.landing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LandingCorrectionRepository extends JpaRepository<LandingCorrection, Long> {

    Optional<LandingCorrection> findByCorrectionNo(String correctionNo);

    /** 悲观写锁：并发确认同一更正时串行化，第二个决定按幂等/版本规则处理。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LandingCorrection c where c.correctionNo = :correctionNo")
    Optional<LandingCorrection> findByCorrectionNoForUpdate(@Param("correctionNo") String correctionNo);

    List<LandingCorrection> findByLandingIdOrderByIdAsc(Long landingId);
}
