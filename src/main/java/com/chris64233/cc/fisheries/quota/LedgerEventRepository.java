package com.chris64233.cc.fisheries.quota;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEventRepository extends JpaRepository<LedgerEvent, Long> {

    List<LedgerEvent> findByAccountIdOrderByIdAsc(Long accountId);

    long countByAccountIdAndType(Long accountId, LedgerEventType type);
}
