package com.chris64233.cc.fisheries.landing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LandingReviewRepository extends JpaRepository<LandingReview, Long> {

    Optional<LandingReview> findByReviewEventId(String reviewEventId);

    Optional<LandingReview> findByLandingId(Long landingId);

    List<LandingReview> findBySeasonAndSpeciesAndHolder(String season, String species, String holder);
}
