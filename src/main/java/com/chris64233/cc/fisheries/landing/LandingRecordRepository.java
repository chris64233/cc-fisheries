package com.chris64233.cc.fisheries.landing;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LandingRecordRepository extends JpaRepository<LandingRecord, Long> {

    Optional<LandingRecord> findByEventId(String eventId);

    List<LandingRecord> findByHolderOrderByIdAsc(String holder);
}
