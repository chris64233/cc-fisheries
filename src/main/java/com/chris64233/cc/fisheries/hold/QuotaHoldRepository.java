package com.chris64233.cc.fisheries.hold;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotaHoldRepository extends JpaRepository<QuotaHold, Long> {

    Optional<QuotaHold> findByHoldTypeAndReferenceId(HoldType holdType, String referenceId);

    List<QuotaHold> findByAccountIdOrderByIdAsc(Long accountId);

    List<QuotaHold> findByAccountIdAndStatusOrderByIdAsc(Long accountId, HoldStatus status);
}
