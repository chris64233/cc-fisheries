package com.chris64233.cc.fisheries.hold;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotaHoldRepository extends JpaRepository<QuotaHold, Long> {

    Optional<QuotaHold> findByHoldTypeAndReferenceId(HoldType holdType, String referenceId);

    List<QuotaHold> findByAccountIdOrderByIdAsc(Long accountId);

    List<QuotaHold> findByAccountIdAndStatusOrderByIdAsc(Long accountId, HoldStatus status);

    /**
     * 某账户指定来源的持有中（HELD）冻结量合计。用于区分：
     * 被待处理转让占用的额度（TRANSFER）与待复核卸港/更正占用的额度。
     */
    @Query("select coalesce(sum(h.quantity), 0) from QuotaHold h "
            + "where h.accountId = :accountId and h.holdType = :holdType and h.status = :status")
    BigDecimal sumHeldByAccountAndType(@Param("accountId") Long accountId,
                                       @Param("holdType") HoldType holdType,
                                       @Param("status") HoldStatus status);
}
