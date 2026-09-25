package com.chris64233.cc.fisheries.quota;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByAccountIdOrderByIdAsc(Long accountId);
}
