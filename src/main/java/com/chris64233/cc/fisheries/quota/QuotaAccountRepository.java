package com.chris64233.cc.fisheries.quota;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotaAccountRepository extends JpaRepository<QuotaAccount, Long> {

    Optional<QuotaAccount> findBySeasonAndSpeciesAndHolder(String season, String species, String holder);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from QuotaAccount a where a.season = :season and a.species = :species and a.holder = :holder")
    Optional<QuotaAccount> findForUpdate(@Param("season") String season,
                                         @Param("species") String species,
                                         @Param("holder") String holder);
}
