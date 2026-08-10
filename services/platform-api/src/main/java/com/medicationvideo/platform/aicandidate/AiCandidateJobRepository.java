package com.medicationvideo.platform.aicandidate;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AiCandidateJobRepository extends JpaRepository<AiCandidateJob, UUID> {

	Optional<AiCandidateJob> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

	Optional<AiCandidateJob> findByProjectIdAndClipAssetIdAndInputSha256AndAlgorithmKeyAndAlgorithmVersionAndModelSha256AndSamplingFps(
			UUID projectId, UUID clipAssetId, String inputSha256, String algorithmKey, String algorithmVersion,
			String modelSha256, int samplingFps);

}
