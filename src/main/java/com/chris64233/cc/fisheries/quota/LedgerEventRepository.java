package com.chris64233.cc.fisheries.quota;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEventRepository extends JpaRepository<LedgerEvent, Long> {

    List<LedgerEvent> findByAccountIdOrderByIdAsc(Long accountId);

    List<LedgerEvent> findByAccountIdAndReferenceOrderByIdAsc(Long accountId, String reference);

    /** 某账户在指定台账事件之前的最后一条事件，用于还原转让前余额快照；账户首笔事件时为空。 */
    List<LedgerEvent> findTop1ByAccountIdAndIdBeforeOrderByIdDesc(Long accountId, Long id);

    long countByAccountIdAndType(Long accountId, LedgerEventType type);
}
