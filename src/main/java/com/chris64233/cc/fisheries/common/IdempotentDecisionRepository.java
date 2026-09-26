package com.chris64233.cc.fisheries.common;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotentDecisionRepository extends JpaRepository<IdempotentDecision, Long> {

    Optional<IdempotentDecision> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
