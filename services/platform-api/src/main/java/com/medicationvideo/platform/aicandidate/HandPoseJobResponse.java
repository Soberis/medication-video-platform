package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.UUID;

record HandPoseJobResponse(UUID aiJobId, UUID projectId, UUID clipAssetId, String algorithmKey, String algorithmVersion,
		String modelSha256, String inputSha256, long inputSizeBytes, int samplingFps, String profileSha256,
		String status, String handResult, HandPoseRunSummary runSummary, String requestedBy, Instant createdAt,
		Instant updatedAt) {

	HandPoseJobResponse(UUID aiJobId, UUID projectId, UUID clipAssetId, String algorithmKey, String algorithmVersion,
			String modelSha256, String inputSha256, long inputSizeBytes, int samplingFps, String profileSha256,
			String status, String requestedBy, Instant createdAt, Instant updatedAt) {
		this(aiJobId, projectId, clipAssetId, algorithmKey, algorithmVersion, modelSha256, inputSha256, inputSizeBytes,
				samplingFps, profileSha256, status, "UNKNOWN", null, requestedBy, createdAt, updatedAt);
	}

	static HandPoseJobResponse from(AiCandidateJob job) {
		return from(job, null);
	}

	static HandPoseJobResponse from(AiCandidateJob job, AiCandidateJobAttempt attempt) {
		return new HandPoseJobResponse(job.getAiJobId(), job.getProjectId(), job.getClipAssetId(),
				job.getAlgorithmKey(), job.getAlgorithmVersion(), job.getModelSha256(), job.getInputSha256(),
				job.getInputSizeBytes(), job.getSamplingFps(), job.getProfileSha256(), job.getStatus().name(),
				job.getHandResult().name(),
				attempt == null || attempt.getStartedAt() == null ? null : HandPoseRunSummary.from(attempt),
				job.getRequestedBy(), job.getCreatedAt(), job.getUpdatedAt());
	}

}
