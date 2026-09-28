package com.chris64233.cc.fisheries.transfer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") Long id);

    Optional<Transfer> findByRequestId(String requestId);

    /**
     * 加锁前的到期预判：只返回状态/到期时间的标量投影，不读取实体、不加行锁、
     * 不污染持久化上下文，避免在持有转让行锁时再开 REQUIRES_NEW 事务去到期同一行（自锁）。
     */
    @Query("select t.status as status, t.expiresAt as expiresAt from Transfer t where t.id = :id")
    Optional<TransferMeta> findMetaById(@Param("id") Long id);

    List<Transfer> findByStatusAndExpiresAtBefore(TransferStatus status, Instant now);

    List<Transfer> findBySeasonAndSpecies(String season, String species);

    interface TransferMeta {
        TransferStatus getStatus();

        Instant getExpiresAt();
    }
}
