package com.medicationvideo.platform.aicandidate;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface AiCandidateJobRepository extends JpaRepository<AiCandidateJob, UUID> {

	Optional<AiCandidateJob> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT job FROM AiCandidateJob job WHERE job.aiJobId = :aiJobId")
	Optional<AiCandidateJob> findForUpdate(@Param("aiJobId") UUID aiJobId);

	Optional<AiCandidateJob> findByProjectIdAndClipAssetIdAndInputSha256AndAlgorithmKeyAndAlgorithmVersionAndModelSha256AndSamplingFpsAndProfileSha256(
			UUID projectId, UUID clipAssetId, String inputSha256, String algorithmKey, String algorithmVersion,
			String modelSha256, int samplingFps, String profileSha256);

	Optional<AiCandidateJob> findTopByProjectIdAndClipAssetIdAndAlgorithmKeyOrderByCreatedAtDesc(UUID projectId,
			UUID clipAssetId, String algorithmKey);

	java.util.List<AiCandidateJob> findAllByProjectIdAndClipAssetIdOrderByCreatedAtDesc(UUID projectId,
			UUID clipAssetId);

}
