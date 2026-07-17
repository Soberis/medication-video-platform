package com.medicationvideo.platform.mediaprivacy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PrivacyAssessmentRepository extends JpaRepository<PrivacyAssessment, UUID> {

	Optional<PrivacyAssessment> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

	Optional<PrivacyAssessment> findByPrivacyAssessmentIdAndProjectId(
			UUID privacyAssessmentId, UUID projectId);

	Optional<PrivacyAssessment>
			findTopByProjectIdAndClipAssetIdAndInputDigestOrderByAssessmentVersionDesc(
					UUID projectId, UUID clipAssetId, String inputDigest);
}
