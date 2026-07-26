package com.medicationvideo.platform.qualitycleaning;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface QualityReviewRepository extends JpaRepository<QualityReview, UUID> {

	Optional<QualityReview> findByProjectIdAndClipAssetId(UUID projectId, UUID clipAssetId);

	List<QualityReview> findAllByProjectIdAndClipAssetIdIn(UUID projectId, Collection<UUID> clipAssetIds);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT review FROM QualityReview review
			WHERE review.projectId = :projectId AND review.clipAssetId = :clipAssetId
			""")
	Optional<QualityReview> findForUpdate(
			@Param("projectId") UUID projectId,
			@Param("clipAssetId") UUID clipAssetId);
}
