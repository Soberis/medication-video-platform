package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.UUID;

record HandPoseJobResponse(UUID aiJobId, UUID projectId, UUID clipAssetId, String algorithmKey, String algorithmVersion,
		String modelSha256, String inputSha256, long inputSizeBytes, int samplingFps, String status, String requestedBy,
		Instant createdAt, Instant updatedAt) {

	static HandPoseJobResponse from(AiCandidateJob job) {
		return new HandPoseJobResponse(job.getAiJobId(), job.getProjectId(), job.getClipAssetId(),
				job.getAlgorithmKey(), job.getAlgorithmVersion(), job.getModelSha256(), job.getInputSha256(),
				job.getInputSizeBytes(), job.getSamplingFps(), job.getStatus().name(), job.getRequestedBy(),
				job.getCreatedAt(), job.getUpdatedAt());
	}

}
