package com.chris64233.cc.fisheries.quota;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface QuotaAccountRepository extends JpaRepository<QuotaAccount, Long> {

    Optional<QuotaAccount> findBySeasonAndSpeciesAndHolder(String season, String species, String holder);

    /**
     * 悲观写锁读取账户，所有余额变更都必须先拿到行锁，保证并发下余额守恒。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from QuotaAccount a where a.season = :season and a.species = :species and a.holder = :holder")
    Optional<QuotaAccount> findForUpdate(@Param("season") String season,
                                         @Param("species") String species,
                                         @Param("holder") String holder);

    List<QuotaAccount> findBySeasonAndSpecies(String season, String species);
}
