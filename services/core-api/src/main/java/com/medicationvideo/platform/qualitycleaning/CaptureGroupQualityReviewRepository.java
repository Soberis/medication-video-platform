package com.medicationvideo.platform.qualitycleaning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface CaptureGroupQualityReviewRepository extends JpaRepository<CaptureGroupQualityReview, UUID> {

	Optional<CaptureGroupQualityReview> findByProjectIdAndCaptureGroupIdAndReviewerActorIdAndIdempotencyKey(
			UUID projectId, UUID captureGroupId, String reviewerActorId, String idempotencyKey);

	Optional<CaptureGroupQualityReview> findTopByProjectIdAndCaptureGroupIdOrderByRevisionDesc(UUID projectId,
			UUID captureGroupId);

	List<CaptureGroupQualityReview> findAllByProjectIdAndCaptureGroupIdInOrderByCaptureGroupIdAscRevisionDesc(
			UUID projectId, List<UUID> captureGroupIds);

	List<CaptureGroupQualityReview> findAllByProjectIdAndCaptureGroupIdOrderByRevisionAsc(UUID projectId,
			UUID captureGroupId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select review from CaptureGroupQualityReview review
			where review.projectId = :projectId and review.captureGroupId = :captureGroupId
			order by review.revision desc
			""")
	List<CaptureGroupQualityReview> findForUpdate(@Param("projectId") UUID projectId,
			@Param("captureGroupId") UUID captureGroupId);

}
