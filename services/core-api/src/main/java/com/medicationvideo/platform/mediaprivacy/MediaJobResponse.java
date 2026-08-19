package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

record MediaJobResponse(UUID mediaJobId, UUID projectId, UUID clipAssetId, MediaJobType jobType, MediaJobStatus status,
		String inputDigest, long inputSizeBytes, String contentType, String probeProfileKey, String recipeDigest,
		MediaJobAttemptResponse currentAttempt, Instant createdAt, Instant updatedAt) {

	static MediaJobResponse from(MediaJob job, MediaJobAttempt attempt) {
		return new MediaJobResponse(job.getMediaJobId(), job.getProjectId(), job.getClipAssetId(), job.getJobType(),
				job.getStatus(), job.getInputDigest(), job.getInputSizeBytes(), job.getContentType(),
				job.getProbeProfileKey(), job.getRecipeDigest(), MediaJobAttemptResponse.from(attempt),
				job.getCreatedAt(), job.getUpdatedAt());
	}
}
