package com.chris64233.cc.fisheries.hold;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotaHoldRepository extends JpaRepository<QuotaHold, Long> {

    Optional<QuotaHold> findByHoldTypeAndReferenceId(HoldType holdType, String referenceId);

    /** 按账户 + 单据定位冻结明细：同一单号只会挂在来源（冻结）账户上。 */
    Optional<QuotaHold> findByAccountIdAndHoldTypeAndReferenceId(Long accountId, HoldType holdType,
                                                                 String referenceId);

    List<QuotaHold> findByAccountIdOrderByIdAsc(Long accountId);

    List<QuotaHold> findByAccountIdAndStatusOrderByIdAsc(Long accountId, HoldStatus status);
}
