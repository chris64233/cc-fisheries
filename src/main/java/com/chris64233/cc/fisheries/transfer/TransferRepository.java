package com.chris64233.cc.fisheries.transfer;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Transfer> findWithLockById(Long id);
}
